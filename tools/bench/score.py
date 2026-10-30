#!/usr/bin/env python3
"""Scores bench results against the FLEURS references.

    score.py <fleurs dir> <result.tsv>...

Whisper results: WER (share of words wrong; lower is better), after the same filters the app
uses (Engine.java): whisper sometimes repeats sentences, and the app drops those. Translator results: chrF
(character n-gram F-score, 0-100; higher is better). Also the median time per sentence.
"""
import os, re, statistics, sys
from collections import Counter

refs = {}
for line in open(os.path.join(sys.argv[1], "refs.tsv")):
    i, lang, text = line.rstrip("\n").split("\t")
    refs[i] = text

def words(s):
    return re.sub(r"[^\w\s]", " ", s.lower()).split()

def edits(a, b):
    d = list(range(len(b) + 1))
    for x in a:
        prev, d[0] = d[0], d[0] + 1
        for j, y in enumerate(b, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (x != y))
    return d[-1]

def chrf(hyp, ref, n=6, beta=2):
    # as sacrebleu's chrF: character n-grams without spaces, averaged over n = 1..6
    h, r = hyp.replace(" ", ""), ref.replace(" ", "")
    p = rc = 0
    for k in range(1, n + 1):
        hc = Counter(h[i:i + k] for i in range(len(h) - k + 1))
        rcn = Counter(r[i:i + k] for i in range(len(r) - k + 1))
        m = sum((hc & rcn).values())
        p += m / max(sum(hc.values()), 1)
        rc += m / max(sum(rcn.values()), 1)
    p, rc = p / n, rc / n
    return 0 if p + rc == 0 else 100 * (1 + beta ** 2) * p * rc / (beta ** 2 * p + rc)

def norm(t):
    return re.sub(r"[^\w]+", " ", t).strip().lower()

def junk(t):
    s, l = re.sub(r"[^\w]", "", t).lower(), t.lower()
    w = [x for x in re.split(r"[^\w]+", l) if x]
    return (not s or (len(w) >= 6 and len(set(w)) * 3 < len(w)) or s in ("musik", "música")
            or any(k in l for k in ("untertitel", "amara.org", "zdf", "subtítulos", "vielen dank fürs zuschauen",
                                    "gracias por ver", "thanks for watching")))

def app_text(raw):
    """The text the app keeps from whisper's "<end ms>\t<word>" lines (Engine.segments)."""
    lines = [l.split("\t", 1) for l in raw.split("\x1e") if "\t" in l]
    segs, sentence, n, last = [], "", 0, ""
    for i, (end, word) in enumerate(lines):
        if not word.strip():
            continue
        sentence += word
        n += 1
        w = word.strip()
        if not (re.search(r"[.?!…]$", w) or (n >= 12 and w.endswith(",")) or i == len(lines) - 1):
            continue
        text = re.sub(r"(.{8,}?)(?:\s*\1)+", r"\1", sentence.strip())
        sentence, n = "", 0
        if junk(text) or norm(text) == last:
            continue
        last = norm(text)
        segs.append((int(end), text))
    said, kept, prev = set(), [], 0
    for end, text in segs:
        ms, prev = max(1, end - prev), end
        if norm(text) in said:
            continue
        said.add(norm(text))
        if len(text) > 12 and len(text) * 1000 / ms > 30:
            continue
        kept.append(text)
    return " ".join(kept)

other = {"de": "es", "es": "de"}
for path in sys.argv[2:]:
    rows = [l.rstrip("\n").split("\t") for l in open(path) if l.count("\t") >= 2]
    if not rows:
        continue
    ms = statistics.median(float(r[1]) for r in rows)
    name = os.path.basename(path).removesuffix(".tsv")
    if name.startswith("whisper"):
        hyp = {r[0]: app_text(r[3]) if len(r) > 3 else r[2] for r in rows}
        err = sum(edits(words(hyp[r[0]]), words(refs[r[0]])) for r in rows)
        total = sum(len(words(refs[r[0]])) for r in rows)
        print(f"{name}\tWER {100 * err / total:.1f}%\t{ms / 1000:.2f} s\t{len(rows)}")
    else:
        # "de123" was translated from German: its reference is the Spanish "es123"
        score = statistics.mean(chrf(r[2], refs[other[r[0][:2]] + r[0][2:]]) for r in rows)
        print(f"{name}\tchrF {score:.1f}\t{ms / 1000:.2f} s\t{len(rows)}")
