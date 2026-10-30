package com.dbuezas.traductor;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to the mic, cuts speech into utterances (at pauses, or by the talk button in turn mode),
 * and runs them through whisper and the translator on one worker thread.
 *
 * While someone talks, the not yet settled audio is transcribed again and again; a running pass
 * is restarted whenever more speech comes. So when the speaker stops, the running pass already
 * has all the audio, and it becomes the final one without waiting for the pause to be confirmed.
 * A sentence (whisper segment) is settled when two passes in a row agree on it, or when it ended
 * well before the audio heard so far: its audio is not transcribed again, and it goes to the
 * translator right away.
 *
 * The engine only produces text. It reports every change as an immutable {@link UttView}; what is
 * read aloud, and when, is decided by {@link Speaker} on the UI thread.
 */
final class Engine {
    private static final String TAG = "traductor";
    private static final int RATE = 16000;
    private static final int CHUNK = RATE / 10;              // 100 ms
    private static final int START_CHUNKS = 2;               // 200 ms of speech starts an utterance
    private static final int PREROLL_CHUNKS = 3;             // keep 300 ms before the start
    // 1.5 s of silence ends it: shorter pauses between sentences stay in one bubble, and the voice
    // does not start in such a pause (the final pass starts at 300 ms anyway)
    private static final int END_SILENCE_CHUNKS = 15;
    private static final int MAX_CHUNKS = 200;               // cut very long talk every 20 s
    private static final long FIRST_PARTIAL_MS = 1000;
    // a pass over this much unsettled audio is not restarted: its settled sentences get translated
    // while the speaker goes on
    private static final int LONG_UNSETTLED_SAMPLES = RATE * 4;
    private static final int SILENCE_START_CHUNKS = 3;    // 300 ms quiet: restart with all speech, may become the final
    private static final int SETTLED_AFTER_MS = 1500;
    private static final int MAX_CHARS_PER_SECOND = 30;   // fast speech is ~15-20

    enum Phase {
        RECORDING,      // audio still coming in
        TRANSCRIBING,   // the speaker stopped; the last part is being transcribed
        DONE,           // all text is settled (translations may still be running)
        DROPPED         // nothing was said, or thrown away: the UI removes it
    }

    /**
     * One utterance. The worker thread writes the text; the audio thread writes the phase. Shared
     * fields are only touched in synchronized methods, and the UI only sees {@link #view()} copies.
     */
    static final class Utt {
        final int id;
        // worker thread only
        volatile int committedSamples;   // audio before this is settled (read by the audio thread)
        int passCovered;           // buffer length the last finished pass transcribed
        List<String> prevTail = new ArrayList<>();   // last pass's unsettled segments

        private Phase phase = Phase.RECORDING;
        private Lang lang;         // null until detected
        private Lang shownLang;    // a guess, to show early text before detection
        private Lang target;       // set with lang: the language it is translated into
        private final List<String> pieces = new ArrayList<>();             // settled sentences
        private final Map<Integer, String> translations = new HashMap<>(); // piece index -> translation
        private String tail = "";           // text that may still change
        private String translating = "";    // the translation being generated (of the first missing piece)

        Utt(int id) {
            this.id = id;
        }

        synchronized Phase phase() { return phase; }
        synchronized Lang lang() { return lang; }
        synchronized Lang target() { return target; }
        synchronized String committed() { return String.join(" ", pieces); }
        synchronized String piece(int i) { return pieces.get(i); }
        synchronized boolean dropped() { return phase == Phase.DROPPED; }

        synchronized void setLang(Lang l, Lang to) {
            lang = l;
            target = to;
        }

        synchronized void setGuess(Lang l, String text) {
            shownLang = l;
            tail = text;
        }

        /** Adds a settled sentence; returns its index. */
        synchronized int addPiece(String p) {
            pieces.add(p);
            return pieces.size() - 1;
        }

        synchronized boolean isPiece(String normalized) {
            for (String p : pieces) if (norm(p).equals(normalized)) return true;
            return false;
        }

        synchronized void setTail(String t) { tail = t; }
        synchronized void setTranslating(String t) { translating = t; }

        synchronized void setTranslation(int index, String t) {
            translations.put(index, t);
            translating = "";
        }

        /** RECORDING -> TRANSCRIBING; false if it was dropped meanwhile. */
        synchronized boolean stopRecording() {
            if (phase != Phase.RECORDING) return false;
            phase = Phase.TRANSCRIBING;
            return true;
        }

