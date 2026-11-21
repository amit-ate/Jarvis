"""Token-and-Duration Transducer (TDT) loss that runs on the CPU.

NeMo's fast TDT loss is CUDA-only and its pure-PyTorch reference loops over every (batch, frame, token) in Python and
calls .cuda(). This computes the same forward variable (Equation 7 of https://arxiv.org/abs/2304.06795) with a loop over
frames, vectorised over batch and tokens: plenty for fine-tuning on a few minutes of the user's recordings.
"""
from typing import List

import torch

NEG = -1.0e4


class CpuTDTLoss(torch.nn.Module):
    def __init__(self, blank: int, durations: List[int], sigma: float = 0.0):
        super().__init__()
        self.blank = blank
        self.durations = list(durations)
        self.sigma = sigma
        self.reduction = None  # set/cleared by NeMo's RNNTLoss wrapper; per-utterance losses are returned

    def forward(self, acts, labels, act_lens, label_lens):
        n_dur = len(self.durations)
        lab = torch.log_softmax(acts[..., :-n_dur], -1) - self.sigma
        dur = torch.log_softmax(acts[..., -n_dur:], -1)
        B, T, U1, _ = lab.shape
        blank_lp = lab[..., self.blank]  # B, T, U+1
        U = U1 - 1
        tgt = labels[:, :U].long().clamp(min=0)
        emit_lp = lab[:, :, :U, :].gather(-1, tgt[:, None, :, None].expand(B, T, U, 1)).squeeze(-1)  # B, T, U
        neg_col = acts.new_full((B, 1), NEG)
        alphas = []
        for t in range(T):
            terms = []
            for n, l in enumerate(self.durations):
                if l == 0 or t - l < 0:
                    continue
                a = alphas[t - l]
                terms.append(a + blank_lp[:, t - l, :] + dur[:, t - l, :, n])
                if U > 0:
                    terms.append(torch.cat([neg_col, a[:, :-1] + emit_lp[:, t - l, :] + dur[:, t - l, :-1, n]], 1))
            if t == 0:
                base = torch.cat([acts.new_zeros((B, 1)), acts.new_full((B, U), NEG)], 1)
            elif terms:
                base = torch.logsumexp(torch.stack(terms), 0)
            else:
                base = acts.new_full((B, U1), NEG)
            if 0 in self.durations and U > 0:
                n0 = self.durations.index(0)
                cols = [base[:, 0]]
                for u in range(1, U1):
                    cols.append(torch.logaddexp(base[:, u], cols[-1] + emit_lp[:, t, u - 1] + dur[:, t, u - 1, n0]))
                base = torch.stack(cols, 1)
            alphas.append(base)
        alpha = torch.stack(alphas, 1)  # B, T, U+1
        losses = []
        for b in range(B):
            tb, ub = int(act_lens[b]), int(label_lens[b])
            fin = [alpha[b, tb - l, ub] + blank_lp[b, tb - l, ub] + dur[b, tb - l, ub, n]
                   for n, l in enumerate(self.durations) if l > 0 and tb - l >= 0]
            losses.append(-torch.logsumexp(torch.stack(fin), 0))
        return torch.stack(losses)


def install(model):
    """Swaps a NeMo TDT model's loss for [CpuTDTLoss] so it can be fine-tuned without an NVIDIA GPU."""
    from nemo.collections.asr.losses.rnnt import RNNTLoss

    _, kw = model.extract_rnnt_loss_cfg(model.cfg.get("loss", None))
    blank = model.joint.num_classes_with_blank - 1 - model.joint.num_extra_outputs
    loss = RNNTLoss(num_classes=blank, loss_name="tdt_pytorch",
                    loss_kwargs={"durations": list(kw["durations"]), "sigma": kw.get("sigma", 0.0)},
                    reduction=model.cfg.get("rnnt_reduction", "mean_batch"))
    loss._loss = CpuTDTLoss(blank=blank, durations=list(kw["durations"]), sigma=kw.get("sigma", 0.0))
    model.loss = loss
    if model.joint.fuse_loss_wer:
        model.joint.set_loss(loss)
