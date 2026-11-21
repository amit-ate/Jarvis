#!/usr/bin/env python3
"""Fine-tune NVIDIA Parakeet TDT-CTC 110M (the recogniser Jarvis runs) on the user's recordings.

Small data, so: low learning rate (1e-5), 10 epochs, SpecAugment kept on and BatchNorm statistics frozen. On 79
recordings (two voices, 3.7 min) this took held-out sentences from 7.6% to 0% word errors and other voices from 1.7% to
1.2%; training only the top 6 encoder layers did not help the held-out sentences. The result
is only installed if evaluate.py shows it beats the stock model on held-out sentences.
"""
import argparse, os, sys

import lightning.pytorch as pl
import nemo.collections.asr as nemo_asr
import torch
from omegaconf import open_dict

BASE = "nvidia/parakeet-tdt_ctc-110m"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data", help="folder with train.json and test.json (prepare.py)")
    ap.add_argument("out", help="output .nemo file")
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--lr", type=float, default=1e-5)
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--train-top-layers", type=int, default=17,
                    help="encoder layers (from the top) left trainable; the rest stay frozen")
    a = ap.parse_args()

    torch.set_num_threads(max(1, os.cpu_count() - 2))
    model = nemo_asr.models.ASRModel.from_pretrained(BASE, map_location="cpu")
    if not torch.cuda.is_available():
        # NeMo's TDT loss needs an NVIDIA GPU; tdt_loss.py computes the same loss on the CPU.
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import tdt_loss
        tdt_loss.install(model)

    with open_dict(model.cfg):
        for split, path, shuffle in (("train_ds", "train.json", True), ("validation_ds", "test.json", False)):
            ds = model.cfg[split]
            ds.manifest_filepath = os.path.join(a.data, path)
            ds.batch_size = a.batch
            ds.shuffle = shuffle
            ds.num_workers = 0
            ds.pin_memory = False
            ds.is_tarred = False
            ds.tarred_audio_filepaths = None
            ds.max_duration = 30.0
            ds.min_duration = 0.1
            ds.use_lhotse = False
            ds.use_bucketing = False
            ds.bucketing_strategy = None
            ds.shard_manifests = False
            ds.defer_setup = False
            ds.batch_duration = None
        model.cfg.optim.name = "adamw"
        model.cfg.optim.lr = a.lr
        model.cfg.optim.weight_decay = 1e-3
        model.cfg.optim.sched = {"name": "CosineAnnealing", "warmup_steps": 5, "min_lr": a.lr / 10}

    model.setup_training_data(model.cfg.train_ds)
    model.setup_multiple_validation_data(model.cfg.validation_ds)
    model.setup_optimization(model.cfg.optim)

    layers = model.encoder.layers
    keep = max(0, len(layers) - a.train_top_layers)
    for p in model.encoder.pre_encode.parameters():
        p.requires_grad = False
    for layer in layers[:keep]:
        for p in layer.parameters():
            p.requires_grad = False
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"trainable params: {trainable/1e6:.1f}M of {sum(p.numel() for p in model.parameters())/1e6:.1f}M")

    class FreezeNormStats(pl.Callback):
        """Keeps BatchNorm running statistics fixed: a few minutes of padded audio would otherwise shift them for
        every layer, frozen ones included, and the whole model gets worse."""

        def on_train_epoch_start(self, trainer, pl_module):
            for mod in pl_module.modules():
                if isinstance(mod, torch.nn.modules.batchnorm._BatchNorm):
                    mod.eval()

    trainer = pl.Trainer(callbacks=[FreezeNormStats()], accelerator="cpu", devices=1, max_epochs=a.epochs, logger=False,
                         enable_checkpointing=False, log_every_n_steps=5, num_sanity_val_steps=0,
                         gradient_clip_val=1.0)
    model.set_trainer(trainer)
    trainer.fit(model)
    model.save_to(a.out)
    print("saved", a.out)


if __name__ == "__main__":
    main()
