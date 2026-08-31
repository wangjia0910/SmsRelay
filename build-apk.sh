#!/usr/bin/env bash
# SmsRelay 一键编译脚本（Linux / macOS）
# 前置：JDK 17、Android SDK（且已安装 platforms;android-37 与 build-tools）
# 用法：./build-apk.sh            -> 产出 debug apk（默认，可直接 adb install）
#       ./build-apk.sh release    -> 产出已签名的 release apk（需在 local.properties 配置签名，否则回退 debug 密钥）
set -e
cd "$(dirname "$0")"

echo "[SmsRelay] 检查 JDK ..."
if ! command -v java >/dev/null 2>&1; then
  echo "缺少 JDK 17，请先安装：https://adoptium.net （或 Android Studio 自带的 JDK）"
  exit 1
fi

if [ ! -f gradlew ]; then
  echo "缺少 gradlew，请先执行：gradle wrapper --gradle-version 8.11.1"
  exit 1
fi

TASK="assembleDebug"
if [ "$1" = "release" ]; then TASK="assembleRelease"; fi

echo "[SmsRelay] 开始编译 $TASK ..."
./gradlew "$TASK"

if [ "$TASK" = "assembleDebug" ]; then
  APK="app/build/outputs/apk/debug/app-debug.apk"
else
  APK="app/build/outputs/apk/release/app-release.apk"
fi

echo "[SmsRelay] 产物：$APK"
echo "连接手机并开启 USB 调试后执行："
echo "  adb install -r \"$APK\""
