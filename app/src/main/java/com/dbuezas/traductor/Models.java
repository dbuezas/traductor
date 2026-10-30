package com.dbuezas.traductor;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The models: the tested catalog (catalog/models.json in the repo, shipped in the app and
 * refreshed from GitHub), plus "more models" found on Hugging Face from trusted publishers
 * (untested). Also downloads, deletes, and which model is selected.
 */
final class Models {
    enum Kind { WHISPER, TRANSLATOR }

    static final class Model {
        final String id;          // file name on the phone
        final Kind kind;
        final String label;
        final Map<String, String> notes;   // language code -> note; empty for discovered models
        final String url;
        final long size;
        final boolean general;    // translator: a general chat model, not TranslateGemma (other prompt)
        final int precision, speed;   // 1..5; 0 = not tested
        final double quality;         // measured: Whisper % of words wrong, translator chrF; 0 = not measured
        final int time;               // measured: % of the speaking time
        final int ram;                // measured: MB of memory used; 0 = not measured
        final boolean hidden;         // another model is better and faster: listed only once downloaded

        Model(String id, Kind kind, String label, Map<String, String> notes, String url, long size, boolean general,
              int precision, int speed, double quality, int time, int ram, boolean hidden) {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.notes = notes;
            this.url = url;
            this.size = size;
            this.general = general;
            this.precision = precision;
            this.speed = speed;
            this.quality = quality;
            this.time = time;
            this.ram = ram;
            this.hidden = hidden;
        }

        boolean tested() {
            return precision > 0;
        }

        /**
         * The note in the phone's language, else English. Models found on Hugging Face have no
         * note: it is made from the name (model family and how much it is compressed).
         */
        String note(Context c) {
            if (notes.isEmpty()) return Discovery.describe(c, this);
            String n = notes.get(java.util.Locale.getDefault().getLanguage());
            return n != null ? n : notes.getOrDefault("en", "");
        }
    }

    private static final String CATALOG_URL = "https://raw.githubusercontent.com/dbuezas/traductor/main/catalog/models.json";
    static volatile List<Model> catalog = new ArrayList<>();
    static volatile List<Model> discovered = new ArrayList<>();

    /** Tested models first (best to worst per kind), then the discovered ones. */
    static List<Model> all() {
        List<Model> out = new ArrayList<>(catalog);
        out.addAll(discovered);
        return out;
    }

    static Model byId(String id) {
        for (Model m : all()) if (m.id.equals(id)) return m;
        return null;
    }

    /** Loads the newest saved catalog (or the one in the app) and the last discovery results. */
    static synchronized void init(Context c) {
        if (!catalog.isEmpty()) return;
        try {
            File saved = new File(c.getFilesDir(), "catalog.json");
            String json = saved.exists() ? read(new java.io.FileInputStream(saved)) : read(c.getAssets().open("models.json"));
            catalog = parse(json);
        } catch (Exception e) {
            try {
                catalog = parse(read(c.getAssets().open("models.json")));
            } catch (Exception ignored) {}
        }
        try {
            File saved = new File(c.getFilesDir(), "discovered.json");
            if (saved.exists()) discovered = parse(read(new java.io.FileInputStream(saved)));
        } catch (Exception ignored) {}
    }

    /** In the background: newest catalog from GitHub, then the search on Hugging Face. */
    static void refresh(Context ctx, Runnable done) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String json = read(new URL(CATALOG_URL).openStream());
                List<Model> fresh = parse(json);
                if (!fresh.isEmpty()) {
                    catalog = fresh;
                    write(new File(app.getFilesDir(), "catalog.json"), json);
                }
            } catch (Exception ignored) {}   // offline: keep what we have
            try {
                JSONArray found = Discovery.search(catalog);
                discovered = parse(new JSONObject().put("models", found).toString());
                write(new File(app.getFilesDir(), "discovered.json"), new JSONObject().put("models", found).toString());
            } catch (Exception ignored) {}
            done.run();
        }).start();
    }

    static List<Model> parse(String json) throws JSONException {
        List<Model> out = new ArrayList<>();
        JSONArray a = new JSONObject(json).getJSONArray("models");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Map<String, String> notes = new HashMap<>();
            JSONObject n = o.optJSONObject("note");
            if (n != null) for (java.util.Iterator<String> it = n.keys(); it.hasNext(); ) {
                String k = it.next();
                notes.put(k, n.getString(k));
            }
            out.add(new Model(o.getString("id"), o.getString("kind").equals("whisper") ? Kind.WHISPER : Kind.TRANSLATOR,
                    o.getString("label"), notes, o.getString("url"), o.getLong("size"), o.optBoolean("general"),
                    o.optInt("precision"), o.optInt("speed"), o.optDouble("quality", 0), o.optInt("time"),
                    o.optInt("ram"), o.optBoolean("hidden")));
        }
        return out;
    }

    static String read(InputStream in) throws java.io.IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static void write(File f, String s) throws java.io.IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** The selected model of a kind, if downloaded (null otherwise). */
    static Model selectedModel(Context c, Kind k) {
        Model m = byId(selectedId(c, k));
        return m != null && isDownloaded(c, m) ? m : null;
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
        Model m = selectedModel(c, k);
        return m == null ? null : file(c, m);
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
        for (Model m : all()) if (running.containsKey(m.id)) out.add(m);
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
