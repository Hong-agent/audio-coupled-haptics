#!/usr/bin/env bash
# 一键构建 debug APK（默认使用本机已装好的 JDK / SDK / Gradle 路径，可用环境变量覆盖）。
#
# 用法:  bash tools/build_apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/home/t/android-sdk}"
GRADLE="${GRADLE:-/home/t/gradle-dist/gradle-8.7/bin/gradle}"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "GRADLE=$GRADLE"

"$GRADLE" -p "$ROOT" --console=plain :app:assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo
echo "APK: $APK  ($(stat -c%s "$APK") 字节)"
echo "安装: $ANDROID_HOME/platform-tools/adb install -r \"$APK\""
