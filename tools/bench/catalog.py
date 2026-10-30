#!/usr/bin/env python3
"""Writes the measured numbers into catalog/models.json.

    catalog.py <fleurs dir> <results dir> <models list>

For each measured model: quality (Whisper: % of words wrong; translator: chrF), time (% of the
speaking time), and from those the precision and speed dots on fixed scales. Models that another
model beats in both quality and speed (not on the Pareto frontier), and translators below chrF 35
(unusable), get "hidden": the app lists them only when they are downloaded already. Measured
models that are not in the catalog yet are added (without a note).
"""
import json, os, re, subprocess, sys

fleurs, results, models = sys.argv[1:4]
path = os.path.join(os.path.dirname(__file__), "../../catalog/models.json")
cat = json.load(open(path))
by_url = {m["url"]: m for m in cat["models"]}

SPEED = [(10, 5), (25, 4), (50, 3), (100, 2)]                 # time % -> dots, else 1
WER = [(5, 5), (8, 4), (12, 3), (20, 2)]                      # % words wrong -> dots, else 1
CHRF = [(49, 5), (47, 4), (44, 3), (38, 2)]                   # chrF -> dots, else 1

def dots(v, scale, higher_better=False):
    for limit, d in scale:
        if (v >= limit if higher_better else v <= limit):
            return d
    return 1

measured = []
for line in open(models):
    name, kind, general, url = line.rstrip("\n").split("\t")
    res = os.path.join(results, f"{kind}-{name}.tsv")
    if not os.path.exists(res):
        continue
    out = subprocess.run([sys.executable, "-I", os.path.join(os.path.dirname(__file__), "score.py"), fleurs, res],
                         capture_output=True, text=True, check=True).stdout.split("\t")
    quality = float(re.sub(r"[^\d.]", "", out[1]))
    time = int(re.sub(r"\D", "", out[2]))
    ram = next((int(l.split("\t")[1]) for l in open(res) if l.startswith("#ram")), None)
    m = by_url.get(url)
    if m is None:
        size = int(subprocess.run(["curl", "-sfIL", url], capture_output=True, text=True).stdout.lower()
                   .split("x-linked-size:")[-1].split()[0])
        m = {"id": url.split("/")[4] + "_" + url.rsplit("/", 1)[1], "kind": kind, "label": name,
             "url": url, "size": size, "general": general == "1", "note": {}}
        cat["models"].append(m)
        by_url[url] = m
    m["quality"], m["time"] = quality, time
    if ram:
        m["ram"] = ram
    good = quality if kind == "translator" else -quality   # higher is better
    m["precision"] = dots(quality, CHRF, True) if kind == "translator" else dots(quality, WER)
    m["speed"] = dots(time, SPEED)
    measured.append((m, good))

for m, good in measured:
    beaten = any(o is not m and o["kind"] == m["kind"] and g >= good and o["time"] <= m["time"]
                 and (g > good or o["time"] < m["time"]) for o, g in measured)
    m["hidden"] = beaten or (m["kind"] == "translator" and m["quality"] < 35)
    if not m["hidden"]:
        m.pop("hidden")

# best to worst per kind: visible ones first, by quality
def order(m):
    q = m.get("quality", 0 if m["kind"] == "translator" else 100)
    return (m["kind"] != "whisper", m.get("hidden", False), -q if m["kind"] == "translator" else q)
cat["models"].sort(key=order)
json.dump(cat, open(path, "w"), ensure_ascii=False, indent=2)
open(path, "a").write("\n")
for m in cat["models"]:
    print(m["kind"][:3], "hidden" if m.get("hidden") else "SHOWN ", m.get("quality"), m.get("time"), m["precision"], m["speed"], m["label"])
