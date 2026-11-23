#!/bin/bash
# Momo记账 一键构建：环境检查/下载 + 编译 APK
set -e
cd /root

# 1. Gradle
if [ ! -x /root/gradle-8.7/bin/gradle ]; then
  echo "[setup] downloading gradle..."
  curl -sL --retry 3 -o gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip
  unzip -q -o gradle.zip
fi
echo "[setup] gradle ok"

# 2. SDK cmdline-tools
if [ ! -x /root/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "[setup] downloading sdk cmdline-tools..."
  curl -sL --retry 3 -o clt.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip
  mkdir -p android-sdk/cmdline-tools
  unzip -q -o clt.zip -d android-sdk/cmdline-tools
  rm -rf android-sdk/cmdline-tools/latest
  mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
fi
echo "[setup] cmdline-tools ok"

# 3. build-tools
if [ ! -f /root/android-sdk/build-tools/34.0.0/aapt2 ]; then
  echo "[setup] downloading build-tools..."
  curl -sL --retry 3 -o bt.zip https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r34-linux.zip
  mkdir -p android-sdk/build-tools
  unzip -q -o bt.zip -d android-sdk/build-tools
  rm -rf android-sdk/build-tools/34.0.0
  mv android-sdk/build-tools/android-14 android-sdk/build-tools/34.0.0
fi
echo "[setup] build-tools ok"

# 4. platform android-34
if [ ! -f /root/android-sdk/platforms/android-34/android.jar ]; then
  echo "[setup] installing platform 34 via sdkmanager..."
  yes | /root/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true
  /root/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" >/dev/null 2>&1
fi
echo "[setup] platform ok"

# 5. sync source & build
rsync -a --delete /mnt/agents/momo-src/ /root/momo/
cd /root/momo

# 5.1 图标：repo 中以 base64 文本保存（icons/*.b64），构建前解码为 PNG
if [ -d icons ]; then
  for b in icons/*.b64; do
    [ -f "$b" ] || continue
    name=$(basename "$b" .b64)
    dpi="${name%%_*}"; icon="${name#*_}"
    mkdir -p "app/src/main/res/$dpi"
    base64 -d "$b" > "app/src/main/res/$dpi/$icon.png"
  done
  echo "[setup] icons decoded"
fi

echo "sdk.dir=/root/android-sdk" > local.properties
echo "[build] assembling..."
ANDROID_HOME=/root/android-sdk /root/gradle-8.7/bin/gradle assembleDebug --no-daemon 2>&1 | tail -5
cp -f app/build/outputs/apk/debug/app-debug.apk "/mnt/agents/output/Momo记账-v$1.apk"
echo "[done] /mnt/agents/output/Momo记账-v$1.apk"
