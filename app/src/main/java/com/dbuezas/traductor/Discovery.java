package com.dbuezas.traductor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds models on Hugging Face that the app can run but that are not in the tested catalog:
 * Whisper (ggml) files, and TranslateGemma / small Gemma 3 GGUF files. Only from publishers I
 * trust, since a crafted GGUF file can attack the parser; and only up to 4.5 GB, as bigger ones
 * do not fit next to Whisper in 8 GB of RAM.
 */
final class Discovery {
    private static final String API = "https://huggingface.co/api/models";
    private static final Set<String> TRUSTED = Set.of("ggml-org", "ggerganov", "google", "bartowski", "unsloth",
            "lmstudio-community", "bullerwins", "mradermacher");
    // the original models only (not "abliterated", "i1", "text" or other changed versions), as the
    // family name and whether it is a QAT version (trained for 4 bits)
    private static final Pattern TRANSLATOR_REPO = Pattern.compile(
            "(?i)[^/]+/(?:google_)?(translategemma-4b|gemma-3-(?:270m|1b|4b)|gemma-3n-e[24]b)-it(-qat)?(?:-q4_0)?-gguf");
    private static final long MAX_SIZE = 4_500_000_000L;
    // the compressions that are really different; the others are about the same as one of these, or worse
    private static final Pattern QUANT = Pattern.compile("(?i)[-._](Q4_K_M|Q5_K_M|Q6_K|Q8_0)\\.gguf");
    private static final Pattern QAT_QUANT = Pattern.compile("(?i)[-._](Q4_0)\\.gguf");

    /**
     * Models not in the catalog, as catalog JSON entries without precision/speed/notes. Per model
     * only the most downloaded publisher, and per publisher only a few compressions.
     */
    static JSONArray search(List<Models.Model> catalog) throws Exception {
        Set<String> known = new HashSet<>();   // urls, and "family quant" of what is listed already
        for (Models.Model m : catalog) {
            known.add(m.url);
            String k = key(m.url);
            if (k != null) known.add(k);
        }
        JSONArray out = new JSONArray();

        // Whisper: q8 versions of the multilingual whisper.cpp models (q5 is slow on phones, the full
        // ones are twice the size for the same quality, large v1/v2 are older versions of v3)
        for (Object[] f : files("ggerganov/whisper.cpp")) {
            String name = (String) f[0];
            if (!name.matches("ggml-[a-z0-9.-]+-q8_0\\.bin") || name.contains(".en") || name.matches(".*large-v[12].*")) continue;
            add(out, known, "ggerganov/whisper.cpp", name, (Long) f[1], "whisper",
                    "Whisper " + name.replace("ggml-", "").replace(".bin", ""), false);
        }

        // translators: the API lists the most downloaded repos first; the first one per family wins
        Set<String> families = new HashSet<>();
        java.util.List<String> repos = new java.util.ArrayList<>();
        for (String q : new String[]{"translategemma", "gemma-3"}) {
            JSONArray found = new JSONArray(Models.read(new URL(API + "?search=" + q + "&filter=gguf&sort=downloads&limit=200").openStream()));
            for (int i = 0; i < found.length(); i++) {
                String id = found.getJSONObject(i).getString("id");
                java.util.regex.Matcher m = TRANSLATOR_REPO.matcher(id);
                if (!TRUSTED.contains(id.substring(0, id.indexOf('/'))) || !m.matches()) continue;
                if (families.add(m.group(1).toLowerCase() + (m.group(2) != null ? "-qat" : ""))) repos.add(id);
            }
        }
        for (String repo : repos) {
            boolean general = !repo.toLowerCase().contains("translategemma");
            boolean qat = repo.toLowerCase().contains("-qat");
            String author = repo.substring(0, repo.indexOf('/'));
            for (Object[] f : files(repo)) {
                String name = (String) f[0];
                if (name.contains("/") || !(qat ? QAT_QUANT : QUANT).matcher(name).find()) continue;
                // 270M is so small that the full 8 bits cost almost nothing more
                if (repo.contains("270m") && !name.contains("Q8_0")) continue;
                String k = key("https://huggingface.co/" + repo + "/resolve/main/" + name);
                if (k != null && !known.add(k)) continue;   // same model and compression as one listed already
                add(out, known, repo, name, (Long) f[1], "translator",
                        name.replace(".gguf", "") + " · " + author, general);
            }
        }
        // sorted by name, so versions of the same model are together
        java.util.List<JSONObject> sorted = new java.util.ArrayList<>();
        for (int i = 0; i < out.length(); i++) sorted.add(out.getJSONObject(i));
        sorted.sort((a, b) -> a.optString("label").compareToIgnoreCase(b.optString("label")));
        return new JSONArray(sorted);
    }

    /** "gemma-3-4b Q4_K_M" for a translator file url, so the same file from two publishers counts once. */
    private static String key(String url) {
        String repo = url.replaceFirst("https://huggingface.co/([^/]+/[^/]+)/.*", "$1");
        java.util.regex.Matcher r = TRANSLATOR_REPO.matcher(repo);
        java.util.regex.Matcher q = Pattern.compile("(?i)[-._](Q\\d_\\w+?)\\.gguf$").matcher(url);
        if (!r.matches() || !q.find()) return null;
        return r.group(1).toLowerCase() + (r.group(2) != null ? "-qat" : "") + " " + q.group(1).toUpperCase();
    }

    private static void add(JSONArray out, Set<String> known, String repo, String file, long size, String kind,
                            String label, boolean general) throws Exception {
        String url = "https://huggingface.co/" + repo + "/resolve/main/" + file;
        if (size <= 0 || size > MAX_SIZE || !known.add(url)) return;
        out.put(new JSONObject()
                .put("id", repo.replace('/', '_') + "_" + file)   // unique, different publishers use the same names
                .put("kind", kind).put("label", label).put("url", url).put("size", size).put("general", general));
    }

    /** {name, size} of each file in a repo. */
    private static java.util.List<Object[]> files(String repo) throws Exception {
        JSONArray s = new JSONObject(Models.read(new URL(API + "/" + repo + "?blobs=true").openStream())).getJSONArray("siblings");
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            JSONObject f = s.getJSONObject(i);
            out.add(new Object[]{f.getString("rfilename"), f.optLong("size")});
        }
        return out;
    }

    private static final Pattern BITS = Pattern.compile("(?i)(?:^|[-._])(?:I?Q|q)(\\d)(?:_|$|\\.|-)");

    /** "Made for translating. 4 bits: good balance of size and quality." from the model's name. */
    static String describe(android.content.Context c, Models.Model m) {
        String name = m.url.substring(m.url.lastIndexOf('/') + 1).toLowerCase();
        String family = m.kind == Models.Kind.WHISPER ? c.getString(R.string.fam_whisper)
                : name.contains("translategemma") ? c.getString(R.string.fam_translategemma)
                : name.matches(".*gemma-3-(1b|270m).*") ? c.getString(R.string.fam_gemma_small)
                : c.getString(R.string.fam_gemma);
        java.util.regex.Matcher b = BITS.matcher(name.replace(".gguf", "").replace(".bin", ""));
        String bits;
        if (!b.find()) {
            bits = c.getString(R.string.bits_full);   // f16 / bf16 / unquantized whisper
        } else {
            int n = Integer.parseInt(b.group(1));
            bits = c.getString(n >= 8 ? R.string.bits_8 : n == 6 ? R.string.bits_6 : n == 5 ? R.string.bits_5
                    : n == 4 ? R.string.bits_4 : R.string.bits_3, n);
        }
        return family + " " + bits;
    }

    private Discovery() {}
}
