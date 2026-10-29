#!/usr/bin/env bash
# Builds and installs the debug app, then plays a 16 kHz mono 16-bit WAV through it instead of
# the mic, and prints the app log. Needs the models in place on the phone.
# Usage: tools/replay-wav.sh file.wav [es]   (with a language: replay as one turn in it)
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/Users/davidbuezas/code/night-android/.tools/jdk-17.0.13+11/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/usr/local/share/android-commandlinetools}

./gradlew assembleDebug --console=plain -q
adb install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
adb push "$1" /data/local/tmp/test.wav > /dev/null
adb shell 'run-as com.dbuezas.traductor cp /data/local/tmp/test.wav files/test.wav'
adb logcat -c
turn=()
[ $# -ge 2 ] && turn=(--es turn "$2")
# "at" must be fresh, so an old test intent is not replayed when the app is opened later
adb shell am start -n com.dbuezas.traductor/.MainActivity --es wav files/test.wav --el at "$(date +%s)000" "${turn[@]}" > /dev/null
adb logcat -s traductor
