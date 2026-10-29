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
import java.util.List;

/**
 * Listens to the mic, cuts speech into utterances (at pauses, or by the talk button in turn mode),
 * and runs them through whisper and the translator on one worker thread.
 *
 * While someone talks, the not yet settled audio is transcribed again and again; a running pass
 * is restarted whenever more speech comes. So when the speaker stops, the running pass already
 * has all the audio, and it becomes the final one without waiting for the pause to be confirmed.
 * A sentence (whisper segment) is settled when two passes in a row agree on it, or when it ended
 * well before the audio heard so far: its audio is not transcribed again, and it goes to the
 * translator right away. At the end of the utterance the rest is settled, and the whole
 * translation is spoken.
 */
final class Engine {
    private static final String TAG = "traductor";
    private static final int RATE = 16000;
    private static final int CHUNK = RATE / 10;              // 100 ms
    private static final int START_CHUNKS = 2;               // 200 ms of speech starts an utterance
    private static final int PREROLL_CHUNKS = 3;             // keep 300 ms before the start
    private static final int END_SILENCE_CHUNKS = 8;         // 800 ms of silence ends it
    private static final int MAX_CHUNKS = 200;               // cut very long talk every 20 s
    private static final long FIRST_PARTIAL_MS = 1000;
    private static final int LONG_UNSETTLED_SAMPLES = RATE * 8;
    private static final int SILENCE_START_CHUNKS = 3;    // 300 ms quiet: restart with all speech, may become the final
    private static final int SETTLED_AFTER_MS = 1500;
    private static final int MAX_CHARS_PER_SECOND = 30;   // fast speech is ~15-20

    static final class Utt {
        final int id;
        Lang lang;                 // null until detected
        Lang shownLang;            // a guess, to show early text before detection
        // original text: settled part + the part that may still change
        volatile String committed = "";
        volatile String tail = "";
        int committedSamples;      // audio before this is settled
        int passCovered;           // buffer length the last finished pass transcribed
        List<String> prevTail = new ArrayList<>();   // last pass's unsettled segments
        // translation: one per settled piece, plus the one being generated now
        // read by the UI thread while the worker adds to them
        final List<String> pieces = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> translations = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile String translating = "";
        volatile boolean recording = true;   // audio still coming in
        volatile boolean ended, spoken, dropped;
        volatile boolean cancelled;   // thrown away: its work is skipped

        final Session session;

        Utt(int id, Session session) {
            this.id = id;
            this.session = session;
        }

        String translationDone() {
            return String.join(" ", translations);
        }
    }

    interface Listener {
        void onStatus(String status);

        /** Mic level 0..1 every 100 ms while listening; speaking: voice detected right now. */
        void onLevel(float level, boolean speaking);

        void onUtt(Utt u);           // created or changed (called on the worker/audio thread)

        void onTranslated(Utt u);    // final translation ready, to be spoken
    }

    private final Listener listener;
    private volatile Lang langA, langB;
    private volatile boolean muted;
    private final Thread workerThread;

    // worker queue: finals first (in order), then at most one pending partial
    private final ArrayDeque<Object[]> finals = new ArrayDeque<>();
    private Object[] pendingPartial;
    private boolean busy;
    private String stage = "";      // what the worker is doing: "partial-asr", "partial-mt", "final", "load"
    private boolean aborted;
    private Utt running;            // utterance of the job the worker runs now (null for model jobs)
    private int runningCovers;
    private Object[] runningJob;      // a running pass: how much of the utterance buffer it transcribes

    private volatile boolean whisperReady, translatorReady;
    private Lang lastLang;           // language of the last utterance, the first guess for the next
    private int nextId = 1;

    Engine(Listener listener, Lang a, Lang b) {
        this.listener = listener;
        this.langA = a;
        this.langB = b;
        workerThread = new Thread(this::workerLoop, "worker");
        workerThread.start();
    }

    void setLangs(Lang a, Lang b) {
        langA = a;
        langB = b;
        enqueueFinal(new Object[]{"prepare"});
    }

    /**
     * Loads models on the worker thread. Null files are left as they are.
     * generalTranslator: the translator is a general Gemma, not TranslateGemma.
     */
    void load(File whisper, File live, File translator, boolean generalTranslator) {
        enqueueFinal(new Object[]{"load", whisper, live, translator, generalTranslator});
    }

    private volatile boolean generalTranslator;

    /** Unloads the live model (none selected). */
    void unloadLive() {
        enqueueFinal(new Object[]{"unload-live"});
    }

    boolean isReady() {
        return whisperReady && translatorReady;
    }

    /** Nothing loading, running or waiting (tests start only then). */
    synchronized boolean isIdle() {
        return !busy && finals.isEmpty() && pendingPartial == null;
    }

    void setMuted(boolean m) {
        muted = m;
    }

