package com.dbuezas.traductor;

/** JNI bridge to whisper.cpp and llama.cpp. Call only from the single worker thread. */
final class Native {
    static {
        System.loadLibrary("traductor");
    }

    interface PartialListener {
        void onPartial(byte[] utf8SoFar);
    }

    /** While true, the running transcription/translation stops as soon as it can. */
    static native void setAbort(boolean abort);

    static final int MAIN = 0;   // the native side has two whisper slots; the app uses one

    /** Loads a whisper model into a slot; a null path unloads the slot. */
    static native boolean loadWhisper(int slot, String path);

    /** Language of the audio of the last transcribe() call: whichever of the two is more likely. */
    static native String detectLanguage(int slot, String langA, String langB);

    /** One line per word, "endMs\tword", UTF-8. prompt: text said just before, for context. */
    static native byte[] transcribe(int slot, float[] pcm, String lang, String prompt, boolean fullCtx);

    static native boolean loadTranslator(String path);

    /** Where the evaluated translator instructions are saved, to load them fast next time. */
    static native void setCacheDir(String dir);

    /** Prepares one translation direction ahead of time (the two most recent are kept). */
    /** general: a general Gemma (plain instruction) instead of TranslateGemma's own prompt format. */
    static native void prepareTranslation(String srcName, String srcCode, String tgtName, String tgtCode, boolean general);

    static native byte[] translate(String srcName, String srcCode, String tgtName, String tgtCode,
                                   String text, boolean general, PartialListener listener);

    private Native() {}
}
