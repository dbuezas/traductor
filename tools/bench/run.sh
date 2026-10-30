#!/usr/bin/env bash
# Measures each model of a list on the connected phone: copies the model to the phone (straight
# from Hugging Face, nothing is stored on this computer), runs bench, keeps the result, deletes
# the model. Models with a result already are skipped, so a stopped run can just be started again.
#
#   run.sh <fleurs dir> <results dir> <models list>
#
# models list lines: name \t whisper|translator \t general 0/1 \t url
set -euo pipefail
mkdir -p "$2"
FLEURS=$(cd "$1" && pwd); RESULTS=$(cd "$2" && pwd); LIST=$(cd "$(dirname "$3")" && pwd)/$(basename "$3")
cd "$(dirname "$0")"
export ANDROID_HOME=${ANDROID_HOME:-/usr/local/share/android-commandlinetools}
NDK=$ANDROID_HOME/ndk/29.0.14206865; CM=$ANDROID_HOME/cmake/4.1.2/bin
BUILD=${BUILD:-/tmp/traductor-bench}
DIR=/data/local/tmp/bench

"$CM/cmake" -S . -B "$BUILD" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DLLAMA_DIR="$PWD/../../third_party/llama.cpp" -DWHISPER_DIR="$PWD/../../third_party/whisper.cpp" >/dev/null
"$CM/cmake" --build "$BUILD" --target bench >/dev/null

adb shell mkdir -p $DIR
adb push -q "$BUILD/bench" "$FLEURS"/*.wav "$FLEURS/whisper.tsv" "$FLEURS/translate.tsv" $DIR/
adb shell am force-stop com.dbuezas.traductor   # the app would take CPU away

# waits until the phone cooled down, so each model starts at the same temperature (max 10 min)
cool() {
    for _ in $(seq 60); do
        t=$(adb shell dumpsys battery | awk '/temperature/ {print $2; exit}')
        [ "$t" -le 340 ] && return
        sleep 10
    done
}

while IFS=$'\t' read -r name kind general url <&3; do
    out="$RESULTS/$kind-$name.tsv"
    [ -s "$out" ] && continue
    echo "== $name"
    curl -sfL "$url" | adb exec-in "cat > $DIR/model"
    cool
    if [ "$kind" = whisper ]; then
        adb shell "cd $DIR && ./bench whisper model whisper.tsv" > "$out.part"
    else
        adb shell "cd $DIR && ./bench translate model $general translate.tsv" > "$out.part"
    fi
    mv "$out.part" "$out"
    adb shell rm -f $DIR/model
    python3 -I score.py "$FLEURS" "$out"
done 3< "$LIST"
