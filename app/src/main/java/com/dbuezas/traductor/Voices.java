package com.dbuezas.traductor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * The phone's text-to-speech voices per language, which one the user picked, and whether each is
 * a man's or a woman's voice. Android does not say that: each voice reads a sample into a file
 * once (nothing is heard), and the pitch of the sample tells (below 165 Hz: a man's voice).
 */
final class Voices {
    /** The two languages of the conversation. */
    static Lang[] langsInUse(Context c) {
        Lang a = Lang.byCode(Models.prefs(c).getString("langA", "es"), Lang.ES);
        Lang b = Lang.byCode(Models.prefs(c).getString("langB", "de"), Lang.DE);
        return a == b ? new Lang[]{a} : new Lang[]{a, b};
    }

    /** Installed voices that speak this language, offline ones first. */
    static List<Voice> forLang(TextToSpeech tts, Lang l) {
        List<Voice> out = new ArrayList<>();
        Set<Voice> all = tts.getVoices();
        if (all == null) return out;
        for (Voice v : all) {
            if (v.getLocale().getLanguage().equals(l.locale.getLanguage())
                    && !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) out.add(v);
        }
        out.sort((x, y) -> x.isNetworkConnectionRequired() != y.isNetworkConnectionRequired()
                ? (x.isNetworkConnectionRequired() ? 1 : -1) : x.getName().compareTo(y.getName()));
        return out;
    }

    /**
     * Voice names are codes like "de-de-x-deb-local": shows "Woman 2 · Germany" (numbered among the
     * voices of the same kind in the list), and whether it needs internet.
     */
    static String label(Context c, Voice v, List<Voice> all) {
        String g = gender(c, v);
        int n = 0;
        for (Voice o : all) {
            if (g == null || g.equals(gender(c, o))) n++;
            if (o.getName().equals(v.getName())) break;
        }
        String country = v.getLocale().getDisplayCountry();
        return c.getString("f".equals(g) ? R.string.voice_female : "m".equals(g) ? R.string.voice_male : R.string.voice_label, n)
                + (country.isEmpty() ? "" : " · " + country)
                + (v.isNetworkConnectionRequired() ? " · " + c.getString(R.string.voice_needs_internet) : "");
    }

    /** "f", "m", or null if not measured yet. */
    static String gender(Context c, Voice v) {
        return v == null ? null : Models.prefs(c).getString("gender_" + v.getName(), null);
    }

    /** ♀ or ♂ for a voice, or a neutral mark while it is not known. */
    static String symbol(Context c, Voice v) {
        String g = gender(c, v);
        return "f".equals(g) ? "♀" : "m".equals(g) ? "♂" : "⚥";
    }

    private static boolean measuring;

    /**
     * Measures the voices of these languages that were not measured yet, in the background, then
     * runs done on the main thread (if anything was measured). Each voice takes ~0.3 s, once.
     */
    static void measure(Context ctx, Lang[] langs, Runnable done) {
        Context c = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        synchronized (Voices.class) {
            if (measuring) return;
            measuring = true;
        }
        TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(c, status -> {
            TextToSpeech tts = holder[0];
            LinkedList<Voice> todo = new LinkedList<>();
            LinkedList<Lang> sampleLang = new LinkedList<>();
            if (status == TextToSpeech.SUCCESS) for (Lang l : langs) for (Voice v : forLang(tts, l)) {
                if (gender(c, v) == null && !v.isNetworkConnectionRequired()) {
                    todo.add(v);
                    sampleLang.add(l);
                }
            }
            if (todo.isEmpty()) {
                tts.shutdown();
                synchronized (Voices.class) { measuring = false; }
                return;
            }
            File file = new File(c.getCacheDir(), "voice-sample.wav");
            Runnable[] next = new Runnable[1];
            next[0] = () -> {
                if (todo.isEmpty()) {
                    tts.shutdown();
                    synchronized (Voices.class) { measuring = false; }
                    main.post(done);
                    return;
                }
                Voice v = todo.peekFirst();
                tts.setVoice(v);
                tts.synthesizeToFile(sampleLang.peekFirst().holdToTalk, null, file, v.getName());
            };
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}

                @Override public void onDone(String id) {
                    float f0 = pitch(file);
                    android.util.Log.i("traductor", "voice " + id + ": " + Math.round(f0) + " Hz");
                    if (f0 > 0) Models.prefs(c).edit().putString("gender_" + id, f0 < 165 ? "m" : "f").apply();
                    todo.pollFirst();
                    sampleLang.pollFirst();
                    next[0].run();
                }

                @Override public void onError(String id) {
                    todo.pollFirst();
                    sampleLang.pollFirst();
                    next[0].run();
                }
            });
            next[0].run();
        });
    }

    /**
     * The median pitch (Hz) of a 16-bit mono WAV: for each 40 ms frame with sound, the lag with the
     * strongest autocorrelation between 60 and 400 Hz. 0 if nothing voiced was found.
     */
    static float pitch(File wav) {
        try (RandomAccessFile f = new RandomAccessFile(wav, "r")) {
            byte[] b = new byte[(int) f.length()];
            f.readFully(b);
            int rate = (b[24] & 0xFF) | (b[25] & 0xFF) << 8 | (b[26] & 0xFF) << 16 | (b[27] & 0xFF) << 24;
            int pos = 12, dataStart = -1, dataLen = 0;
            while (pos + 8 <= b.length) {   // find the "data" chunk
                int size = (b[pos + 4] & 0xFF) | (b[pos + 5] & 0xFF) << 8 | (b[pos + 6] & 0xFF) << 16 | (b[pos + 7] & 0xFF) << 24;
                if (b[pos] == 'd' && b[pos + 1] == 'a' && b[pos + 2] == 't' && b[pos + 3] == 'a') {
                    dataStart = pos + 8;
                    dataLen = Math.min(size, b.length - dataStart);
                    break;
                }
                pos += 8 + size + (size & 1);
            }
            if (dataStart < 0 || rate <= 0) return 0;
            float[] x = new float[dataLen / 2];
            for (int i = 0; i < x.length; i++) x[i] = (short) ((b[dataStart + 2 * i] & 0xFF) | b[dataStart + 2 * i + 1] << 8) / 32768f;
            int frame = rate * 40 / 1000, hop = frame / 2, minLag = rate / 400, maxLag = rate / 60;
            List<Float> f0s = new ArrayList<>();
            for (int s = 0; s + frame + maxLag < x.length; s += hop) {
                double e = 0;
                for (int i = 0; i < frame; i++) e += x[s + i] * x[s + i];
                if (e / frame < 1e-4) continue;   // silence
                double[] r = new double[maxLag + 2];
                double best = 0;
                for (int lag = minLag; lag <= maxLag + 1; lag++) {
                    double sum = 0, e2 = 0;
                    for (int i = 0; i < frame; i++) {
                        sum += x[s + i] * x[s + i + lag];
                        e2 += x[s + i + lag] * x[s + i + lag];
                    }
                    r[lag] = sum / (Math.sqrt(e * e2) + 1e-9);
                    if (lag <= maxLag) best = Math.max(best, r[lag]);
                }
                if (best < 0.6) continue;   // not clearly voiced
                // twice the period correlates about as well: take the shortest lag that is a peak
                // close to the best one, or a woman's voice would read an octave too low
                for (int lag = minLag + 1; lag <= maxLag; lag++) {
                    if (r[lag] >= 0.9 * best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                        f0s.add((float) rate / lag);
                        break;
                    }
                }
            }
            if (f0s.size() < 5) return 0;
            Float[] a = f0s.toArray(new Float[0]);
            Arrays.sort(a);
            return a[a.length / 2];
        } catch (Exception e) {
            return 0;
        }
    }

    static String chosen(Context c, Lang l) {
        return Models.prefs(c).getString("voice_" + l.code, null);
    }

    static void choose(Context c, Lang l, Voice v) {
        Models.prefs(c).edit().putString("voice_" + l.code, v == null ? null : v.getName()).apply();
    }

    /** The picked voice for this language, if it is still installed. */
    static Voice find(Context c, TextToSpeech tts, Lang l) {
        String name = chosen(c, l);
        if (name == null || tts.getVoices() == null) return null;
        for (Voice v : tts.getVoices()) if (v.getName().equals(name)) return v;
        return null;
    }

    private Voices() {}
}