        /** All text is settled: DONE, or DROPPED if nothing was said. */
        synchronized void finish() {
            if (phase == Phase.DROPPED) return;
            tail = "";
            phase = pieces.isEmpty() ? Phase.DROPPED : Phase.DONE;
        }

        /** False if it was dropped already. */
        synchronized boolean drop() {
            if (phase == Phase.DROPPED) return false;
            phase = Phase.DROPPED;
            return true;
        }

        synchronized UttView view() {
            List<String> done = new ArrayList<>();   // translations of pieces 0..n-1, without gaps
            while (translations.containsKey(done.size())) done.add(translations.get(done.size()));
            return new UttView(id, phase, lang, shownLang, target, new ArrayList<>(pieces), done, tail, translating);
        }
    }

    /** A consistent, immutable copy of an utterance, for the UI thread. */
    static final class UttView {
        final int id;
        final Phase phase;
        final Lang lang, shownLang, target;
        final List<String> pieces, translations;   // translations: of the first pieces, in order
        final String tail, translating;

        UttView(int id, Phase phase, Lang lang, Lang shownLang, Lang target, List<String> pieces,
                List<String> translations, String tail, String translating) {
            this.id = id;
            this.phase = phase;
            this.lang = lang;
            this.shownLang = shownLang;
            this.target = target;
            this.pieces = Collections.unmodifiableList(pieces);
            this.translations = Collections.unmodifiableList(translations);
            this.tail = tail;
            this.translating = translating;
        }

        boolean recording() { return phase == Phase.RECORDING; }
        boolean dropped() { return phase == Phase.DROPPED; }
        /** All text settled and translated. */
        boolean complete() { return phase == Phase.DONE && translations.size() == pieces.size(); }
        String committed() { return String.join(" ", pieces); }
        String translated() { return String.join(" ", translations); }
        /** The language it was said in, or the current guess; null if not known yet. */
        Lang said() { return lang != null ? lang : shownLang; }
    }

    interface Listener {
        /** A status line: a string resource and its format arguments. */
        void onStatus(int text, Object... args);

        /** Mic level 0..1 every 100 ms while listening; speaking: voice detected right now. */
        void onLevel(float level, boolean speaking);

        /** An utterance was created or changed (called on the worker or audio thread). */
        void onUtt(UttView u);
    }

    private final Listener listener;
    private volatile Lang langA, langB;
    private volatile boolean muted;

    Engine(Listener listener, Lang a, Lang b) {
        this.listener = listener;
        this.langA = a;
        this.langB = b;
        new Thread(this::workerLoop, "worker").start();
    }

    void setLangs(Lang a, Lang b) {
        langA = a;
        langB = b;
        enqueue(Job.prepare());
    }

    /**
     * Loads models on the worker thread. Null files are left as they are.
     * general: the translator is a general Gemma, not TranslateGemma.
     */
    void load(File whisper, File translator, boolean general) {
        enqueue(Job.load(whisper, translator, general));
    }

    private volatile boolean generalTranslator;
    private volatile boolean whisperReady, translatorReady;

    boolean isReady() {
        return whisperReady && translatorReady;
    }

    /** Nothing loading, running or waiting (tests start only then). */
    synchronized boolean isIdle() {
        return running == null && queue.isEmpty() && pendingPartial == null;
    }

    /** The phone is talking: the mic is ignored (auto mode). */
    void setMuted(boolean m) {
        muted = m;
    }

    private void emit(Utt u) {
        listener.onUtt(u.view());
    }

    // ---------------------------------------------------------------------------------------------
    // sessions

    /**
     * One listening run: an "auto" run (until stop()) or one turn (until endTurn()). All its
     * state lives here, so an old run can never touch a newer one.
     */
    static final class Session {
        final File wav;        // test input instead of the mic, or null
        final Lang turn;       // turn mode: everything is one utterance in this language
        volatile boolean active = true;      // still recording
        volatile boolean cancelled;          // thrown away: its unfinished utterance is dropped
        Thread thread;

        Session(File wav, Lang turn) {
            this.wav = wav;
            this.turn = turn;
        }
    }

    private Session session;
    private int nextId = 1;
    private Lang lastLang;           // language of the last utterance, the first guess for the next

    synchronized boolean isListening() {
        return session != null && session.active;
    }

    /** Auto mode: listen until stop(), cutting utterances at pauses. */
    void start() {
        start(null, null);
    }

    /** Turn mode: record one utterance in the given language until endTurn(). */
    Session startTurn(Lang lang) {
        return start(null, lang);
    }

