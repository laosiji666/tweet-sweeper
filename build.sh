#!/usr/bin/env bash
# 一键构建：生成签名密钥（如果还没有）→ 编译 release APK
# 需要：JDK 17、Android SDK（platform 35 + build-tools 35）、Gradle（或仓库自带 gradlew）
set -euo pipefail

cd "$(dirname "$0")"

# --- JDK ---
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /opt/jdk17 /usr/lib/jvm/*jdk*; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
[ -x "${JAVA_HOME:-}/bin/java" ] || { echo "找不到 JDK 17，请设置 JAVA_HOME"; exit 1; }

# --- Android SDK ---
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
[ -d "$ANDROID_HOME/platforms/android-35" ] || echo "提示：$ANDROID_HOME 里没有 android-35，构建可能失败"
export PATH="$JAVA_HOME/bin:$PATH"

# --- Gradle ---
if [ -z "${GRADLE_BIN:-}" ]; then
  if [ -x ./gradlew ]; then GRADLE_BIN=./gradlew; else GRADLE_BIN=gradle; fi
fi

# --- 签名密钥（只在本机生成，永不提交）---
if [ ! -f keystore.properties ]; then
  KS_PASS="${KS_PASS:-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')}"
  echo "==> 生成签名密钥 release.keystore（密码已写入 keystore.properties，请自行备份）"
  keytool -genkeypair -v \
    -keystore release.keystore \
    -alias tweetsweeper \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=TweetSweeper, O=TweetSweeper, C=CN" >/dev/null
  cat > keystore.properties <<EOF
storeFile=release.keystore
storePassword=$KS_PASS
keyAlias=tweetsweeper
keyPassword=$KS_PASS
EOF
  echo "   keystore.properties 与 release.keystore 已在 .gitignore 中，不要提交"
fi

echo "==> 编译 release"
"$GRADLE_BIN" assembleRelease --console=plain

APK=$(ls -1 app/build/outputs/apk/release/*.apk | head -1)
echo "==> 产物：$APK"
