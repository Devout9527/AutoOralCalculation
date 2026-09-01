#!/usr/bin/env bash
# AutoOralCalculation 打包脚本 —— 带版本号+名字，按目录归档，旧包不删除
set -e

cd "$(dirname "$0")"
APP_NAME="AutoOralCalculation"
TARGET="小猿AI_3.140"
VERSION_FILE="VERSION"

# 版本号递增：默认读 VERSION 文件 +1，也可用 ./build_pkg.sh 1.7.5 指定
if [ -n "$1" ]; then
  VER="$1"
else
  CUR="1.7.3"
  [ -f "$VERSION_FILE" ] && CUR="$(cat "$VERSION_FILE")"
  # 递增三位里的最后一段
  MAJOR="${CUR%%.*}"
  REST="${CUR#*.}"
  MINOR="${REST%%.*}"
  PATCH="${REST##*.}"
  PATCH=$((PATCH + 1))
  VER="$MAJOR.$MINOR.$PATCH"
fi
echo "$VER" > "$VERSION_FILE"

# 写入版本到 build.gradle.kts
sed -i "s/versionName = \".*\"/versionName = \"$VER\"/" app/build.gradle.kts
sed -i "s/versionCode = [0-9]*/versionCode = $(( $(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts | head -1) + 1))/" app/build.gradle.kts
echo ">>> 版本 -> v$VER"

# 构建
export ANDROID_HOME=/workspace/android-sdk
./gradlew assembleDebug --no-daemon -q 2>&1 | tail -8

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="/workspace/包发布/${APP_NAME}_${TARGET}_v${VER}_${STAMP}.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT"
chmod 666 "$OUT"
echo ">>> 已归档: $OUT"
echo ">>> 现有包："
ls -1 /workspace/包发布/
