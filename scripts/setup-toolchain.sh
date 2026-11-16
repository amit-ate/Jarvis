#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
if [ ! -d "$JAVA_HOME" ]; then
  brew install openjdk@17
fi

if ! command -v sdkmanager >/dev/null 2>&1; then
  brew install --cask android-commandlinetools
fi
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

SDK_SRC="/opt/homebrew/share/android-commandlinetools"
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$(dirname "$ANDROID_HOME")"
if [ ! -e "$ANDROID_HOME" ]; then
  ln -s "$SDK_SRC" "$ANDROID_HOME"
fi

yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platforms;android-34" \
  "platforms;android-28" \
  "build-tools;34.0.0" \
  "platform-tools" \
  "cmake;3.22.1" \
  "ndk;26.3.11579264"

echo "=== Toolchain Verification ==="
java -version
ls -1 "$ANDROID_HOME/ndk"
ls -1 "$ANDROID_HOME/cmake"