    /** wav: for testing, plays a 16 kHz mono 16-bit WAV file in real time instead of the mic. */
    synchronized Session start(File wav, Lang turn) {
        // a turn still in its short tail after the button was released ends normally; anything
        // else that is recording is stopped
        if (session != null && session.turn != null) session.active = false;
        else stop();
        Session s = new Session(wav, turn);
        session = s;
        s.thread = new Thread(() -> audioLoop(s), "audio");
        s.thread.start();
        return s;
    }

    /** Ends a turn normally: the rest of its audio is transcribed and translated. */
    void endTurn(Session s) {
        s.active = false;
    }

    /**
     * Stops listening. The utterance being recorded is thrown away; utterances that were already
     * complete (e.g. a turn whose button was released) are still transcribed and translated.
     */
    synchronized void stop() {
        Session s = session;
        if (s == null) return;
        session = null;
        if (s.active) {
            s.active = false;
            s.cancelled = true;
            s.thread.interrupt();
        }
    }

    /** Stops listening and throws away all unfinished work, waiting or running (mode change). */
    synchronized void cancelAll() {
        stop();
        for (Job j : queue) if (j.u != null) discard(j.u);
        queue.removeIf(j -> j.u != null);
        if (pendingPartial != null) discard(pendingPartial.u);
        pendingPartial = null;
        if (running != null && running.u != null) discard(running.u);
    }

    private synchronized int nextId() {
        return nextId++;
    }

    /** Drops an utterance that will not be finished (too short, cancelled, failed). */
    private synchronized void discard(Utt u) {
        if (!u.drop()) return;
        if (running != null && running.u == u) abortRunning();
        emit(u);
    }

    // ---------------------------------------------------------------------------------------------
    // audio

