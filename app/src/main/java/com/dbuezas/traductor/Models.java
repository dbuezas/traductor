package com.dbuezas.traductor;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/** Model catalog, download/delete, and which model is selected. */
final class Models {
    enum Kind { WHISPER, TRANSLATOR }

    static final class Model {
        final String id;
        final Kind kind;
        final String label;
        final int note;          // string resource
        final String url;
        final long size;
        final boolean general;   // translator: a general chat model, not TranslateGemma (other prompt)
        final int precision, speed;   // 1..5

        Model(String id, Kind kind, String label, int note, String url, long size, boolean general,
              int precision, int speed) {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.note = note;
            this.url = url;
            this.size = size;
            this.general = general;
            this.precision = precision;
            this.speed = speed;
        }
    }

    private static final String WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    /**
     * Best to worst within each kind. Precision and speed are 1..5, my estimate from tests on a
     * Pixel 7 and how the sizes and quantizations usually compare; not an exact benchmark.
     */
    static final Model[] ALL = {
        whisper("large-v3-turbo", 874188075L, 5, 3, R.string.note_whisper_turbo),
        whisper("medium", 823369779L, 4, 2, R.string.note_whisper_medium),
        whisper("small", 264464607L, 3, 4, R.string.note_whisper_small),
        whisper("base", 81768585L, 2, 5, R.string.note_whisper_base),
        tg("Q8_0", 4130417472L, 5, 1, R.string.note_tg_q8),
        tg("Q6_K", 3190755392L, 5, 2, R.string.note_tg_q6),
        tg("Q5_K_M", 2829713472L, 4, 3, R.string.note_tg_faithful),
        tg("Q5_K_S", 2764607552L, 4, 3, R.string.note_tg_faithful),
        tg("Q4_K_M", 2489909312L, 4, 3, R.string.note_tg_faithful),
        tg("Q4_K_S", 2377945152L, 3, 3, R.string.note_tg_faithful),
        tg("Q3_K_L", 2236100672L, 3, 4, R.string.note_tg_q3),
        gemma("4b", "Q8_0", 4130226336L, 3, 1, R.string.note_gemma4_q8),
        gemma("4b", "Q4_K_M", 2489757856L, 3, 3, R.string.note_gemma4),
        gemma("1b", "Q8_0", 1069306368L, 1, 5, R.string.note_gemma1),
        gemma("1b", "Q4_K_M", 806058240L, 1, 5, R.string.note_gemma1),
    };

    /** Whisper, q8 (q5 is much slower on phone CPUs). */
    private static Model whisper(String name, long size, int precision, int speed, int note) {
        String file = "ggml-" + name + "-q8_0.bin";
        return new Model(file, Kind.WHISPER, "Whisper " + name + " (q8)", note, WHISPER + file, size, false, precision, speed);
    }

    /** TranslateGemma 4B in one quantization. */
    private static Model tg(String quant, long size, int precision, int speed, int note) {
        String file = "translategemma-4b-it-" + quant + ".gguf";
        return new Model(file, Kind.TRANSLATOR, "TranslateGemma 4B (" + quant + ")", note,
                "https://huggingface.co/bullerwins/translategemma-4b-it-GGUF/resolve/main/" + file, size, false, precision, speed);
    }

    /** General Gemma 3 (not made for translation), asked to translate with an instruction. */
    private static Model gemma(String params, String quant, long size, int precision, int speed, int note) {
        String file = "gemma-3-" + params + "-it-" + quant + ".gguf";
        return new Model(file, Kind.TRANSLATOR, "Gemma 3 " + params.toUpperCase() + " (" + quant + ")", note,
                "https://huggingface.co/ggml-org/gemma-3-" + params + "-it-GGUF/resolve/main/" + file, size, true, precision, speed);
    }

