#!/bin/bash
# 无 Gradle 的 APK 构建脚本：aapt2 -> javac -> d8 -> 打包 -> zipalign -> apksigner
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="${TC:-$ROOT/../.toolchain}"
OUT="${OUT:-$ROOT/../pankscan_v1.0.apk}"

JAVA_HOME="$(ls -d "$TC"/jdk-* 2>/dev/null | head -1)"
BT="$(dirname "$(find "$TC" -name aapt2.exe | head -1)")"
AJAR="$(find "$TC" -name android.jar | head -1)"

if [ -z "$JAVA_HOME" ] || [ ! -x "$BT/aapt2.exe" ] || [ -z "$AJAR" ]; then
  echo "工具链缺失: JAVA_HOME=$JAVA_HOME BT=$BT AJAR=$AJAR"
  exit 1
fi

JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"

echo "JDK   : $JAVA_HOME"
echo "BT    : $BT"
echo "ANDROID: $AJAR"

cd "$ROOT"
rm -rf build
mkdir -p build/classes build/dex build/gen

# 版本号以 AndroidManifest.xml 为唯一来源，脚本里不再各写一遍
VC="$(grep -o 'android:versionCode="[^"]*"' AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')"
VN="$(grep -o 'android:versionName="[^"]*"' AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')"
[ -z "$VC" ] && VC=1
[ -z "$VN" ] && VN=1.0
echo "版本  : $VN ($VC)"

echo "[1/7] aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[2/7] aapt2 link"
"$BT/aapt2.exe" link \
  -o build/base.apk \
  -I "$AJAR" \
  --manifest AndroidManifest.xml \
  build/res.zip \
  --java build/gen \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VC" \
  --version-name "$VN" \
  -A assets

echo "[3/7] javac"
winp() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
CLS=""
JARS=""
if [ -d "$ROOT/libs" ]; then
  for j in "$ROOT"/libs/*.jar; do
    [ -f "$j" ] || continue
    w="$(winp "$j")"
    if [ -z "$CLS" ]; then CLS="$w"; else CLS="$CLS;$w"; fi
    JARS="$JARS $w"
  done
fi
CP="$(winp "$AJAR")"
[ -n "$CLS" ] && CP="$CP;$CLS"
echo "     依赖:${JARS:- 无}"
find src build/gen -name "*.java" > build/sources.txt
"$JAVAC" -source 8 -target 8 -nowarn -encoding UTF-8 \
  -J-Duser.language=en -J-Duser.country=US \
  -bootclasspath "$(winp "$AJAR")" -classpath "$CP" \
  -d build/classes @build/sources.txt > build/javac.log 2>&1 || {
    echo "!!! javac 失败："; cat build/javac.log; exit 1;
  }
CLASSCOUNT=$(find build/classes -name "*.class" | wc -l)
echo "     编译产出 $CLASSCOUNT 个 class"
if [ "$CLASSCOUNT" -lt 3 ]; then
  echo "!!! 编译结果异常（可能没引到依赖）"; cat build/javac.log; exit 1;
fi

echo "[4/7] d8"
find build/classes -name "*.class" > build/classes.txt
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJAR" --min-api 24 --output build/dex @build/classes.txt $JARS

echo "[5/7] 打包 classes.dex"
python - <<'PY'
import zipfile, shutil
shutil.copy('build/base.apk', 'build/packed.apk')
with zipfile.ZipFile('build/packed.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
PY

echo "[6/7] zipalign"
"$BT/zipalign.exe" -f 4 build/packed.apk build/aligned.apk

echo "[7/7] 签名"
KS="build/pankscan.jks"
if [ ! -f "$KS" ]; then
  if [ -f "$ROOT/pankscan.jks" ]; then
    cp "$ROOT/pankscan.jks" "$KS"
    echo "     复用工程内已有签名密钥"
  else
    "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass pankscan -keypass pankscan \
      -alias pankscan -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=PanScan, OU=IT, O=Yuguo, L=Shenzhen, ST=Guangdong, C=CN" >/dev/null 2>&1
    cp "$KS" "$ROOT/pankscan.jks"
    echo "     新生成签名密钥并保存到工程"
  fi
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:pankscan --key-pass pass:pankscan \
  --out build/pankscan.apk build/aligned.apk

"$JAVA" -jar "$BT/lib/apksigner.jar" verify --print-certs build/pankscan.apk | head -5

cp build/pankscan.apk "$OUT"
ls -lh "$OUT"
echo "构建完成"