    /** Reads one chunk from the test file at real-time speed; false at the end of the file. */
    private boolean readWav(java.io.InputStream in, float[] chunk) throws java.io.IOException, InterruptedException {
        byte[] b = new byte[chunk.length * 2];
        int got = 0, n;
        while (got < b.length && (n = in.read(b, got, b.length - got)) > 0) got += n;
        if (got < b.length) return false;
        for (int i = 0; i < chunk.length; i++) chunk[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
        Thread.sleep(chunk.length * 1000L / RATE);
        return true;
    }

    @SuppressLint("MissingPermission")
    private void audioLoop(Session s) {
        AudioRecord rec = null;
        java.io.InputStream wav = null;
        try {
            if (s.wav != null) {
                wav = new java.io.BufferedInputStream(new java.io.FileInputStream(s.wav));
                wav.skip(44);
            } else {
                int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, Math.max(minBuf, CHUNK * 4 * 4));
                rec.startRecording();
            }
        } catch (java.io.IOException e) {
            s.active = false;
            listener.onStatus(R.string.status_error, e.getMessage());
            return;
        }
        if (s.wav != null) listener.onStatus(R.string.status_testing, s.wav.getName());
        else if (s.turn != null) listener.onStatus(R.string.status_speaking_in, s.turn.label);
        else listener.onStatus(R.string.status_listening);

        ArrayDeque<float[]> preroll = new ArrayDeque<>();
        FloatBuf buf = null;           // current utterance
        Utt utt = null;
        int speechRun = 0, silenceRun = 0, chunks = 0;
        int speechEnd = 0;             // buffer length at the last chunk with speech
        long lastPartial = 0;
        float noise = 0.003f;
        long samplesSeen = 0;

        try {
            while (s.active) {
                float[] chunk = new float[CHUNK];
                if (wav != null) {
                    if (!readWav(wav, chunk)) {
                        // end of file: finish the utterance as if the speaker stopped
                        if (s.turn == null && buf != null && chunks >= 4) {
                            handOver(utt, buf.copy(buf.size), speechEnd);
                            utt = null;
                        }
                        s.active = false;
                        break;
                    }
                } else {
                    int got = 0;
                    while (got < CHUNK && s.active) {
                        int n = rec.read(chunk, got, CHUNK - got, AudioRecord.READ_BLOCKING);
                        if (n <= 0) break;
                        got += n;
                    }
                }
                if (s.cancelled) break;
                samplesSeen += CHUNK;
                double sum = 0;
                for (float v : chunk) sum += v * v;
                float rms = (float) Math.sqrt(sum / CHUNK);
                boolean speech = rms > Math.max(0.003f, noise * 3f);
                // ~-50 dB .. -10 dB mapped to 0..1 for the meter
                float level = (float) Math.max(0, Math.min(1, (20 * Math.log10(rms + 1e-6) + 50) / 40));
                listener.onLevel(muted && wav == null ? 0 : level, s.turn != null || buf != null || speech);

                if (s.turn != null) {             // turn mode: the button decides the end, keep everything
                    if (buf == null) {
                        buf = new FloatBuf();
                        utt = new Utt(nextId());
                        utt.setLang(s.turn, other(s.turn));
                        emit(utt);
                        lastPartial = System.currentTimeMillis();   // utterance start
                    }
                    buf.add(chunk);
                    silenceRun = speech ? 0 : silenceRun + 1;
                    if (speech) speechEnd = buf.size;
                    if (!s.active) break;
                    // same passes as in auto mode, so the pass running when the speaker stops
                    // can be the final one even if the button is released a bit later
                    if (speechEnd > 0 && (silenceRun == 0 || silenceRun == SILENCE_START_CHUNKS)
                            && System.currentTimeMillis() - lastPartial >= FIRST_PARTIAL_MS) {
                        offerPartial(utt, buf.copy(buf.size), speechEnd);
                    }
                    continue;
                }
                if (!s.active) break;

                if (muted && wav == null) {   // the phone is talking: ignore the mic
                    if (utt != null) discard(utt);
                    buf = null;
                    utt = null;
                    speechRun = 0;
                    preroll.clear();
                    continue;
                }

                if (buf == null && !speech) noise = noise * 0.95f + Math.min(rms, noise * 2f) * 0.05f;

                if (buf == null) {
                    preroll.addLast(chunk);
                    if (preroll.size() > PREROLL_CHUNKS) preroll.removeFirst();
                    speechRun = speech ? speechRun + 1 : 0;
                    if (speechRun >= START_CHUNKS) {
                        buf = new FloatBuf();
                        for (float[] p : preroll) buf.add(p);
                        preroll.clear();
                        utt = new Utt(nextId());
                        emit(utt);   // shows that someone is being listened to
                        Log.i(TAG, "utt " + utt.id + " start at " + fmt(samplesSeen) + " (rms " + rms + ", noise " + noise + ")");
                        chunks = 0;
                        silenceRun = 0;
                        speechEnd = buf.size;
                        lastPartial = System.currentTimeMillis();   // utterance start
                    }
                    continue;
                }

                buf.add(chunk);
                chunks++;
                silenceRun = speech ? 0 : silenceRun + 1;
                if (speech) speechEnd = buf.size;

                boolean end = silenceRun >= END_SILENCE_CHUNKS || chunks >= MAX_CHUNKS;
                if (end) {
                    // keep 300 ms after the speech, as the passes during the silence did
                    float[] pcm = buf.copy(Math.min(buf.size, speechEnd + SILENCE_START_CHUNKS * CHUNK));
                    Log.i(TAG, "utt " + utt.id + " end at " + fmt(samplesSeen) + ", " + fmt(pcm.length) + " long");
                    if (chunks - silenceRun >= 4) handOver(utt, pcm, speechEnd);
                    else discard(utt);
                    buf = null;
                    utt = null;
                    speechRun = 0;
                } else if (silenceRun == SILENCE_START_CHUNKS) {
                    // a silence starts: restart with all that was said; if the pause holds, this
                    // pass becomes the final one
                    offerPartial(utt, buf.copy(buf.size), speechEnd);
                } else if (silenceRun == 0 && System.currentTimeMillis() - lastPartial >= FIRST_PARTIAL_MS) {
                    offerPartial(utt, buf.copy(buf.size), speechEnd);
                }
            }
            // a turn ended normally (button released): transcribe what was said
            if (!s.cancelled && s.turn != null && buf != null) {
                if (buf.size >= RATE / 2) handOver(utt, buf.copy(buf.size), Math.max(speechEnd, 1));
                else discard(utt);
                utt = null;
            }
        } catch (InterruptedException | java.io.IOException e) {
            // stopped
        } finally {
            if (rec != null) {
                rec.stop();
                rec.release();
            }
            if (wav != null) try { wav.close(); } catch (java.io.IOException ignored) {}
            s.active = false;
            if (utt != null) discard(utt);   // stopped in the middle of it
            listener.onLevel(0, false);
            if (s.turn != null && !s.cancelled) listener.onStatus(R.string.status_translating);
            else if (s.cancelled) listener.onStatus(R.string.status_mic_off);
        }
    }

    /** The utterance's audio is complete: queue its final pass. */
    private void handOver(Utt u, float[] pcm, int speechEnd) {
        if (!u.stopRecording()) return;
        emit(u);
        enqueue(Job.fin(u, pcm, speechEnd));
    }

    private Lang other(Lang l) {
        Lang a = langA, b = langB;
        return l == a ? b : a;
    }

