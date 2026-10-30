# Model benchmark

Measures the Whisper models and the translators on a phone, with the app's own native code
(`bench.cpp` includes `app/src/main/cpp/native.cpp`). The precision and speed dots in
`catalog/models.json` come from these numbers.

Test sentences: [FLEURS](https://huggingface.co/datasets/google/fleurs), the same sentences read
aloud in many languages. I use 50 German and Spanish ones of 3 to 12 seconds.

- Whisper: each recording is transcribed; WER (share of words wrong, lower is better).
- Translators: each German sentence is translated to Spanish and each Spanish one to German;
  chrF (how many character groups match the reference translation, 0-100, higher is better).
- Time: as a share of how long the sentences take to say (50% = done in half the speaking time). For translators without the instruction part, which the app
  has cached.

```sh
tools/bench/fleurs.py /tmp/fleurs 50          # the test sentences (downloads ~1.2 GB, keeps 25 MB)
tools/bench/run.sh /tmp/fleurs /tmp/results models.tsv
tools/bench/score.py /tmp/fleurs /tmp/results/*.tsv
```

`models.tsv` lines: name, `whisper` or `translator`, general model 0/1, URL. `run.sh` copies one
model at a time to the phone (nothing is stored on the computer), waits for the phone to cool
down, and skips models that have a result already.
