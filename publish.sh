#!/bin/bash
# 发布到 GitHub：打包 → 生成 update.json → 建 Release → 提交清单
#
# 用法：
#   gh auth login          # 只需一次，令牌失效后要重做
#   bash publish.sh        # 打包并发布当前版本
#   bash publish.sh --dry  # 只打包并生成本地清单，不碰 GitHub
#
# 版本号仍以 android/AndroidManifest.xml 为唯一来源；
# 仓库地址从 AppConfig.java 的 OWNER/REPO 读，脚本和 App 不会各写一份。
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
SRC="$PROJ/android"
CFG="$SRC/src/com/pankscan/app/AppConfig.java"
DRY=0
[ "$1" = "--dry" ] && DRY=1

VER="$(grep -o 'android:versionName="[^"]*"' "$SRC/AndroidManifest.xml" | head -1 | sed 's/.*="//;s/"//')"
VC="$(grep -o 'android:versionCode="[^"]*"' "$SRC/AndroidManifest.xml" | head -1 | sed 's/.*="//;s/"//')"
OWNER="$(grep -o 'String OWNER = "[^"]*"' "$CFG" | sed 's/.*= "//;s/"//')"
REPO="$(grep -o 'String REPO = "[^"]*"' "$CFG" | sed 's/.*= "//;s/"//')"
BRANCH="$(grep -o 'String BRANCH = "[^"]*"' "$CFG" | sed 's/.*= "//;s/"//')"
BRANCH="${BRANCH:-main}"

[ -z "$VER" ] && { echo "错误：读不到 versionName"; exit 1; }
if [ -z "$OWNER" ] || [ -z "$REPO" ]; then
  echo "错误：AppConfig.java 里 OWNER/REPO 没填"
  exit 1
fi

echo "版本：v$VER ($VC)   仓库：$OWNER/$REPO"

# 1) 打包（产物同时落到根目录和 releases/）
bash "$PROJ/makedist.sh"

SRC_APK="$PROJ/releases/盘库助手_v$VER.apk"
[ -f "$SRC_APK" ] || { echo "错误：找不到 $SRC_APK"; exit 1; }

# Release 资产名用纯 ASCII，避免中文 URL 在部分网络环境下解析失败
ASSET="pankscan_v$VER.apk"
TMP="$PROJ/build/publish"
mkdir -p "$TMP"
cp "$SRC_APK" "$TMP/$ASSET"

SHA="$(sha256sum "$TMP/$ASSET" | cut -d' ' -f1)"
SIZE_KB=$(( ($(stat -c%s "$TMP/$ASSET") + 1023) / 1024 ))
APK_URL="https://github.com/$OWNER/$REPO/releases/download/v$VER/$ASSET"

if [ -f "$PROJ/更新说明.txt" ]; then
  NOTES="$(cat "$PROJ/更新说明.txt" | sed 's/\\/\\\\/g; s/"/\\"/g' | awk 'BEGIN{ORS="\\n"} {print}')"
else
  NOTES="$(date +%Y-%m-%d) 更新内容写在这里，一行一条"
fi

cat > "$PROJ/update.json" <<JSON
{
  "versionCode": $VC,
  "versionName": "$VER",
  "apk": "$APK_URL",
  "size": "${SIZE_KB} KB",
  "sha256": "$SHA",
  "notes": "$NOTES"
}
JSON

echo
echo "update.json 已生成："
cat "$PROJ/update.json"
echo

if [ "$DRY" = "1" ]; then
  echo "dry-run：跳过 git 与 Release。清单在 $PROJ/update.json"
  exit 0
fi

# 2) 检查登录状态，令牌失效时直接停，别留下半截发布
if ! gh auth status >/dev/null 2>&1; then
  echo "错误：GitHub CLI 未登录或令牌已失效。先执行：gh auth login -h github.com"
  exit 1
fi

# 3) 仓库初始化（第一次发布时）
if [ ! -d "$PROJ/.git" ]; then
  echo "初始化本地仓库…"
  git -C "$PROJ" init -q
  git -C "$PROJ" add -A
  git -C "$PROJ" -c user.name="$OWNER" -c user.email="$OWNER@users.noreply.github.com" \
      commit -q -m "chore: 初始提交（盘库助手 v$VER）"
fi
if ! git -C "$PROJ" remote get-url origin >/dev/null 2>&1; then
  git -C "$PROJ" remote add origin "https://github.com/$OWNER/$REPO.git"
fi

# 4) 提交清单并推送（清单必须先进分支，App 才检查得到）
git -C "$PROJ" add update.json
if ! git -C "$PROJ" diff --cached --quiet; then
  git -C "$PROJ" -c user.name="$OWNER" -c user.email="$OWNER@users.noreply.github.com" \
      commit -q -m "release: v$VER 更新清单"
fi
git -C "$PROJ" push -u origin HEAD:"$BRANCH"

# 5) 建 Release 并上传 APK
if gh release view "v$VER" --repo "$OWNER/$REPO" >/dev/null 2>&1; then
  echo "Release v$VER 已存在，只补传安装包"
  gh release upload "v$VER" "$TMP/$ASSET" --repo "$OWNER/$REPO" --clobber
else
  gh release create "v$VER" "$TMP/$ASSET" \
     --repo "$OWNER/$REPO" \
     --title "盘库助手 v$VER" \
     --notes "安装包 $ASSET（${SIZE_KB} KB，SHA-256 ${SHA:0:16}…）
版本清单与更新说明见仓库根目录 update.json。"
fi

echo
echo "完成。App 内的「检查更新」会读到："
echo "  https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/update.json"
echo "  $APK_URL"
