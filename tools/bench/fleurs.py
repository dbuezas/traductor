#!/usr/bin/env python3
"""Gets the test sentences from FLEURS (Google's set of the same sentences read aloud in 100
languages): N sentences that exist in both languages, with the audio of one speaker each.

    fleurs.py <out dir> [N]

Writes into <out dir>: the WAV files, whisper.tsv (id, lang, wav) and translate.tsv
(id, source, target, text) for bench, and refs.tsv (id, lang, text) for score.py.
"""
import array, csv, io, os, struct, sys, tarfile, urllib.request, wave

LANGS = {"de": "de_de", "es": "es_419"}
BASE = "https://huggingface.co/datasets/google/fleurs/resolve/main/data/"
out = sys.argv[1]
n = int(sys.argv[2]) if len(sys.argv) > 2 else 50
os.makedirs(out, exist_ok=True)

def to_pcm16(data, path):
    """FLEURS has 32-bit float WAV files; bench (like the app) reads 16-bit ones."""
    pos, samples = 12, None
    while pos + 8 <= len(data):
        cid, size = data[pos:pos + 4], struct.unpack("<I", data[pos + 4:pos + 8])[0]
        if cid == b"data":
            samples = array.array("f", data[pos + 8:pos + 8 + size])
            break
        pos += 8 + size + (size & 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(array.array("h", (max(-32768, min(32767, int(x * 32767))) for x in samples)).tobytes())


rows = {}   # lang -> id -> (wav, text, seconds); the first speaker of each sentence
for lang, code in LANGS.items():
    tsv = urllib.request.urlopen(BASE + code + "/test.tsv").read().decode()
    rows[lang] = {}
    for r in csv.reader(io.StringIO(tsv), delimiter="\t", quoting=csv.QUOTE_NONE):
        rows[lang].setdefault(r[0], (r[1], r[2], int(r[5]) / 16000))

# sentences of conversation length (3 to 12 s in both languages), the same ones every time
ids = sorted(i for i in rows["de"].keys() & rows["es"].keys()
             if all(3 <= rows[l][i][2] <= 12 for l in LANGS))[:n]

for lang, code in LANGS.items():
    want = {rows[lang][i][0] for i in ids}
    # the audio of all test sentences is one big archive: read it as a stream, keep only ours
    with urllib.request.urlopen(BASE + code + "/audio/test.tar.gz") as r, tarfile.open(fileobj=r, mode="r|gz") as tar:
        for m in tar:
            name = os.path.basename(m.name)
            if m.isfile() and name in want:
                to_pcm16(tar.extractfile(m).read(), os.path.join(out, lang + "_" + name))

with open(os.path.join(out, "whisper.tsv"), "w") as w, open(os.path.join(out, "translate.tsv"), "w") as t, \
        open(os.path.join(out, "refs.tsv"), "w") as refs:
    for i in ids:
        for src, tgt in (("de", "es"), ("es", "de")):
            w.write(f"{src}{i}\t{src}\t{src}_{rows[src][i][0]}\n")
            t.write(f"{src}{i}\t{src}\t{tgt}\t{rows[src][i][1]}\n")
            refs.write(f"{src}{i}\t{src}\t{rows[src][i][1]}\n")
print(f"{len(ids)} sentences in {out}")
