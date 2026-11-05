#!/bin/bash
# 发布到 GitHub：打包 → 放一份到 dist/ → 生成清单 → 推 main 与 tag → 建 Release
#
# 用法：
#   bash publish.sh        # 打包并发布当前版本
#   bash publish.sh --dry  # 只打包并生成本地清单，不碰 GitHub
#
# 版本号仍以 android/AndroidManifest.xml 为唯一来源；
# 仓库地址从 AppConfig.java 的 OWNER/REPO 读，脚本和 App 不会各写一份。
#
# 为什么安装包要再放一份到 dist/：
#   国内直接连 raw.githubusercontent.com 常常解析不了，GitHub Release 下载也慢，
#   而 jsDelivr 的 GitHub 镜像实测可直连。dist/ 里只留最新一版（历史版本在各版 tag
#   指向的旧提交里，地址依然有效），所以仓库不会被一堆 APK 撑大。
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

ASSET="pankscan_v$VER.apk"
DIST="$PROJ/dist"
mkdir -p "$DIST"
rm -f "$DIST"/*.apk
cp "$SRC_APK" "$DIST/$ASSET"

SHA="$(sha256sum "$DIST/$ASSET" | cut -d' ' -f1)"
SIZE_KB=$(( ($(stat -c%s "$DIST/$ASSET") + 1023) / 1024 ))

CDN_APK="https://cdn.jsdelivr.net/gh/$OWNER/$REPO@v$VER/dist/$ASSET"
GCORE_APK="https://gcore.jsdelivr.net/gh/$OWNER/$REPO@v$VER/dist/$ASSET"
CDN_MANIFEST="https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$BRANCH/update.json"

if [ -f "$PROJ/更新说明.txt" ]; then
  NOTES="$(cat "$PROJ/更新说明.txt" | sed 's/\\/\\\\/g; s/"/\\"/g' | awk 'BEGIN{ORS="\\n"} {print}')"
else
  NOTES="$(date +%Y-%m-%d) 更新内容写在这里，一行一条"
fi

# 两个地址都走 jsDelivr：本地网络下 Release 附件域名解析不了，CDN 反而稳
cat > "$PROJ/update.json" <<JSON
{
  "versionCode": $VC,
  "versionName": "$VER",
  "apk": "$CDN_APK",
  "apk2": "$GCORE_APK",
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
  echo "dry-run：跳过 git 与 Release。清单在 $PROJ/update.json，包在 $DIST/$ASSET"
  exit 0
fi

# 2) 提交并推送：清单进分支（App 才读得到），tag 让 CDN 有固定地址
cd "$PROJ"
[ -d .git ] || { echo "错误：这里不是 git 仓库，先 git init 并关联 origin"; exit 1; }
git remote get-url origin >/dev/null 2>&1 || \
  git remote add origin "https://github.com/$OWNER/$REPO.git"

git add -A
if ! git diff --cached --quiet; then
  git -c user.name="$OWNER" -c user.email="$OWNER@users.noreply.github.com" \
      commit -q -m "release: v$VER"
fi

git tag -f "v$VER" >/dev/null
git push origin "HEAD:$BRANCH"
git push -f origin "refs/tags/v$VER"
echo "已推送 $BRANCH 与 tag v$VER"

# 3) 建 Release 并上传 APK。gh 令牌常失效，失效时用 git 凭据直接调 API
CURL_NOPROXY="${CURL_NOPROXY:---noproxy=*}"

upload_release() {
  if gh auth status >/dev/null 2>&1; then
    if gh release view "v$VER" --repo "$OWNER/$REPO" >/dev/null 2>&1; then
      gh release upload "v$VER" "$DIST/$ASSET" --repo "$OWNER/$REPO" --clobber
    else
      gh release create "v$VER" "$DIST/$ASSET" \
         --repo "$OWNER/$REPO" \
         --title "盘库助手 v$VER" \
         --notes "安装包 $ASSET（${SIZE_KB} KB，SHA-256 ${SHA:0:16}…）"$'\n'"版本清单与更新说明见仓库根目录 update.json。"
    fi
    return 0
  fi

  local token rid
  token="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p' | head -1)"
  if [ -z "$token" ]; then
    echo "跳过 Release：既没有 gh 登录，也没有可用的 git 凭据"
    return 1
  fi

  # 已存在就复用，不重复建
  rid="$(curl $CURL_NOPROXY -sS -H "Authorization: Bearer $token" \
        "https://api.github.com/repos/$OWNER/$REPO/releases/tags/v$VER" \
        | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)"
  if [ -z "$rid" ]; then
    rid="$(curl $CURL_NOPROXY -sS -X POST \
        -H "Authorization: Bearer $token" \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/$OWNER/$REPO/releases" \
        -d "{\"tag_name\":\"v$VER\",\"name\":\"盘库助手 v$VER\",\"body\":\"安装包 $ASSET（${SIZE_KB} KB）\"}" \
        | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)"
  fi
  [ -z "$rid" ] && { echo "跳过 Release：接口没有返回 release id"; return 1; }

  curl $CURL_NOPROXY -sS -X POST \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$DIST/$ASSET" \
    "https://uploads.github.com/repos/$OWNER/$REPO/releases/$rid/assets?name=$ASSET" >/dev/null
  echo "Release v$VER 已上传 $ASSET"
}

upload_release || echo "（Release 未完成，不影响 App 更新：清单和包包都已进仓库，走 CDN）"

echo
echo "完成。App 内的「检查更新」按顺序读："
echo "  $CDN_MANIFEST"
echo "  清单里 apk 先走 $CDN_APK"
echo "  不行再切 $GH_APK"
