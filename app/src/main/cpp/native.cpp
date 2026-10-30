// JNI bridge: whisper.cpp for speech-to-text, llama.cpp + TranslateGemma for translation.
// All calls come from one Java worker thread, so there is no locking here. The work is done in
// plain C++ functions (also used by tools/bench); the JNI functions only convert the arguments.

#include <jni.h>
#include <android/log.h>
#include <sched.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <fstream>
#include <functional>
#include <string>
#include <vector>

#include "llama.h"
#include "whisper.h"

#define TAG "traductor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static const int N_THREADS = 4;

// Set from Java when a newer job makes the running one useless; checked inside whisper and llama.
static std::atomic<bool> g_abort{false};
static bool abort_cb(void *) { return g_abort.load(); }

extern "C" JNIEXPORT void JNICALL
Java_com_dbuezas_traductor_Native_setAbort(JNIEnv *, jclass, jboolean v) { g_abort = v; }

// ggml worker threads inherit the CPU mask of the thread that creates them, so pinning the
// calling thread to the fastest cores keeps the slow little cores out of every computation.
static void pin_to_fast_cores() {
    std::vector<std::pair<long, int>> freqs;
    for (int i = 0; i < 16; i++) {
        std::ifstream in("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq");
        long f;
        if (in >> f) freqs.emplace_back(f, i);
    }
    if (freqs.empty()) return;
    std::sort(freqs.rbegin(), freqs.rend());
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int k = 0; k < std::min<int>(N_THREADS, (int) freqs.size()); k++) CPU_SET(freqs[k].second, &set);
    sched_setaffinity(0, sizeof(set), &set);
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static jbyteArray jbytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), (const jbyte *) s.data());
    return arr;
}

// ---------------------------------------------------------------------------------------------
// Whisper

// Two models: 0 = main (accurate, settles text), 1 = live (small and fast, gray preview text).
//
// The encoder window (audio_ctx, 1500 = 30 s) is sized to the audio because short windows are
// much faster. But a whisper state must always be used with the same window: after a call with a
// bigger window, calls with a smaller one write nonsense. So each model has one state per window
// size. The smallest window makes whisper repeat itself more ("Hola. Hola."); the Java side
// filters repeated sentences.
static const int N_WINDOWS = 3;
static const int WINDOWS[N_WINDOWS] = {384, 768, 1500};

struct Whisper {
    whisper_context *ctx = nullptr;
    whisper_state *states[N_WINDOWS] = {nullptr, nullptr, nullptr};
    whisper_state *last = nullptr;   // state of the last transcription, for language detection
};
static Whisper g_whisper[2];

// The state for the biggest window takes ~250 MB and is rarely needed, so it only exists
// during the call that uses it (and the language detection right after).
static const int BIG = N_WINDOWS - 1;

// Picks the smallest window that fits the audio plus ~4 s of margin.
static int window_for(int n_samples, bool full) {
    if (full) return BIG;
    const float need = n_samples / 16000.0f * 50.0f + 192;
    for (int i = 0; i < BIG; i++) if (need <= WINDOWS[i]) return i;
    return BIG;
}

// Transcribes and returns one line per word: "<end in ms>\t<word>". The word ends let the
// caller cut the text into sentences and drop audio whose text is settled.
// prompt: previous text, for context. full: use the 30 s window (slower, more robust).
static std::string run_whisper(Whisper &w, const float *pcm, int n, const char *lang, const char *prompt, bool full = false) {
    const int win = window_for(n, full);
    if (!w.states[win]) w.states[win] = whisper_init_state(w.ctx);
    whisper_state *st = w.states[win];
    if (!st) return "";
    w.last = st;
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = N_THREADS;
    p.language = lang;
    p.no_context = true;
    p.no_timestamps = false;
    p.single_segment = false;
    p.token_timestamps = true;   // one segment per word, with its end time
    p.max_len = 1;
    p.split_on_word = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.temperature_inc = 0.0f;  // no slow retries
    p.audio_ctx = WINDOWS[win];
    p.initial_prompt = prompt && *prompt ? prompt : nullptr;
    p.abort_callback = [](void *) { return g_abort.load(); };
    p.max_tokens = 16 + n / 16000 * 8;  // ~8 tokens per second of audio: stops hallucination loops
    if (whisper_full_with_state(w.ctx, st, p, pcm, n) != 0) return "";
    // Whisper's own rule for "nothing was said": a high no-speech score AND unsure words.
    // The score alone is high for real speech with silence around it (e.g. a held talk button).
    const int n_seg = whisper_full_n_segments_from_state(st);
    double logprob = 0;
    int n_tok = 0;
    for (int i = 0; i < n_seg; i++) {
        for (int j = 0; j < whisper_full_n_tokens_from_state(st, i); j++) {
            const whisper_token_data d = whisper_full_get_token_data_from_state(st, i, j);
            if (d.id >= whisper_token_eot(w.ctx)) continue;   // special and timestamp tokens
            logprob += d.plog;
            n_tok++;
        }
    }
    const float no_speech = n_seg > 0 ? whisper_full_get_segment_no_speech_prob_from_state(st, 0) : 0.0f;
    const float avg_logprob = n_tok > 0 ? (float) (logprob / n_tok) : 0.0f;
    LOGI("window %d, no_speech %.2f, avg logprob %.2f, %d words", WINDOWS[win], no_speech, avg_logprob, n_seg);
    std::string out;
    if (no_speech <= 0.6f || avg_logprob >= -1.0f) {
        for (int i = 0; i < n_seg; i++) {
            const int64_t end_ms = std::min<int64_t>(whisper_full_get_segment_t1_from_state(st, i) * 10, n / 16);
            std::string text = whisper_full_get_segment_text_from_state(st, i);
            std::replace(text.begin(), text.end(), '\n', ' ');
            out += std::to_string(end_ms) + "\t" + text + "\n";
        }
    }
    return out;
}