    /** The selected model of a kind, if downloaded (null otherwise). */
    static Model selectedModel(Context c, Kind k) {
        String id = selectedId(c, k);
        for (Model m : ALL) if (m.id.equals(id) && isDownloaded(c, m)) return m;
        return null;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "models");
        d.mkdirs();
        return d;
    }

    static File file(Context c, Model m) {
        return new File(dir(c), m.id);
    }

    static boolean isDownloaded(Context c, Model m) {
        return file(c, m).length() == m.size;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("traductor", Context.MODE_PRIVATE);
    }

    static String selectedId(Context c, Kind k) {
        return prefs(c).getString("model_" + k, null);
    }

    static void select(Context c, Model m) {
        prefs(c).edit().putString("model_" + m.kind, m.id).apply();
    }

    /** The selected model of a kind, if it is downloaded. */
    static File selectedFile(Context c, Kind k) {
        String id = selectedId(c, k);
        for (Model m : ALL) if (m.id.equals(id) && isDownloaded(c, m)) return file(c, m);
        return null;
    }

    // ---- downloads (they keep running when the screen closes) ----

    interface Progress {
        /** done: the download ended (finished, failed or cancelled); else more bytes arrived. */
        void update(Model m, boolean done);
    }

    static final Map<String, Long> progress = new HashMap<>();   // bytes so far, only while downloading
    static final Map<String, String> errors = new HashMap<>();
    private static final Map<String, Thread> running = new HashMap<>();
    static Progress listener;          // the settings screen
    static Progress serviceListener;   // DownloadService's notification

    /** Downloads running now. */
    static synchronized java.util.List<Model> downloading() {
        java.util.List<Model> out = new java.util.ArrayList<>();
        for (Model m : ALL) if (running.containsKey(m.id)) out.add(m);
        return out;
    }

    static synchronized boolean isDownloading(Model m) {
        return running.containsKey(m.id);
    }

    static synchronized void download(Context ctx, Model m) {
        if (running.containsKey(m.id)) return;
        Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            errors.remove(m.id);
            File part = new File(dir(app), m.id + ".part");
            try {
                long have = part.length();
                HttpURLConnection conn = (HttpURLConnection) new URL(m.url).openConnection();
                conn.setInstanceFollowRedirects(true);
                if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
                int code = conn.getResponseCode();
                if (code == 200) have = 0;  // server ignored the range: start over
                else if (code != 206) throw new Exception("HTTP " + code);
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part, have > 0)) {
                    byte[] buf = new byte[1 << 16];
                    long lastReport = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (Thread.interrupted()) throw new InterruptedException();
                        out.write(buf, 0, n);
                        have += n;
                        if (have - lastReport > 4_000_000) {
                            lastReport = have;
                            report(m, have);
                        }
                    }
                }
                if (part.length() != m.size) throw new Exception(app.getString(R.string.err_wrong_size));
                if (!part.renameTo(file(app, m))) throw new Exception(app.getString(R.string.err_cannot_save));
                if (selectedId(app, m.kind) == null) select(app, m);
            } catch (InterruptedException e) {
                part.delete();
            } catch (Exception e) {
                errors.put(m.id, e.getMessage());
            } finally {
                synchronized (Models.class) {
                    running.remove(m.id);
                    progress.remove(m.id);
                }
                report(m, -1);
            }
        });
        running.put(m.id, t);
        progress.put(m.id, new File(dir(app), m.id + ".part").length());
        t.start();
        // keeps the app alive while downloading, also in the background or with the screen off
        app.startForegroundService(new android.content.Intent(app, DownloadService.class));
    }

    private static void report(Model m, long bytes) {
        synchronized (Models.class) {
            if (bytes >= 0) progress.put(m.id, bytes);
        }
        Progress l = listener;
        if (l != null) l.update(m, bytes < 0);
        Progress sl = serviceListener;
        if (sl != null) sl.update(m, bytes < 0);
    }

    static synchronized void cancel(Model m) {
        Thread t = running.get(m.id);
        if (t != null) t.interrupt();
    }

    static void delete(Context c, Model m) {
        file(c, m).delete();
        new File(dir(c), m.id + ".part").delete();
    }

    private Models() {}
}
