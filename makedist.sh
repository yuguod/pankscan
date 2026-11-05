#!/bin/bash
# 一键重新打包 APK
# 用法：bash makedist.sh
# 说明：Android 构建工具（aapt2）无法处理含中文的路径，所以构建在
#       C:/Users/yuguo/pankscan-build 下进行，产物再拷回本项目。
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
APP="$PROJ/app"
SRC="$PROJ/android"
BUILD="/c/Users/yuguo/pankscan-build"
TC="$BUILD/tc"
WORK="$BUILD/proj"

# 版本号统一从 AndroidManifest.xml 读取，避免脚本、清单、说明文档三处各写一遍
VER="$(grep -o 'android:versionName="[^"]*"' "$SRC/AndroidManifest.xml" | head -1 | sed 's/.*="//;s/"//')"
[ -z "$VER" ] && { echo "错误：读不到 versionName"; exit 1; }

OUT="$BUILD/pankscan_v$VER.apk"
FINAL="$PROJ/盘库助手_v$VER.apk"
ARCHIVE_DIR="$PROJ/releases"
ARCHIVE="$ARCHIVE_DIR/盘库助手_v$VER.apk"
echo "版本：v$VER"

echo "[0/3] 同步网页资源到 android/assets"
rm -rf "$SRC/assets"
mkdir -p "$SRC/assets"
cp "$APP/index.html" "$APP/styles.css" "$APP/app.js" "$APP/manifest.webmanifest" "$SRC/assets/"
cp -r "$APP/vendor" "$APP/data" "$APP/icons" "$SRC/assets/"
mkdir -p "$SRC/res/mipmap-xxxhdpi"
cp "$APP/icons/icon-512.png" "$SRC/res/mipmap-xxxhdpi/ic_launcher.png"

echo "[1/3] 复制工程到英文路径 $WORK"
rm -rf "$WORK"
cp -r "$SRC" "$WORK"
rm -rf "$WORK/build"

echo "[2/3] 构建"
cd "$WORK"
TC="$TC" OUT="$OUT" bash build.sh

echo "[3/3] 拷回项目目录并归档"
cp "$OUT" "$FINAL"
# 每个版本都留一份到 releases/，不覆盖、不删除旧版本
mkdir -p "$ARCHIVE_DIR"
if [ -f "$ARCHIVE" ]; then
  echo "     注意：releases 里已有同版本包，本次重新构建覆盖它"
fi
cp "$OUT" "$ARCHIVE"

# 根目录只留最新版：旧包必须已在 releases/ 里有同名副本才清理，否则留着不动
for old in "$PROJ"/盘库助手_v*.apk; do
  [ -f "$old" ] || continue
  [ "$old" = "$FINAL" ] && continue
  base="$(basename "$old")"
  if [ -f "$ARCHIVE_DIR/$base" ]; then
    rm -f "$old"
    echo "     清理根目录旧包：$base（releases 中已归档）"
  else
    echo "     跳过：$base 在 releases 中没有副本，保留以免丢包"
  fi
done

ls -lh "$FINAL"
echo "完成：$FINAL"
echo "归档：$ARCHIVE"
echo "历史版本：$(ls "$ARCHIVE_DIR"/*.apk 2>/dev/null | wc -l) 个（$ARCHIVE_DIR）"
