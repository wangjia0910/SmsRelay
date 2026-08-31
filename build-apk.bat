@echo off
REM SmsRelay 一键编译脚本（Windows）
REM 前置：JDK 17、Android SDK（且已安装 platforms;android-37 与 build-tools）
REM 用法：build-apk.bat            -> 产出 debug apk（默认，可直接 adb install）
REM       build-apk.bat release    -> 产出已签名的 release apk（需 local.properties 配置签名，否则回退 debug 密钥）
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo [SmsRelay] 缺少 JDK 17，请先安装：https://adoptium.net
  pause & exit /b 1
)

if not exist gradlew (
  echo [SmsRelay] 缺少 gradlew，请先执行：gradle wrapper --gradle-version 8.11.1
  pause & exit /b 1
)

set TASK=assembleDebug
if "%1"=="release" set TASK=assembleRelease

echo [SmsRelay] 开始编译 %TASK% ...
call gradlew %TASK%

if "%TASK%"=="assembleDebug" (
  set APK=app\build\outputs\apk\debug\app-debug.apk
) else (
  set APK=app\build\outputs\apk\release\app-release.apk
)

echo [SmsRelay] 产物：%APK%
echo 连接手机开启 USB 调试后执行：adb install -r %APK%
pause
