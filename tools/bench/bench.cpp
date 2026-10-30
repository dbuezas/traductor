// Runs the app's own native code (app/src/main/cpp/native.cpp) on a list of test sentences, on
// the phone through adb, and prints the results with the time each one took. See README.md.
//
//   bench whisper <model.bin> <list>       list lines: id \t lang \t file.wav (16 kHz mono)
//   bench translate <model.gguf> <general 0/1> <list>
//                                          list lines: id \t source lang \t target lang \t text
// Output lines: id \t ms \t text; for whisper also \t the raw "<end ms>\t<word>" lines joined
// by \x1e, so score.py can drop repetitions the way the app does

#include "../../app/src/main/cpp/native.cpp"

#include <chrono>
#include <cstdio>
#include <map>
#include <sstream>

static double now_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

static std::vector<std::vector<std::string>> read_list(const char *path) {
    std::ifstream in(path);
    std::vector<std::vector<std::string>> out;
    for (std::string line; std::getline(in, line);) {
        std::vector<std::string> cols;
        std::stringstream ss(line);
        for (std::string c; std::getline(ss, c, '\t');) cols.push_back(c);
        if (!cols.empty()) out.push_back(cols);
    }
    return out;
}

static std::vector<float> read_wav(const std::string &path) {
    std::ifstream f(path, std::ios::binary);
    std::vector<char> b((std::istreambuf_iterator<char>(f)), {});
    std::vector<float> out;
    for (size_t i = 44; i + 1 < b.size(); i += 2) out.push_back((int16_t) ((uint8_t) b[i] | (b[i + 1] << 8)) / 32768.0f);
    return out;
}

// the app's language names for the translator prompt
static const std::map<std::string, std::string> NAMES = {
    {"de", "German"}, {"es", "Spanish"}, {"en", "English"}, {"fr", "French"}, {"it", "Italian"}, {"pt", "Portuguese"}, {"nl", "Dutch"}};

int main(int argc, char **argv) {
    const std::string mode = argc > 1 ? argv[1] : "";
    if (mode == "whisper" && argc == 4) {
        double t = now_ms();
        if (!load_whisper(0, argv[2])) return fprintf(stderr, "cannot load %s\n", argv[2]), 1;
        fprintf(stderr, "loaded in %.0f ms\n", now_ms() - t);
        for (auto &c : read_list(argv[3])) {
            std::vector<float> pcm = read_wav(c[2]);
            t = now_ms();
            std::string words = transcribe(0, pcm.data(), (int) pcm.size(), c[1], "", false);
            const double ms = now_ms() - t;
            std::string text, raw;   // the lines are "<end ms>\t<word>"
            std::stringstream ss(words);
            for (std::string line; std::getline(ss, line);) {
                text += line.substr(line.find('\t') + 1);
                raw += (raw.empty() ? "" : "\x1e") + line;
            }
            printf("%s\t%.0f\t%s\t%s\n", c[0].c_str(), ms, text.c_str(), raw.c_str());
            fflush(stdout);
        }
        return 0;
    }
    if (mode == "translate" && argc == 5) {
        const bool general = argv[3][0] == '1';
        double t = now_ms();
        if (!load_translator(argv[2])) return fprintf(stderr, "cannot load %s\n", argv[2]), 1;
        fprintf(stderr, "loaded in %.0f ms\n", now_ms() - t);
        for (auto &c : read_list(argv[4])) {
            const std::string S = NAMES.at(c[1]), T = NAMES.at(c[2]);
            // the instruction part is cached in the app, so it is not part of a sentence's time
            ensure_prefix(make_prefix(S, c[1], T, c[2], general));
            t = now_ms();
            std::string out = translate(S, c[1], T, c[2], c[3], general, [](const std::string &) {});
            const double ms = now_ms() - t;
            std::replace(out.begin(), out.end(), '\n', ' ');
            std::replace(out.begin(), out.end(), '\t', ' ');
            printf("%s\t%.0f\t%s\n", c[0].c_str(), ms, out.c_str());
            fflush(stdout);
        }
        return 0;
    }
    fprintf(stderr, "usage: bench whisper <model> <list> | bench translate <model> <general 0/1> <list>\n");
    return 2;
}