// Frees the big-window state after a call that used it (it is rarely needed).
static void release_big_state(Whisper &w) {
    if (!w.states[BIG]) return;
    if (w.last == w.states[BIG]) w.last = nullptr;
    whisper_free_state(w.states[BIG]);
    w.states[BIG] = nullptr;
}

static void free_whisper(Whisper &w) {
    for (auto &st : w.states) if (st) whisper_free_state(st);
    if (w.ctx) whisper_free(w.ctx);
    w = Whisper();
}

// path empty: just unload
static bool load_whisper(int slot, const std::string &path) {
    pin_to_fast_cores();
    Whisper &w = g_whisper[slot];
    free_whisper(w);
    if (path.empty()) return false;
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;   // same speed here, and the encoder buffer shrinks from 212 to 55 MB
    w.ctx = whisper_init_from_file_with_params_no_state(path.c_str(), cp);
    if (!w.ctx) return false;
    // warm-up of the small windows: creates their states and compute buffers now, not on the
    // first real phrase
    for (float seconds : {2.0f, 8.0f}) {
        std::vector<float> silence((size_t) (16000 * seconds), 0.0f);
        run_whisper(w, silence.data(), (int) silence.size(), "en", nullptr, false);
    }
    if (!w.states[0]) {
        free_whisper(w);
        return false;
    }
    return true;
}