    private static String fmt(long samples) {
        return String.format(java.util.Locale.ROOT, "%.1fs", samples / (float) RATE);
    }

    private static final class FloatBuf {
        float[] data = new float[RATE * 4];
        int size;

        void add(float[] c) {
            if (size + c.length > data.length) data = Arrays.copyOf(data, data.length * 2);
            System.arraycopy(c, 0, data, size, c.length);
            size += c.length;
        }

        float[] copy(int n) {
            return Arrays.copyOf(data, n);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // worker

    private enum Kind { LOAD, PREPARE, PARTIAL, FINAL, MT }

    private static final class Job {
        final Kind kind;
        final Utt u;              // PARTIAL, FINAL, MT
        final float[] pcm;        // PARTIAL, FINAL: the utterance's audio so far
        final int speechEnd;      // FINAL: buffer length at the last speech
        final int index;          // MT: the piece to translate
        final File whisper, translator;   // LOAD
        final boolean general;            // LOAD

        private Job(Kind kind, Utt u, float[] pcm, int speechEnd, int index, File whisper, File translator, boolean general) {
            this.kind = kind;
            this.u = u;
            this.pcm = pcm;
            this.speechEnd = speechEnd;
            this.index = index;
            this.whisper = whisper;
            this.translator = translator;
            this.general = general;
        }

        static Job load(File w, File t, boolean general) { return new Job(Kind.LOAD, null, null, 0, 0, w, t, general); }
        static Job prepare() { return new Job(Kind.PREPARE, null, null, 0, 0, null, null, false); }
        static Job partial(Utt u, float[] pcm) { return new Job(Kind.PARTIAL, u, pcm, 0, 0, null, null, false); }
        static Job fin(Utt u, float[] pcm, int speechEnd) { return new Job(Kind.FINAL, u, pcm, speechEnd, 0, null, null, false); }
        static Job mt(Utt u, int index) { return new Job(Kind.MT, u, null, 0, index, null, null, false); }
    }

    // waiting jobs, and at most one waiting pass (newer audio replaces it)
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private Job pendingPartial;
    private Job running;              // the job the worker runs now
    private boolean aborted;          // the running job was told to stop
    private boolean lastWasPass;

    private synchronized void enqueue(Job job) {
        queue.addLast(job);
        if (job.kind == Kind.FINAL && running != null && running.kind == Kind.PARTIAL) {
            // a running pass that already heard all the speech is the final one in the making:
            // keep it. Also one over long unsettled audio: it settles sentences the voice can start
            // with, and the final pass then only needs the rest.
            boolean keep = running.u == job.u && (running.pcm.length >= job.speechEnd
                    || running.pcm.length - job.u.committedSamples > LONG_UNSETTLED_SAMPLES);
            if (!keep) abortRunning();
        }
        // reading the translator's instructions must not delay real work
        if (job.kind != Kind.PREPARE && running != null && running.kind == Kind.PREPARE) abortRunning();
        notifyAll();
    }

    /**
     * Replaces any waiting pass with the newer audio, and restarts a running pass of the same
     * utterance that has not heard all speech yet. During speech no pass needs to finish: what
     * matters is that when the speaker stops, the running pass already has all the audio and
     * becomes the final one. Exception: once the unsettled audio is long, a pass is left to finish,
     * so it can settle sentences and the final pass does not get a very long audio.
     */
    private synchronized void offerPartial(Utt u, float[] pcm, int speechEnd) {
        pendingPartial = Job.partial(u, pcm);
        if (running != null && running.kind == Kind.PARTIAL && running.u == u) {
            boolean longUnsettled = running.pcm.length - u.committedSamples > LONG_UNSETTLED_SAMPLES;
            if (running.pcm.length < speechEnd && !longUnsettled) abortRunning();
        }
        // a translation is not stopped: it is what the voice will say first
        if (running != null && running.kind == Kind.PREPARE) abortRunning();
        notifyAll();
    }

    /**
     * Translations of utterances whose speaker stopped first: the voice needs them now, and the
     * final pass for the rest runs while it reads them. Then finals (often free, reusing the last
     * pass) and model loading. Then passes and translations take turns: while someone talks, the
     * sentences settled so far get translated.
     */
    private Job nextJob() {
        for (Job j : queue) {
            if (j.kind != Kind.MT || j.u.phase() == Phase.RECORDING) continue;
            queue.remove(j);
            return j;
        }
        for (Job j : queue) {
            if (j.kind != Kind.FINAL && j.kind != Kind.LOAD) continue;
            queue.remove(j);
            if (j.kind == Kind.FINAL && pendingPartial != null && pendingPartial.u == j.u) pendingPartial = null;
            return j;
        }
        if (pendingPartial != null && (!lastWasPass || queue.isEmpty())) {
            Job j = pendingPartial;
            pendingPartial = null;
            lastWasPass = true;
            return j;
        }
        lastWasPass = false;
        return queue.removeFirst();
    }

    private void abortRunning() {
        aborted = true;
        Native.setAbort(true);
    }

    private synchronized boolean wasAborted() {
        return aborted;
    }

    private void workerLoop() {
        while (true) {
            Job job;
            synchronized (this) {
                while (queue.isEmpty() && pendingPartial == null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                job = nextJob();
                running = job;
                aborted = false;
                Native.setAbort(false);
            }
            try {
                run(job);
            } catch (Throwable t) {
                Log.e(TAG, "job failed", t);
                listener.onStatus(R.string.status_error, String.valueOf(t.getMessage()));
                if (job.u != null) discard(job.u);   // never leave an utterance waiting forever
            }
            synchronized (this) {
                running = null;
            }
        }
    }

    private void run(Job job) {
        switch (job.kind) {
            case LOAD:
                if (job.whisper != null) {
                    listener.onStatus(R.string.status_loading_whisper);
                    whisperReady = Native.loadWhisper(Native.MAIN, job.whisper.getAbsolutePath());
                }
                if (job.translator != null) {
                    listener.onStatus(R.string.status_loading_translator);
                    generalTranslator = job.general;
                    translatorReady = Native.loadTranslator(job.translator.getAbsolutePath());
                }
                if (translatorReady) enqueue(Job.prepare());   // in the background
                if (isReady()) listener.onStatus(isListening() ? R.string.status_listening : R.string.status_ready);
                else listener.onStatus(R.string.status_missing_models);
                break;
            case PREPARE:
                if (!translatorReady) break;
                prepare();
                if (wasAborted()) synchronized (this) {   // stopped for real work: again later, once
                    boolean queued = false;
                    for (Job j : queue) queued |= j.kind == Kind.PREPARE;
                    if (!queued) queue.addLast(Job.prepare());
                }
                break;
            case PARTIAL:
                if (whisperReady && !job.u.dropped()) pass(job.u, job.pcm, false);
                break;
            case FINAL: {
                Utt u = job.u;
                if (u.dropped()) break;
                if (!whisperReady) {
                    discard(u);
                } else if (u.lang() != null && u.passCovered >= job.speechEnd) {
                    // the last pass already heard all speech: its text is the final one
                    Log.i(TAG, "final from the last pass (covered " + fmt(u.passCovered) + " of " + fmt(job.speechEnd) + " speech)");
                    for (String piece : u.prevTail) commit(u, piece);
                    u.prevTail = new ArrayList<>();
                    finish(u);
                } else {
                    pass(u, job.pcm, true);
                }
                break;
            }
            case MT:
                translatePiece(job);
                break;
        }
    }

    private static final class Seg {
        final int endMs;
        final String text;

        Seg(int endMs, String text) {
            this.endMs = endMs;
            this.text = text;
        }
    }

    /** Transcribes and cuts the words into sentences (Seg = one sentence with its end time). */
    private List<Seg> transcribe(Utt u, float[] pcm, Lang lang, String prompt) {
        String out = new String(Native.transcribe(Native.MAIN, pcm, lang.code, prompt, false), StandardCharsets.UTF_8);
        List<Seg> segs = new ArrayList<>();
        String last = "";
        StringBuilder sentence = new StringBuilder();
        int words = 0;
        String[] lines = out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            int tab = lines[i].indexOf('\t');
            if (tab < 0) continue;
            String word = lines[i].substring(tab + 1);
            if (word.trim().isEmpty()) continue;
            sentence.append(word);
            words++;
            String w = word.trim();
            boolean end = w.matches(".*[.?!…]$") || (words >= 12 && w.endsWith(",")) || i == lines.length - 1;
            if (!end) continue;
            String text = dedupe(sentence.toString().trim());
            sentence.setLength(0);
            words = 0;
            if (isJunk(text) || norm(text).equals(last)) {   // noise, or whisper repeating itself
                Log.i(TAG, "dropped junk: " + text);
                continue;
            }
            last = norm(text);
            segs.add(new Seg(Integer.parseInt(lines[i].substring(0, tab)), text));
        }
        // Whisper invents repetitions, mostly of its context or at the padded end of the audio.
        // Drop a sentence already said in this utterance, and one "spoken" impossibly fast.
        java.util.Set<String> said = new java.util.HashSet<>();
        List<Seg> kept = new ArrayList<>();
        int prevEnd = 0;
        for (Seg g : segs) {
            int ms = Math.max(1, g.endMs - prevEnd);
            prevEnd = g.endMs;
            String n = norm(g.text);
            if (u.isPiece(n) || !said.add(n)) {
                Log.i(TAG, "dropped repeat: " + g.text);
                continue;
            }
            if (g.text.length() > 12 && g.text.length() * 1000 / ms > MAX_CHARS_PER_SECOND) {
                Log.i(TAG, "dropped too fast (" + g.text.length() + " chars in " + ms + "ms): " + g.text);
                continue;
            }
            kept.add(g);
        }
        return kept;
    }

    private static String lastWords(String t) {
        return t.length() > 200 ? t.substring(t.length() - 200) : t;
    }

    private static String join(List<Seg> segs, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < segs.size(); i++) b.append(i > from ? " " : "").append(segs.get(i).text);
        return b.toString();
    }

