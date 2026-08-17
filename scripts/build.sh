#!/usr/bin/env bash
# build.sh — 自动写入时间戳版本，然后编译 host + client
set -euo pipefail

cd /data/wordbattle

# 1. 生成版本号 (精确到分钟)；大版本可用第一个参数覆盖，默认 2.2
MAJOR="${1:-2.2}"
TS_VERSION="v$MAJOR-$(date +%Y%m%d-%H%M)"

# 2. 替换 DebugLog.kt 中的 VERSION
VERSION_FILE="app/src/main/java/com/wordbattle/debug/DebugLog.kt"
sed -i "s/const val VERSION = \".*\"/const val VERSION = \"$TS_VERSION\"/" "$VERSION_FILE"

echo "==> Version set to: $TS_VERSION"

# 3. 编译（clean 防止 UP-TO-DATE 导致旧 APK；改 .kt 后必须 clean，见 README）
export ANDROID_HOME=/usr/lib/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

/opt/gradle-8.2/bin/gradle :app:clean :app:assembleDebug

echo "==> Build done. APKs:"
ls -1 app/build/outputs/apk/*/debug/*.apk