    /**
     * One listening run: an "auto" run (until stop()) or one turn (until endTurn()). All its
     * state lives here, so an old run can never touch a newer one.
     */
    final class Session {
        final File wav;        // test input instead of the mic, or null
        final Lang turn;       // turn mode: everything is one utterance in this language
        volatile boolean active = true;      // still recording
        volatile boolean cancelled;          // thrown away: its work is dropped
        Thread thread;

        Session(File wav, Lang turn) {
            this.wav = wav;
            this.turn = turn;
        }
    }

    private Session session;

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
        stop();
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
            s.cancelled = true;   // the audio loop discards its unfinished utterance
            s.thread.interrupt();
        }
    }

    /** Stops listening and throws away all unfinished work, waiting or running (mode change). */
    synchronized void cancelAll() {
        stop();
        for (Object[] job : finals) if (job.length > 1 && job[1] instanceof Utt) discard((Utt) job[1]);
        finals.removeIf(job -> job.length > 1 && job[1] instanceof Utt);
        if (pendingPartial != null) discard((Utt) pendingPartial[1]);
        pendingPartial = null;
        if (running != null) discard(running);
    }

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

    // ---------------------------------------------------------------------------------------------
    // audio

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
            listener.onStatus("Error: " + e.getMessage());
            return;
        }
        listener.onStatus(s.wav != null ? "Probando con " + s.wav.getName() + "…"
                : s.turn != null ? "Hablando en " + s.turn.label + "…" : "Escuchando…");

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
                        utt = new Utt(nextId(), s);
                        utt.lang = s.turn;
                        listener.onUtt(utt);
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
                        utt = new Utt(nextId(), s);
                        listener.onUtt(utt);   // shows that someone is being listened to
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
            if (s.turn != null && !s.cancelled) listener.onStatus("Traduciendo…");
            else if (s.cancelled) listener.onStatus("Micrófono apagado");
        }
    }

    /** The utterance's audio is complete: queue its final pass. */
    private void handOver(Utt u, float[] pcm, int speechEnd) {
        u.recording = false;
        listener.onUtt(u);
        enqueueFinal(new Object[]{"final", u, pcm, speechEnd});
    }

    private synchronized int nextId() {
        return nextId++;
    }

    /** Removes an utterance that will not be finished (too short, cancelled, phone talking). */
    private synchronized void discard(Utt u) {
        if (u.cancelled) return;
        u.cancelled = true;
        u.recording = false;
        u.ended = true;
        u.dropped = true;
        if (running == u) abortRunning();
        listener.onUtt(u);
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

    private synchronized void enqueueFinal(Object[] job) {
        finals.addLast(job);
        // a running pass that already heard all the speech is the final one in the making: keep it
        boolean keep = job[0].equals("final") && stage.equals("partial-asr") && running == job[1]
                && runningCovers >= (Integer) job[3];
        if ((stage.startsWith("partial") && !keep) || (stage.equals("prepare") && !job[0].equals("prepare"))) {
            abortRunning();
            if (stage.equals("prepare")) requeuePrepare();
        }
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
        pendingPartial = new Object[]{"partial", u, pcm};
        boolean longUnsettled = runningCovers - u.committedSamples > LONG_UNSETTLED_SAMPLES;
        if (stage.equals("partial-asr") && running == u && runningCovers < speechEnd && !longUnsettled) abortRunning();
        // reading the translator's instructions, or translating, must not delay the voice:
        // stop it, it runs again later (a translation from the start)
        if (stage.equals("prepare")) {
            abortRunning();
            requeuePrepare();
        } else if (stage.equals("mt")) {
            abortRunning();
            finals.addFirst(runningJob);
        }
        notifyAll();
    }

    /**
     * The voice goes first: finals (often free, reusing the last pass) and model loading, then a
     * waiting pass, and only then translations and reading the translator's instructions.
     */
    private Object[] nextJob() {
        for (Object[] j : finals) {
            if (j[0].equals("mt") || j[0].equals("prepare")) continue;
            finals.remove(j);
            if (j[0].equals("final") && pendingPartial != null && pendingPartial[1] == j[1]) pendingPartial = null;
            return j;
        }
        if (pendingPartial != null) {
            Object[] j = pendingPartial;
            pendingPartial = null;
            return j;
        }
        return finals.removeFirst();
    }

    /** Puts an aborted "prepare" back at the end of the queue, once. */
    private void requeuePrepare() {
        for (Object[] j : finals) if (j[0].equals("prepare")) return;
        finals.addLast(new Object[]{"prepare"});
    }

    private void abortRunning() {
        aborted = true;
        Native.setAbort(true);
    }

    private synchronized void setStage(String s) {
        stage = s;
    }

    private synchronized boolean wasAborted() {
        return aborted;
    }

    private void workerLoop() {
        while (true) {
            Object[] job;
            synchronized (this) {
                while (finals.isEmpty() && pendingPartial == null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                job = nextJob();
                busy = true;
                aborted = false;
                running = job.length > 1 && job[1] instanceof Utt ? (Utt) job[1] : null;
                runningJob = job;
                stage = job[0].equals("partial") ? "partial-asr" : (String) job[0];
                runningCovers = job[0].equals("partial") ? ((float[]) job[2]).length : 0;
                Native.setAbort(false);
            }
            try {
                run(job);
            } catch (Throwable t) {
                Log.e(TAG, "job failed", t);
                listener.onStatus("Error: " + t.getMessage());
            }
            synchronized (this) {
                busy = false;
                stage = "";
                running = null;
            }
        }
    }

    private void run(Object[] job) {
        switch ((String) job[0]) {
            case "load": {
                File w = (File) job[1], live = (File) job[2], t = (File) job[3];
                if (w != null) {
                    listener.onStatus("Cargando Whisper…");
                    whisperReady = Native.loadWhisper(Native.MAIN, w.getAbsolutePath());
                }
                if (t != null) {
                    listener.onStatus("Cargando traductor…");
                    generalTranslator = (Boolean) job[4];
                    translatorReady = Native.loadTranslator(t.getAbsolutePath());
                }
                if (translatorReady) enqueueFinal(new Object[]{"prepare"});   // in the background, see offerPartial
                listener.onStatus(isReady() ? (isListening() ? "Escuchando…" : "Listo. Tocá el micrófono.")
                        : "Faltan modelos: tocá ⚙");
                break;
            }
            case "unload-live":
                Native.loadWhisper(Native.LIVE, null);
                break;
            case "prepare":
                if (translatorReady) prepare();
                break;
            case "partial":
                if (whisperReady) pass((Utt) job[1], (float[]) job[2], false);
                break;
            case "final": {
                if (!whisperReady) return;
                Utt u = (Utt) job[1];
                float[] pcm = (float[]) job[2];
                int speechEnd = (Integer) job[3];
                if (u.lang != null && u.passCovered >= speechEnd && !u.cancelled) {
                    // the last pass already heard all speech: its text is the final one
                    Log.i(TAG, "final from the last pass (covered " + fmt(u.passCovered) + " of " + fmt(speechEnd) + " speech)");
                    settleRest(u);
                    finish(u);
                } else {
                    pass(u, pcm, true);
                }
                break;
            }
            case "mt":
                translatePiece((Utt) job[1], (Integer) job[2], (Lang) job[3]);
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
    private List<Seg> transcribe(int model, Utt u, float[] pcm, Lang lang, String prompt) {
        return transcribe(model, u, pcm, lang, prompt, false);
    }

    private List<Seg> transcribe(int model, Utt u, float[] pcm, Lang lang, String prompt, boolean fullCtx) {
        String out = new String(Native.transcribe(model, pcm, lang.code, prompt, fullCtx), StandardCharsets.UTF_8);
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
        for (String p : u.pieces) said.add(norm(p));
        List<Seg> kept = new ArrayList<>();
        int prevEnd = 0;
        for (Seg g : segs) {
            int ms = Math.max(1, g.endMs - prevEnd);
            prevEnd = g.endMs;
            if (!said.add(norm(g.text))) {
                Log.i(TAG, "dropped repeat: " + g.text);
                continue;
            }
            if (g.text.length() > 12 && g.text.length() * 1000 / ms > MAX_CHARS_PER_SECOND) {
                Log.i(TAG, "dropped too fast (" + g.text.length() + " chars in " + ms + "ms): " + g.text);
                continue;
            }
            kept.add(g);
        }
        segs = kept;
        return segs;
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
        if (u.ended || u.cancelled) return;
        int start = Math.min(u.committedSamples, pcm.length);
        if (pcm.length - start < RATE / 2) {      // nothing new worth transcribing
            if (isFinal) finish(u);
            return;
        }
        float[] audio = Arrays.copyOfRange(pcm, start, pcm.length);
        String prompt = lastWords(u.committed);
        Lang a = langA, b = langB;
        // Transcribe in the likely language first, then check the language on the same audio
        // (accurate right after a transcription) and redo it if it was wrong.
        // Checked once, on the first pass with at least 1 s of audio.
        Lang guess = u.lang != null ? u.lang : (lastLang == a || lastLang == b) ? lastLang : a;
        long t0 = System.currentTimeMillis();
        List<Seg> segs = transcribe(Native.MAIN, u, audio, guess, prompt);
        if (wasAborted()) {
            Log.i(TAG, "pass over " + fmt(audio.length) + " stopped after " + (System.currentTimeMillis() - t0) + "ms");
            return;
        }
        if (u.lang == null && (isFinal || pcm.length >= RATE) && !segs.isEmpty()) {
            Lang detected = Lang.byCode(Native.detectLanguage(Native.MAIN, a.code, b.code), guess);
            if (detected != guess) {
                segs = transcribe(Native.MAIN, u, audio, detected, prompt);
                if (wasAborted()) return;
            }
            u.lang = detected;
            lastLang = detected;
        }
        if (isFinal && u.lang != null) debugDump(u, pcm);
        Log.i(TAG, (isFinal ? "final " : "partial ") + fmt(audio.length) + " in " + (System.currentTimeMillis() - t0)
                + "ms [" + (u.lang == null ? "?" : u.lang.code) + "]: " + joinMarked(segs));

        if (u.lang == null) {          // too short to know the language: show a guess, settle nothing
            if (isFinal) {
                finish(u);
            } else if (!segs.isEmpty()) {
                u.shownLang = guess;
                u.tail = join(segs, 0);
                listener.onUtt(u);
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
        Lang target = u.lang == a ? b : a;
        for (int i = 0; i < commit; i++) {
            String piece = segs.get(i).text;
            u.committed = (u.committed + " " + piece).trim();
            u.pieces.add(piece);
            enqueueFinal(new Object[]{"mt", u, u.pieces.size() - 1, target});
        }
        if (commit > 0) {
            u.committedSamples = isFinal ? pcm.length : start + segs.get(commit - 1).endMs * (RATE / 1000);
        }
        u.prevTail = new ArrayList<>();
        for (int i = commit; i < segs.size(); i++) u.prevTail.add(segs.get(i).text);
        u.tail = join(segs, commit);
        u.passCovered = pcm.length;
        if (isFinal) finish(u);
        else listener.onUtt(u);
    }

    /** Keeps the audio of the last 30 utterances in files/debug, to replay what went wrong. */
    private File debugDir;

    void setDebugDir(File dir) {
        debugDir = dir;
    }

    private void debugDump(Utt u, float[] pcm) {
        if (debugDir == null) return;
        try {
            debugDir.mkdirs();
            File f = new File(debugDir, String.format(java.util.Locale.ROOT, "utt-%04d-%s.wav", u.id % 10000, u.lang.code));
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

    /** Settles the unsettled sentences of the last pass, as a final pass would. */
    private void settleRest(Utt u) {
        Lang target = u.lang == langA ? langB : langA;
        for (String piece : u.prevTail) {
            u.committed = (u.committed + " " + piece).trim();
            u.pieces.add(piece);
            enqueueFinal(new Object[]{"mt", u, u.pieces.size() - 1, target});
        }
        u.prevTail = new ArrayList<>();
    }

    /** The utterance is over: drop it if nothing was said, else speak once all is translated. */
    private void finish(Utt u) {
        u.ended = true;
        u.tail = "";
        if (u.pieces.isEmpty()) {
            u.dropped = true;
            listener.onUtt(u);   // lets the UI remove it
            return;
        }
        listener.onUtt(u);
        maybeSpeak(u);
    }

    private void maybeSpeak(Utt u) {
        if (!u.ended || u.spoken || u.translations.size() < u.pieces.size()) return;
        u.spoken = true;
        listener.onTranslated(u);
        if (!isListening()) listener.onStatus("Listo");
    }

    /** Reads the translator's fixed instructions for both directions now, not on the first phrase. */
    private void prepare() {
        Lang a = langA, b = langB;
        Native.prepareTranslation(b.englishName, b.code, a.englishName, a.targetCode(), generalTranslator);
        Native.prepareTranslation(a.englishName, a.code, b.englishName, b.targetCode(), generalTranslator);
    }

    private void translatePiece(Utt u, int index, Lang target) {
        if (!translatorReady || u.cancelled) return;
        final boolean voseo = target == Lang.ES;
        long t0 = System.currentTimeMillis();
        byte[] out = Native.translate(u.lang.englishName, u.lang.code, target.englishName, target.targetCode(),
                u.pieces.get(index), generalTranslator, utf8 -> {
                    String s = new String(utf8, StandardCharsets.UTF_8).trim();
                    u.translating = voseo ? Voseo.apply(s) : s;
                    listener.onUtt(u);
                });
        if (wasAborted()) {
            u.translating = "";   // runs again later
            listener.onUtt(u);
            return;
        }
        String s = new String(out, StandardCharsets.UTF_8).trim();
        Log.i(TAG, "translation in " + (System.currentTimeMillis() - t0) + "ms: " + s);
        u.translations.add(voseo ? Voseo.apply(s) : s);
        u.translating = "";
        listener.onUtt(u);
        maybeSpeak(u);
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
                || l.contains("thanks for watching") || s.equals("musik") || s.equals("música");
    }
}