    private static String joinMarked(List<Seg> segs) {
        StringBuilder b = new StringBuilder();
        for (Seg g : segs) b.append(b.length() > 0 ? " | " : "").append(g.endMs).append("ms ").append(g.text);
        return b.toString();
    }

    private static String norm(String t) {
        return t.replaceAll("[\\p{P}\\s]+", " ").trim().toLowerCase();
    }

    /** One transcription pass over the unsettled audio of an utterance. */
    private void pass(Utt u, float[] pcm, boolean isFinal) {
        int start = Math.min(u.committedSamples, pcm.length);
        if (pcm.length - start < RATE / 2) {      // nothing new worth transcribing
            if (isFinal) finish(u);
            return;
        }
        float[] audio = Arrays.copyOfRange(pcm, start, pcm.length);
        String prompt = lastWords(u.committed());
        Lang a = langA, b = langB;
        Lang lang = u.lang();
        // Transcribe in the likely language first, then check the language on the same audio
        // (accurate right after a transcription) and redo it if it was wrong.
        // Checked once, on the first pass with at least 1 s of audio.
        Lang guess = lang != null ? lang : (lastLang == a || lastLang == b) ? lastLang : a;
        long t0 = System.currentTimeMillis();
        List<Seg> segs = transcribe(u, audio, guess, prompt);
        if (wasAborted()) {
            Log.i(TAG, "pass over " + fmt(audio.length) + " stopped after " + (System.currentTimeMillis() - t0) + "ms");
            return;
        }
        if (lang == null && (isFinal || pcm.length >= RATE) && !segs.isEmpty()) {
            Lang detected = Lang.byCode(Native.detectLanguage(Native.MAIN, a.code, b.code), guess);
            if (detected != guess) {
                segs = transcribe(u, audio, detected, prompt);
                if (wasAborted()) return;
            }
            lang = detected;
            u.setLang(detected, detected == a ? b : a);   // the target is fixed from now on
            lastLang = detected;
        }
        if (isFinal && lang != null) debugDump(u, lang, pcm);
        Log.i(TAG, (isFinal ? "final " : "partial ") + fmt(audio.length) + " in " + (System.currentTimeMillis() - t0)
                + "ms [" + (lang == null ? "?" : lang.code) + "]: " + joinMarked(segs));

        if (lang == null) {          // too short to know the language: show a guess, settle nothing
            if (isFinal) {
                finish(u);
            } else if (!segs.isEmpty()) {
                u.setGuess(guess, join(segs, 0));
                emit(u);
            }
            return;
        }

        int commit;
        if (isFinal) {
            commit = segs.size();
        } else {
            // Settle leading segments (never the last one: the speaker may still be in it) that
            // either the previous pass also produced, or that ended a while before the audio does:
            // the speaker has moved on, so they will not change any more.
            int audioMs = audio.length / (RATE / 1000);
            commit = 0;
            while (commit < segs.size() - 1
                    && ((commit < u.prevTail.size() && norm(segs.get(commit).text).equals(norm(u.prevTail.get(commit))))
                        || segs.get(commit).endMs < audioMs - SETTLED_AFTER_MS)) commit++;
        }
        for (int i = 0; i < commit; i++) commit(u, segs.get(i).text);
        if (commit > 0) {
            u.committedSamples = isFinal ? pcm.length : start + segs.get(commit - 1).endMs * (RATE / 1000);
        }
        u.prevTail = new ArrayList<>();
        for (int i = commit; i < segs.size(); i++) u.prevTail.add(segs.get(i).text);
        u.setTail(join(segs, commit));
        u.passCovered = pcm.length;
        if (isFinal) finish(u);
        else emit(u);
    }

