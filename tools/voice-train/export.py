#!/usr/bin/env python3
"""Export a fine-tuned Parakeet .nemo to the sherpa-onnx transducer files Jarvis loads (models/asr-custom).

Same steps as sherpa-onnx's scripts/nemo/fast-conformer-hybrid-transducer-ctc/export-onnx-transducer-non-streaming.py,
reading a local .nemo instead of downloading a pretrained name.
"""
import argparse, os

import nemo.collections.asr as nemo_asr
import onnx
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic


def add_meta(filename, meta):
    m = onnx.load(filename)
    while len(m.metadata_props):
        m.metadata_props.pop()
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key, p.value = k, str(v)
    onnx.save(m, filename)


@torch.no_grad()
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("nemo")
    ap.add_argument("out_dir")
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)
    model = nemo_asr.models.ASRModel.restore_from(a.nemo, map_location="cpu")
    with open(os.path.join(a.out_dir, "tokens.txt"), "w", encoding="utf-8") as f:
        for i, s in enumerate(model.joint.vocabulary):
            f.write(f"{s} {i}\n")
        f.write(f"<blk> {i + 1}\n")
    model.change_decoding_strategy(decoder_type="rnnt")
    model.eval()
    model.set_export_config({"decoder_type": "rnnt"})
    tmp = {m: os.path.join(a.out_dir, f"{m}.fp32.onnx") for m in ("encoder", "decoder", "joiner")}
    model.encoder.export(tmp["encoder"])
    model.decoder.export(tmp["decoder"])
    model.joint.export(tmp["joiner"])
    normalize = model.cfg.preprocessor.normalize
    add_meta(tmp["encoder"], {
        "vocab_size": model.decoder.vocab_size,
        "normalize_type": "" if normalize == "NA" else normalize,
        "pred_rnn_layers": model.decoder.pred_rnn_layers,
        "pred_hidden": model.decoder.pred_hidden,
        "subsampling_factor": 8,
        "model_type": "EncDecHybridRNNTCTCBPEModel",
        "version": "1",
        "model_author": "NeMo",
        # sherpa-onnx treats the model as TDT (token-and-duration transducer) only when the url contains "tdt".
        "url": "https://huggingface.co/nvidia/parakeet-tdt_ctc-110m",
        "comment": "parakeet-tdt_ctc-110m fine-tuned on the user's voice (Jarvis tools/voice-train)",
    })
    for m, path in tmp.items():
        quantize_dynamic(model_input=path, model_output=os.path.join(a.out_dir, f"{m}.int8.onnx"),
                         weight_type=QuantType.QUInt8)
        os.remove(path)
    for leftover in os.listdir(a.out_dir):
        if leftover.endswith(".data") or leftover.endswith(".onnx.data"):
            os.remove(os.path.join(a.out_dir, leftover))
    print("exported to", a.out_dir, sorted(os.listdir(a.out_dir)))


if __name__ == "__main__":
    main()