static std::string transcribe(int slot, const float *pcm, int n, const std::string &lang, const std::string &prompt, bool full) {
    pin_to_fast_cores();
    Whisper &w = g_whisper[slot];
    if (!w.ctx) return "";
    // the big-window state of the previous call was kept for its language detection: free it now
    if (window_for(n, full) != BIG) release_big_state(w);
    return run_whisper(w, pcm, n, lang.c_str(), prompt.c_str(), full);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dbuezas_traductor_Native_loadWhisper(JNIEnv *env, jclass, jint slot, jstring path) {
    return load_whisper(slot, jstr(env, path));
}

// Returns whichever of langA / langB whisper thinks is more likely for the audio of the last
// transcribe() call. Reuses that run's encoder output (patched whisper.cpp), so it is cheap.
extern "C" JNIEXPORT jstring JNICALL
Java_com_dbuezas_traductor_Native_detectLanguage(JNIEnv *env, jclass, jint slot, jstring langA, jstring langB) {
    pin_to_fast_cores();
    Whisper &w = g_whisper[slot];
    const std::string a = jstr(env, langA), b = jstr(env, langB);
    if (!w.ctx || !w.last) return env->NewStringUTF(a.c_str());
    std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
    if (whisper_lang_detect_after_full_with_state(w.ctx, w.last, N_THREADS, probs.data()) < 0) return env->NewStringUTF(a.c_str());
    const float pa = probs[whisper_lang_id(a.c_str())], pb = probs[whisper_lang_id(b.c_str())];
    LOGI("detect %s=%.3f %s=%.3f", a.c_str(), pa, b.c_str(), pb);
    return env->NewStringUTF(pa >= pb ? a.c_str() : b.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dbuezas_traductor_Native_transcribe(JNIEnv *env, jclass, jint slot, jfloatArray pcm, jstring lang, jstring prompt,
                                             jboolean fullCtx) {
    const int n = env->GetArrayLength(pcm);
    float *data = env->GetFloatArrayElements(pcm, nullptr);
    std::string text = transcribe(slot, data, n, jstr(env, lang), jstr(env, prompt), fullCtx);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    return jbytes(env, text);
}

// ---------------------------------------------------------------------------------------------
// Translation (TranslateGemma prompt format)

static llama_model *g_model = nullptr;
static llama_context *g_lctx = nullptr;
static const llama_vocab *g_vocab = nullptr;
static llama_sampler *g_smpl = nullptr;

// Sequences 0 and 1 keep the evaluated instruction prompt of the last two directions
// (e.g. de->es and es->de); sequence 2 is the scratch copy each translation works in.
static const int WORK_SEQ = 2;
static std::string g_prefix_text[2];
static int g_prefix_len[2] = {0, 0};
static int g_prefix_lru = 0;

static std::vector<llama_token> tokenize(const std::string &text, bool add_bos, bool special) {
    std::vector<llama_token> toks(text.size() + 8);
    int n = llama_tokenize(g_vocab, text.c_str(), (int) text.size(), toks.data(), (int) toks.size(), add_bos, special);
    if (n < 0) {
        toks.resize(-n);
        n = llama_tokenize(g_vocab, text.c_str(), (int) text.size(), toks.data(), (int) toks.size(), add_bos, special);
    }
    toks.resize(std::max(n, 0));
    return toks;
}

// Evaluates tokens at positions [pos, pos+n) of one sequence; logits only for the last token.
static bool eval(const std::vector<llama_token> &toks, int pos, int seq, bool want_logits) {
    const int n_batch = 512;
    for (size_t start = 0; start < toks.size(); start += n_batch) {
        const int n = (int) std::min<size_t>(n_batch, toks.size() - start);
        llama_batch batch = llama_batch_init(n, 0, 1);
        for (int i = 0; i < n; i++) {
            batch.token[i] = toks[start + i];
            batch.pos[i] = pos + (int) start + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = seq;
            batch.logits[i] = want_logits && start + i == toks.size() - 1;
        }
        batch.n_tokens = n;
        const int rc = llama_decode(g_lctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            LOGE("llama_decode failed: %d", rc);
            return false;
        }
    }
    return true;
}

// The fixed instruction part of the prompt, up to where the text goes. TranslateGemma has its own
// fixed format; a general Gemma gets a plain instruction (and can be asked for Rioplatense voseo).
static std::string make_prefix(const std::string &S, const std::string &s, const std::string &T, const std::string &t, bool general) {
    if (general) {
        const std::string target = t == "es-AR" ? "Rioplatense Spanish from Argentina, using \"vos\" instead of \"tú\"" : T;
        return "<start_of_turn>user\nTranslate the following " + S + " text into " + target +
               ". Reply with only the translation, without any explanations or comments.\n\n";
    }
    return "<start_of_turn>user\nYou are a professional " + S + " (" + s + ") to " + T + " (" + t +
           ") translator. Your goal is to accurately convey the meaning and nuances of the original " + S +
           " text while adhering to " + T + " grammar, vocabulary, and cultural sensitivities.\n"
           "Produce only the " + T + " translation, without any additional explanations or commentary. "
           "Please translate the following " + S + " text into " + T + ":\n\n\n";
}

static std::string g_cache_dir, g_model_path;

// Returns the cache slot holding this prefix, filling the older slot if needed: from a file
// saved earlier (instant), or by evaluating it (~7 s per direction) and saving it.
static int ensure_prefix(const std::string &prefix) {
    int slot = g_prefix_text[0] == prefix ? 0 : g_prefix_text[1] == prefix ? 1 : -1;
    if (slot < 0) {
        slot = g_prefix_lru;
        llama_memory_t mem = llama_get_memory(g_lctx);
        llama_memory_seq_rm(mem, slot, -1, -1);
        g_prefix_text[slot].clear();
        auto toks = tokenize(prefix, true, true);
        const std::string file = g_cache_dir.empty() ? "" : g_cache_dir + "/prefix-" +
            std::to_string(std::hash<std::string>{}(g_model_path + "\n" + prefix)) + ".bin";
        bool loaded = false;
        if (!file.empty()) {
            std::vector<llama_token> saved(toks.size() + 8);
            size_t n_saved = 0;
            loaded = llama_state_seq_load_file(g_lctx, file.c_str(), slot, saved.data(), saved.size(), &n_saved) > 0
                     && n_saved == toks.size() && std::equal(toks.begin(), toks.end(), saved.begin());
            if (!loaded) llama_memory_seq_rm(mem, slot, -1, -1);
        }
        if (!loaded) {
            if (!eval(toks, 0, slot, false)) return -1;
            if (!file.empty()) llama_state_seq_save_file(g_lctx, file.c_str(), slot, toks.data(), toks.size());
        }
        LOGI("prefix %s into slot %d (%zu tokens)", loaded ? "loaded" : "evaluated", slot, toks.size());
        g_prefix_text[slot] = prefix;
        g_prefix_len[slot] = (int) toks.size();
    }
    g_prefix_lru = 1 - slot;
    return slot;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dbuezas_traductor_Native_setCacheDir(JNIEnv *env, jclass, jstring dir) {
    g_cache_dir = jstr(env, dir);
}

// Evaluates the instruction prompt of one direction ahead of time, so the first translation
// in that direction starts generating right away.
extern "C" JNIEXPORT void JNICALL
Java_com_dbuezas_traductor_Native_prepareTranslation(JNIEnv *env, jclass, jstring jsrcName, jstring jsrcCode,
                                                     jstring jtgtName, jstring jtgtCode, jboolean general) {
    pin_to_fast_cores();
    if (!g_lctx) return;
    ensure_prefix(make_prefix(jstr(env, jsrcName), jstr(env, jsrcCode), jstr(env, jtgtName), jstr(env, jtgtCode), general));
}

static bool load_translator(const std::string &path) {
    pin_to_fast_cores();
    llama_backend_init();
    if (g_smpl) llama_sampler_free(g_smpl);
    if (g_lctx) llama_free(g_lctx);
    if (g_model) llama_model_free(g_model);
    g_smpl = nullptr;
    g_lctx = nullptr;
    g_prefix_text[0].clear();
    g_prefix_text[1].clear();

    g_model_path = path;
    llama_model_params mp = llama_model_default_params();
    // Keep the weights in the memory-mapped file instead of a repacked copy on the heap. Reading
    // the prompt gets ~2x slower (the long instruction part is cached anyway), generating stays
    // as fast, and 2.4 GB stay out of the heap, where Android would squeeze them into swap.
    mp.use_extra_bufts = false;
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) return false;
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_seq_max = 3;
    cp.n_threads = N_THREADS;
    cp.n_threads_batch = N_THREADS;
    cp.kv_unified = true;  // the work sequence shares the cached prefix cells
    cp.swa_full = true;    // Gemma 3 sliding-window cache must keep the whole prefix to copy it
    g_lctx = llama_init_from_model(g_model, cp);
    if (!g_lctx) return false;
    llama_set_abort_callback(g_lctx, abort_cb, nullptr);

    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dbuezas_traductor_Native_loadTranslator(JNIEnv *env, jclass, jstring path) {
    return load_translator(jstr(env, path));
}

// Translates text. Calls on_partial(utf8 so far) as tokens arrive; returns the full UTF-8 result.
static std::string translate(const std::string &S, const std::string &s, const std::string &T, const std::string &t,
                             const std::string &text, bool general, const std::function<void(const std::string &)> &on_partial) {
    pin_to_fast_cores();
    if (!g_lctx) return "";
    const std::string prefix = make_prefix(S, s, T, t, general);

    llama_memory_t mem = llama_get_memory(g_lctx);
    llama_memory_seq_rm(mem, WORK_SEQ, -1, -1);

    const int slot = ensure_prefix(prefix);
    if (slot < 0) return "";
    llama_memory_seq_cp(mem, slot, WORK_SEQ, -1, -1);
    int pos = g_prefix_len[slot];

    auto toks = tokenize(text, false, false);
    auto tail = tokenize("<end_of_turn>\n<start_of_turn>model\n", false, true);
    toks.insert(toks.end(), tail.begin(), tail.end());
    if (!eval(toks, pos, WORK_SEQ, true)) return "";
    pos += (int) toks.size();

    std::string out;
    const int max_tokens = 256;
    llama_sampler_reset(g_smpl);
    for (int i = 0; i < max_tokens && !g_abort; i++) {
        llama_token tok = llama_sampler_sample(g_smpl, g_lctx, -1);
        if (llama_vocab_is_eog(g_vocab, tok)) break;
        char buf[256];
        int n = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        on_partial(out);
        if (!eval({tok}, pos++, WORK_SEQ, true)) break;
    }
    llama_memory_seq_rm(mem, WORK_SEQ, -1, -1);
    return out;
}

// listener.onPartial(byte[] utf8SoFar) as tokens arrive. Bytes (not String) because a token can
// end in the middle of a character.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dbuezas_traductor_Native_translate(JNIEnv *env, jclass, jstring jsrcName, jstring jsrcCode,
                                            jstring jtgtName, jstring jtgtCode, jstring jtext, jboolean general,
                                            jobject listener) {
    jmethodID onPartial = env->GetMethodID(env->GetObjectClass(listener), "onPartial", "([B)V");
    return jbytes(env, translate(jstr(env, jsrcName), jstr(env, jsrcCode), jstr(env, jtgtName), jstr(env, jtgtCode),
                                 jstr(env, jtext), general, [&](const std::string &out) {
        jbyteArray arr = jbytes(env, out);
        env->CallVoidMethod(listener, onPartial, arr);
        env->DeleteLocalRef(arr);
    }));
}
