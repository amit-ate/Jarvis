#!/usr/bin/env python3
"""Word error rate of a sherpa-onnx transducer folder on a NeMo manifest, decoded exactly as on the phone (int8)."""
import argparse, json, re, wave

import jiwer
import numpy as np
import sherpa_onnx

NUM = {"zero": "0", "one": "1", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6", "seven": "7",
       "eight": "8", "nine": "9", "ten": "10"}
SPELL = {"favourite": "favorite", "colour": "color", "kilometres": "kilometers"}
JOIN = {"wi fi": "wifi", "wi-fi": "wifi"}


def norm(t):
    t = t.lower()
    for a, b in JOIN.items():
        t = t.replace(a, b)
    t = re.sub(r"[^a-z0-9' ]", " ", t.lower().replace("’", "'"))
    return " ".join(NUM.get(w, SPELL.get(w, w)) for w in t.split())


def load(path):
    with wave.open(path) as f:
        return np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def recognizer(model_dir):
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{model_dir}/encoder.int8.onnx", decoder=f"{model_dir}/decoder.int8.onnx",
        joiner=f"{model_dir}/joiner.int8.onnx", tokens=f"{model_dir}/tokens.txt",
        num_threads=4, model_type="nemo_transducer")


class Checker:
    """Transcribes one recording and scores it against its label."""

    def __init__(self, model_dir):
        self.r = recognizer(model_dir)

    def check(self, wav, text):
        s = self.r.create_stream()
        s.accept_waveform(16000, load(wav))
        self.r.decode_stream(s)
        heard = s.result.text
        return heard, jiwer.wer(norm(text), norm(heard) or "<none>")


def wer(model_dir, manifest, show=False):
    r = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{model_dir}/encoder.int8.onnx", decoder=f"{model_dir}/decoder.int8.onnx",
        joiner=f"{model_dir}/joiner.int8.onnx", tokens=f"{model_dir}/tokens.txt",
        num_threads=4, model_type="nemo_transducer")
    refs, hyps = [], []
    for line in open(manifest):
        if not line.strip():
            continue
        o = json.loads(line)
        s = r.create_stream()
        s.accept_waveform(16000, load(o["audio_filepath"]))
        r.decode_stream(s)
        refs.append(norm(o["text"]))
        hyps.append(norm(s.result.text))
        if show and refs[-1] != hyps[-1]:
            print(f"  ref: {o['text']}\n  hyp: {s.result.text}")
    return jiwer.wer(refs, hyps) if refs else None, len(refs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model_dir")
    ap.add_argument("manifest")
    ap.add_argument("--show", action="store_true")
    a = ap.parse_args()
    w, n = wer(a.model_dir, a.manifest, a.show)
    print(json.dumps({"model": a.model_dir, "n": n, "wer": None if w is None else round(w, 4)}))


if __name__ == "__main__":
    main()
