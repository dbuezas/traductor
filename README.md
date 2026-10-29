# Traductor

An Android app for a conversation between two people who speak different languages. The phone
lies between them: each one reads their half of the screen (the top half is upside down), speaks,
and hears the other person's words translated aloud. Everything runs on the phone, offline.

- Speech to text: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (Whisper large-v3 turbo by default)
- Translation: [llama.cpp](https://github.com/ggml-org/llama.cpp) with
  [TranslateGemma](https://huggingface.co/google/translategemma-4b-it) 4B, or a general Gemma 3
- Text to speech: the phone's own voices

I built it to talk with family in German and Spanish; it also has English, French, Italian,
Portuguese and Dutch. Each half of the screen talks to its reader in that half's language; the
rest of the interface follows the phone's language.

## Using it

1. Install the APK from [Releases](../../releases/latest) (or build it yourself). Each push to
   `main` makes a new release, and newer ones install as updates.
2. Open ⚙ and download a Whisper model and a translator. They come from Hugging Face once;
   after that no internet is needed.
3. Pick the two languages with the chips at the top of each half.

Two modes:

- **Auto**: the app listens all the time (the round button pauses it), detects who speaks by
  the language, and cuts phrases at pauses.
- **Turns**: each person holds the button on their side while talking.

Text that may still change is shown faded. Each finished translation has a ▶ button to play it
again; the 🔊 chip on each side sets how fast the phone reads to that person.

## How it works

- While someone talks, the not yet settled audio is transcribed again and again with the same
  Whisper model; a running pass is restarted when more speech comes. When the speaker stops,
  the running pass already has all the audio and becomes the final text, without another pass.
- Sentences that are settled (two passes agree, or they ended well before the end of the audio)
  go to the translator right away.
- The translator's fixed instructions are evaluated once and saved to a file, so they load
  instantly later.
- Spanish translations are switched to Rioplatense "vos" with a word list (TranslateGemma does
  not do voseo; a general Gemma is asked for it directly).

### Changes to whisper.cpp

`patches/whisper.patch` is applied to the `third_party/whisper.cpp` submodule automatically on
the first build (see `app/src/main/cpp/CMakeLists.txt`). It adds:

- `whisper_lang_detect_after_full[_with_state]`: language detection that reuses the encoder output
  of the last `whisper_full` call (13 ms instead of a second encoder run of several seconds).
- The abort callback in the encoder: a pass can be stopped within ~0.1 s, not only after the
  encoder finished (which takes seconds on a phone).

Also worth knowing (not patched, worked around in `native.cpp`): after a `whisper_full` call
with a bigger `audio_ctx`, later calls on the same state with a smaller one produce nonsense. The
app keeps one whisper state per window size (384, 768, 1500).

## Building

```sh
git clone --recursive https://github.com/dbuezas/traductor
cd traductor
./gradlew assembleRelease
```

Needs JDK 17 and the Android SDK with NDK 29.0.14206865 and CMake 4.1.2 (see
`.github/workflows/build.yml`). The APK is arm64 only. It is signed with the app's key when
`~/.android/traductor-release.jks` (or `TRADUCTOR_KEYSTORE`) is there, else with the standard
debug key; a debug-signed build cannot update a release install.

`tools/install.sh` builds and installs on a connected phone. `tools/replay-wav.sh file.wav` plays
a 16 kHz WAV through the app instead of the mic and prints the log, which is how I test it.
