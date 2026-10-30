package com.dbuezas.traductor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the phone says goes through here: the only owner of the TextToSpeech queue, on the
 * UI thread. It reads translations aloud in utterance order, sentence by sentence as they come,
 * keeps per utterance how far it has been read, and plays one item at a time (QUEUE_FLUSH, the
 * next one when the previous is done), so nothing is lost or repeated behind its back.
 *
 * Rules: nothing starts while someone is talking (an utterance is recording); the mic is muted
 * while the phone talks; ▶ on a bubble reads it now and skips the older ones that were still
 * waiting; ⏸ and talking (turn button) stop it and skip what was waiting.
 */
final class Speaker {
    private static final String TAG = "traductor";

    interface Host {
        /** The side that reads this language now (0 = A, bottom; 1 = B, top), or -1 for none. */
        int sideOf(Lang l);

        float rate(int side);

        /** Mute the mic while the phone talks (auto mode). */
        void setMuted(boolean muted);

        /** What is being said changed: redraw play buttons and the state chips. */
        void onSpeakingChanged();
    }

    /** How far an utterance has been read: whole translations, then characters of the next. */
    private static final class Track {
        Engine.UttView v;
        int index, chars;
        boolean finished;   // read to the end, skipped or dropped
    }

    private static final class Item {
        final String id;
        final int uttId;     // -1 for a voice sample
        final int side;
        final Lang lang;
        final String text;
        final boolean auto;  // read in order (not ▶ or a sample)
        final int fromIndex, fromChars;   // auto: the track position before it, to read it again

        Item(String id, int uttId, int side, Lang lang, String text, boolean auto, int fromIndex, int fromChars) {
            this.id = id;
            this.uttId = uttId;
            this.side = side;
            this.lang = lang;
            this.text = text;
            this.auto = auto;
            this.fromIndex = fromIndex;
            this.fromChars = fromChars;
        }
    }

    // the end of a sentence followed by more text: ? ! … always; a period only after a word of 3 or
    // more letters or a digit and before a capital ("z. B.", "Dr. Müller" and "3.5" are not ends)
    private static final Pattern SENTENCE_END = Pattern.compile(
            "(?:[?!…]|(?<=[^\\s.]{3}|\\d)\\.)[\"»”)]?(?=\\s+[\\p{Lu}¿¡\"«])|[?!…][\"»”)]?(?=\\s+\\S)");
    private static final long ECHO_MS = 300;   // the mic stays muted a bit after the voice: room echo

    private final Context c;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextToSpeech tts;
    private boolean ready;
    private final TreeMap<Integer, Track> tracks = new TreeMap<>();   // by utterance id = order
    private Item current;
    private int nextItem;
    private final Map<Lang, Voice> autoVoices = new HashMap<>();   // the engine's own pick per language
    private final Runnable unmute;

