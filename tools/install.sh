#!/usr/bin/env bash
# Build the release APK (signed with the debug key) and install it on the connected phone.
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME=/Users/davidbuezas/code/night-android/.tools/jdk-17.0.13+11/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
ADB="$ANDROID_HOME/platform-tools/adb"

./gradlew assembleRelease
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
echo "Installed."