    /** Settles a sentence and queues its translation. */
    private void commit(Utt u, String piece) {
        int index = u.addPiece(piece);
        enqueue(Job.mt(u, index));
    }

    private void finish(Utt u) {
        u.finish();
        emit(u);
    }

    /** Keeps the audio of the last 30 utterances in files/debug, to replay what went wrong. */
    private File debugDir;

    void setDebugDir(File dir) {
        debugDir = dir;
    }

    private void debugDump(Utt u, Lang lang, float[] pcm) {
        if (debugDir == null) return;
        try {
            debugDir.mkdirs();
            File f = new File(debugDir, String.format(java.util.Locale.ROOT, "utt-%04d-%s.wav", u.id % 10000, lang.code));
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
                int bytes = pcm.length * 2;
                out.writeBytes("RIFF");
                out.writeInt(Integer.reverseBytes(36 + bytes));
                out.writeBytes("WAVEfmt ");
                out.writeInt(Integer.reverseBytes(16));
                out.writeShort(Short.reverseBytes((short) 1));
                out.writeShort(Short.reverseBytes((short) 1));
                out.writeInt(Integer.reverseBytes(RATE));
                out.writeInt(Integer.reverseBytes(RATE * 2));
                out.writeShort(Short.reverseBytes((short) 2));
                out.writeShort(Short.reverseBytes((short) 16));
                out.writeBytes("data");
                out.writeInt(Integer.reverseBytes(bytes));
                for (float v : pcm) out.writeShort(Short.reverseBytes((short) Math.max(-32768, Math.min(32767, Math.round(v * 32767)))));
            }
            File[] all = debugDir.listFiles();
            if (all != null && all.length > 30) {
                Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                for (int i = 0; i < all.length - 30; i++) all[i].delete();
            }
        } catch (java.io.IOException e) {
            Log.w(TAG, "debug dump failed", e);
        }
    }