    Speaker(Context c, Host host, Runnable onReady) {
        this.c = c;
        this.host = host;
        unmute = () -> host.setMuted(false);
        tts = new TextToSpeech(c, status -> ui.post(() -> {
            ready = status == TextToSpeech.SUCCESS;
            if (!ready) return;
            onReady.run();
            pump();
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { ui.post(() -> ended(id)); }
            @Override public void onError(String id) { ui.post(() -> ended(id)); }
            @Override public void onStop(String id, boolean interrupted) { ui.post(() -> ended(id)); }
        });
    }

    boolean isReady() {
        return ready;
    }

    TextToSpeech tts() {
        return tts;
    }

    void shutdown() {
        ui.removeCallbacks(unmute);
        tts.shutdown();
    }

    // ---- what is being said ----

    /** The utterance being read aloud now, or -1. */
    int playingUtt() {
        return current == null ? -1 : current.uttId;
    }

    /** The side being spoken to now, or -1. */
    int playingSide() {
        return current == null ? -1 : current.side;
    }

    // ---- input ----

    /** An utterance changed (UI thread). */
    void update(Engine.UttView v) {
        Track t = tracks.get(v.id);
        if (t == null) {
            if (v.dropped()) return;
            t = new Track();
            tracks.put(v.id, t);
        }
        t.v = v;
        if (v.dropped()) {
            t.finished = true;
            if (current != null && current.uttId == v.id) {
                stopCurrent();
                pump();
                if (current == null) afterVoice();
                return;
            }
        }
        pump();
    }

    /** ▶ on a bubble: read this translation now; older utterances still waiting are skipped. */
    void replay(Engine.UttView v) {
        int side = host.sideOf(v.target);
        if (!ready || side < 0) return;
        skipUpTo(v.id);
        Track t = tracks.get(v.id);
        if (t != null) {   // what it reads now counts as read
            t.index = v.translations.size();
            t.chars = 0;
            t.finished = v.complete();
        }
        play(new Item(newId(), v.id, side, v.target, v.translated(), false, 0, 0));
    }

    /** ⏸: stop; what was being read and the older utterances waiting are skipped. */
    void pause() {
        if (current == null) return;
        if (current.auto) skipUpTo(current.uttId);
        stopCurrent();
        afterVoice();
    }

    /** Someone starts talking (turn button): stop, and skip everything waiting so far. */
    void interruptForTalk() {
        if (!tracks.isEmpty()) skipUpTo(tracks.lastKey());
        stopCurrent();
        ui.removeCallbacks(unmute);
        host.setMuted(false);
        host.onSpeakingChanged();
    }

    /** Forget everything (mode change). */
    void clear() {
        tracks.clear();
        stopCurrent();
        ui.removeCallbacks(unmute);
        host.setMuted(false);
        host.onSpeakingChanged();
    }

    /** A voice sample: plays now; an interrupted translation is read again afterwards. */
    void sample(String text, Lang lang, int side) {
        if (!ready) return;
        if (current != null && current.auto) {
            Track t = tracks.get(current.uttId);
            if (t != null) {
                t.index = current.fromIndex;
                t.chars = current.fromChars;
                t.finished = false;
            }
        }
        play(new Item(newId(), -1, side, lang, text, false, 0, 0));
    }

    // ---- playing ----

    /** Starts the next sentence to read, if there is one and nobody is talking. */
    private void pump() {
        if (!ready || current != null) return;
        for (Track t : tracks.values()) if (!t.finished && t.v.recording()) return;   // someone talks
        for (Track t : tracks.values()) {
            if (t.finished) continue;
            Engine.UttView v = t.v;
            if (v.target == null) return;         // language not known yet: wait
            int side = host.sideOf(v.target);
            if (side < 0) {                       // nobody reads that language (any more)
                t.finished = true;
                continue;
            }
            while (true) {
                String text;
                int index = t.index, chars = t.chars;
                if (t.index < v.translations.size()) {           // a whole translation (or its rest)
                    String all = v.translations.get(t.index);
                    text = t.chars < all.length() ? all.substring(t.chars) : "";
                    t.index++;
                    t.chars = 0;
                } else if (t.index < v.pieces.size() && !v.translating.isEmpty()) {
                    // still being generated: its finished sentences
                    int end = lastSentenceEnd(v.translating, t.chars);
                    if (end < 0) return;
                    text = v.translating.substring(t.chars, end);
                    t.chars = end;
                } else if (v.complete()) {
                    t.finished = true;
                    break;   // the next utterance
                } else {
                    return;  // wait for more translation
                }
                if (text.trim().isEmpty()) continue;
                play(new Item(newId(), v.id, side, v.target, text.trim(), true, index, chars));
                return;
            }
        }
        drop();
    }

    private static int lastSentenceEnd(String text, int from) {
        Matcher m = SENTENCE_END.matcher(text);
        int end = -1;
        while (m.find()) if (m.end() > from) end = m.end();
        return end;
    }

    private void play(Item item) {
        if (current != null) tts.stop();
        current = item;
        ui.removeCallbacks(unmute);
        host.setMuted(true);
        setVoice(item.lang);
        tts.setSpeechRate(host.rate(item.side));
        Log.i(TAG, (item.auto ? "speak: " : "play: ") + item.text);
        tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, item.id);
        host.onSpeakingChanged();
    }

    /** TTS finished, failed or was stopped (binder thread, posted here). Stale ids are ignored. */
    private void ended(String id) {
        if (current == null || !current.id.equals(id)) return;
        current = null;
        pump();
        if (current == null) afterVoice();
    }

    private void stopCurrent() {
        if (current == null) return;
        current = null;
        tts.stop();
    }

    /** The phone stopped talking: unmute the mic a bit later, redraw. */
    private void afterVoice() {
        ui.removeCallbacks(unmute);
        ui.postDelayed(unmute, ECHO_MS);
        host.onSpeakingChanged();
    }

    /** Marks this utterance and all older ones as read. */
    private void skipUpTo(int uttId) {
        for (Track t : tracks.headMap(uttId, true).values()) {
            if (t.v.recording()) continue;   // still being said: its text comes later
            t.finished = true;
        }
    }

    /** Forgets finished utterances at the start (their ▶ uses the bubble's own copy). */
    private void drop() {
        while (!tracks.isEmpty() && tracks.firstEntry().getValue().finished) tracks.pollFirstEntry();
    }

    private String newId() {
        return "say-" + (nextItem++);
    }

    // ---- voices ----

    /** Sets the voice for a language: the one picked in settings, else the engine's default. */
    private void setVoice(Lang lang) {
        Voice v = Voices.find(c, tts, lang);
        if (v != null && tts.setVoice(v) == TextToSpeech.SUCCESS) return;
        int r = tts.setLanguage(lang.locale);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(new Locale(lang.locale.getLanguage()));
        }
        autoVoices.put(lang, tts.getVoice());
    }

    /** The voice that reads this language: the one picked, else the one the engine uses. */
    Voice voiceFor(Lang l) {
        if (!ready) return null;
        Voice v = Voices.find(c, tts, l);
        if (v != null) return v;
        if (!autoVoices.containsKey(l) && current == null) setVoice(l);
        return autoVoices.get(l);
    }
}
