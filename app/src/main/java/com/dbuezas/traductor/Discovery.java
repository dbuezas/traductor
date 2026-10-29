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
    // model families the app's llama.cpp runs with a Gemma prompt; small enough for a phone
    private static final Pattern TRANSLATOR_REPO = Pattern.compile(
            "(?i)[^/]+/([a-z]+_)?(translategemma-4b|gemma-3-(270m|1b|4b)|gemma-3n-e[24]b)-it\\b.*gguf.*");
    private static final long MAX_SIZE = 4_500_000_000L;
    private static final Pattern TOO_SMALL = Pattern.compile("(?i)IQ1|IQ2|Q2_K|IQ3_XXS");

    /** Models not in the catalog, as catalog JSON entries without precision/speed/notes. */
    static JSONArray search(List<Models.Model> catalog) throws Exception {
        Set<String> known = new HashSet<>();
        for (Models.Model m : catalog) known.add(m.url);
        JSONArray out = new JSONArray();

        // Whisper: multilingual ggml files of whisper.cpp (".en" ones only know English)
        for (Object[] f : files("ggerganov/whisper.cpp")) {
            String name = (String) f[0];
            if (!name.matches("ggml-[a-z0-9.-]+\\.bin") || name.contains(".en") || name.contains("encoder")) continue;
            add(out, known, "ggerganov/whisper.cpp", name, (Long) f[1], "whisper",
                    "Whisper " + name.replace("ggml-", "").replace(".bin", ""), false);
        }

        // translators
        Set<String> repos = new HashSet<>();
        for (String q : new String[]{"translategemma", "gemma-3"}) {
            JSONArray found = new JSONArray(Models.read(new URL(API + "?search=" + q + "&filter=gguf&limit=200").openStream()));
            for (int i = 0; i < found.length(); i++) {
                String id = found.getJSONObject(i).getString("id");
                if (TRUSTED.contains(id.substring(0, id.indexOf('/'))) && TRANSLATOR_REPO.matcher(id).matches()) repos.add(id);
            }
        }
        for (String repo : repos) {
            boolean general = !repo.toLowerCase().contains("translategemma");
            String author = repo.substring(0, repo.indexOf('/'));
            for (Object[] f : files(repo)) {
                String name = (String) f[0];
                if (!name.endsWith(".gguf") || name.contains("mmproj") || name.contains("/")) continue;
                if (TOO_SMALL.matcher(name).find()) continue;   // 1-2 bit versions: too damaged to translate
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
