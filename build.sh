#!/usr/bin/env bash
# 不依赖 Gradle / Android Studio 的打包脚本：aapt2 → javac → dx → 对齐 → v2 签名
set -euo pipefail
cd "$(dirname "$0")"

TOOLS=${TOOLS:?请设置 TOOLS 为放 aapt2、android-34.jar、dx.jar、signv2.jar 的目录}
AAPT2=$TOOLS/aapt2
ANDROID_JAR=$TOOLS/android-34.jar
DX=$TOOLS/dx.jar
SIGNER=$TOOLS/signv2.jar
KEYSTORE=keystore/release.p12
STOREPASS=${STOREPASS:-zouzou-local}
ALIAS=zouzou
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-0.1}
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$STOREPASS" \
    -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Qilai Zouzou, O=Personal" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
fi

echo "== 1/5 编译资源"
"$AAPT2" compile --dir res -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/res.apk" -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java "$OUT/gen" "$OUT/res.zip" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

run() {  # 运行命令，过滤掉代理提示行；失败时打印输出并退出
  local log="$OUT/step.log"
  if ! "$@" > "$log" 2>&1; then grep -v JAVA_TOOL_OPTIONS "$log"; exit 1; fi
  grep -v JAVA_TOOL_OPTIONS "$log" || true
}

echo "== 2/5 编译 Java"
run javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "== 3/5 转成 dex"
run java -jar "$DX" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"

echo "== 4/5 打包对齐"
python3 tools/pack_apk.py "$OUT/res.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk"

echo "== 5/5 签名"
run java -jar "$SIGNER" "$OUT/unsigned.apk" "$OUT/qilai-zouzou.apk" "$KEYSTORE" "$STOREPASS" "$ALIAS"
ls -la "$OUT/qilai-zouzou.apk"