    /** Reads the translator's fixed instructions for both directions now, not on the first phrase. */
    private void prepare() {
        Lang a = langA, b = langB;
        Native.prepareTranslation(b.englishName, b.code, a.englishName, a.targetCode(), generalTranslator);
        Native.prepareTranslation(a.englishName, a.code, b.englishName, b.targetCode(), generalTranslator);
    }

    private void translatePiece(Job job) {
        Utt u = job.u;
        if (u.dropped()) return;
        Lang from = u.lang(), to = u.target();
        if (!translatorReady) {
            u.setTranslation(job.index, "");   // nothing to say, but the utterance is not stuck
            emit(u);
            return;
        }
        final boolean voseo = to == Lang.ES;
        long t0 = System.currentTimeMillis();
        byte[] out = Native.translate(from.englishName, from.code, to.englishName, to.targetCode(),
                u.piece(job.index), generalTranslator, utf8 -> {
                    String s = new String(utf8, StandardCharsets.UTF_8).trim();
                    u.setTranslating(voseo ? Voseo.apply(s) : s);
                    emit(u);
                });
        if (wasAborted()) {
            u.setTranslating("");
            emit(u);
            synchronized (this) {
                if (!u.dropped()) queue.addFirst(job);   // runs again, before anything else
            }
            return;
        }
        String s = new String(out, StandardCharsets.UTF_8).trim();
        Log.i(TAG, "translation in " + (System.currentTimeMillis() - t0) + "ms: " + s);
        u.setTranslation(job.index, voseo ? Voseo.apply(s) : s);
        emit(u);
    }

    private static final java.util.regex.Pattern REPEAT = java.util.regex.Pattern.compile("(.{8,}?)(?:\\s*\\1)+");

    /** Whisper sometimes repeats a sentence, mostly on short audio: keep one copy. */
    static String dedupe(String t) {
        return REPEAT.matcher(t).replaceAll("$1");
    }

    /** Whisper invents these on silence or noise. */
    private static boolean isJunk(String t) {
        String s = t.replaceAll("[\\p{P}\\s]", "").toLowerCase();
        if (s.isEmpty()) return true;
        String l = t.toLowerCase();
        String[] words = l.split("[\\p{P}\\s]+");
        if (words.length >= 6 && new java.util.HashSet<>(Arrays.asList(words)).size() * 3 < words.length) return true;
        return l.contains("untertitel") || l.contains("amara.org") || l.contains("zdf") || l.contains("subtítulos")
                || l.contains("vielen dank fürs zuschauen") || l.contains("gracias por ver")
                || l.contains("thanks for watching") || l.contains("suscríbete") || l.contains("abonniert")
                || s.equals("musik") || s.equals("música");
    }
}
