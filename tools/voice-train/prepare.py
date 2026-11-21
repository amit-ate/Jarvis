#!/usr/bin/env python3
"""Turn the phone's voice_samples folder into NeMo train/test manifests.

Read-aloud ("enroll") recordings have exact labels and are used for training and testing; whole sentences are held
out (every Nth distinct sentence) so the test measures new words, not memorised ones. Request ("turn") recordings are
labelled with what the recogniser heard, so they are only used with --use-turns.
"""
import argparse, hashlib, json, os, wave


def duration(path):
    with wave.open(path) as f:
        return f.getnframes() / f.getframerate()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("samples", help="folder pulled from /sdcard/Jarvis/voice_samples")
    ap.add_argument("out", help="output folder for train.json / test.json")
    ap.add_argument("--test-every", type=int, default=5, help="hold out 1 in N distinct sentences")
    ap.add_argument("--use-turns", action="store_true", help="also train on request recordings (unverified labels)")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rows = [json.loads(l) for l in open(os.path.join(a.samples, "manifest.jsonl")) if l.strip()]
    train, test = [], []
    for r in rows:
        wav = os.path.abspath(os.path.join(a.samples, r["wav"]))
        if not os.path.isfile(wav):
            continue
        item = {"audio_filepath": wav, "duration": round(duration(wav), 3), "text": r["text"]}
        if r["source"] == "enroll":
            bucket = int(hashlib.md5(r["text"].lower().encode()).hexdigest(), 16) % a.test_every
            (test if bucket == 0 else train).append(item)
        elif a.use_turns:
            train.append(item)
    for name, items in (("train", train), ("test", test)):
        with open(os.path.join(a.out, name + ".json"), "w") as f:
            for it in items:
                f.write(json.dumps(it) + "\n")
    print(json.dumps({"train": len(train), "test": len(test),
                      "train_minutes": round(sum(i["duration"] for i in train) / 60, 1)}))


if __name__ == "__main__":
    main()
