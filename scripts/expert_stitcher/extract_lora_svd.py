#!/usr/bin/env python3
"""Offline SVD task-delta LoRA extractor, GGUF compatibility checker, and llama.cpp command emitter.

Core mathematical decomposition:
    Delta W = W_specialist - W_base ~= (U_r * sqrt(S_r)) @ (sqrt(S_r) * Vt_r) = A @ B
where A in R^{m x r} and B in R^{r x n} yield a rank-r LoRA adapter directly
loadable via llama_adapter_lora_init / llama_set_adapters_lora.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
from typing import Any, Dict, List, Optional, Sequence, Tuple

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from inspect_gguf import parse_gguf  # noqa: E402

try:
    import numpy as np  # type: ignore

    HAS_NUMPY = True
except ImportError:
    np = None  # type: ignore
    HAS_NUMPY = False


def _mat_mul(a: List[List[float]], b: List[List[float]]) -> List[List[float]]:
    rows = len(a)
    inner = len(a[0]) if rows else 0
    cols = len(b[0]) if b else 0
    out = [[0.0] * cols for _ in range(rows)]
    for i in range(rows):
        ai = a[i]
        oi = out[i]
        for k in range(inner):
            aik = ai[k]
            if aik == 0.0:
                continue
            bk = b[k]
            for j in range(cols):
                oi[j] += aik * bk[j]
    return out


def _mat_vec(a: List[List[float]], v: List[float]) -> List[float]:
    return [sum(aij * vj for aij, vj in zip(row, v)) for row in a]


def _mat_t_vec(a: List[List[float]], u: List[float]) -> List[float]:
    cols = len(a[0]) if a else 0
    out = [0.0] * cols
    for row, ui in zip(a, u):
        for j, aij in enumerate(row):
            out[j] += aij * ui
    return out


def _frobenius_norm_sq(a: List[List[float]]) -> float:
    return sum(x * x for row in a for x in row)


def _pure_python_truncated_svd(
    delta: List[List[float]], rank: int, power_iters: int = 40
) -> Tuple[List[List[float]], List[float], List[List[float]], float]:
    """Pure-stdlib truncated SVD via deflated power iteration on Delta^T Delta."""
    m = len(delta)
    n = len(delta[0]) if m else 0
    k_max = min(rank, m, n)
    residual = [list(row) for row in delta]
    rng = random.Random(42)

    u_cols: List[List[float]] = []
    s_vals: List[float] = []
    vt_rows: List[List[float]] = []

    for _ in range(k_max):
        v = [rng.gauss(0.0, 1.0) for _ in range(n)]
        v_norm = math.sqrt(sum(x * x for x in v)) or 1.0
        v = [x / v_norm for x in v]

        for _ in range(power_iters):
            u = _mat_vec(residual, v)
            u_norm = math.sqrt(sum(x * x for x in u))
            if u_norm < 1e-12:
                break
            u = [x / u_norm for x in u]
            v = _mat_t_vec(residual, u)
            v_norm = math.sqrt(sum(x * x for x in v))
            if v_norm < 1e-12:
                break
            v = [x / v_norm for x in v]

        u = _mat_vec(residual, v)
        sigma = math.sqrt(sum(x * x for x in u))
        if sigma < 1e-12:
            break
        u = [x / sigma for x in u]

        u_cols.append(u)
        s_vals.append(sigma)
        vt_rows.append(v)

        # Deflate: residual -= sigma * u * v^T
        for i in range(m):
            si_ui = sigma * u[i]
            ri = residual[i]
            for j in range(n):
                ri[j] -= si_ui * v[j]

    # Convert U columns (r x m) to m x r
    r_actual = len(s_vals)
    u_mat = [[u_cols[c][i] for c in range(r_actual)] for i in range(m)]
    total_energy = _frobenius_norm_sq(delta)
    return u_mat, s_vals, vt_rows, total_energy


def extract_lora_from_delta_pure(
    delta: List[List[float]], rank: int
) -> Dict[str, Any]:
    """Extract rank-r LoRA (A, B) from a 2D delta matrix using pure Python stdlib."""
    u_mat, s_vals, vt_rows, total_energy = _pure_python_truncated_svd(delta, rank)
    m = len(delta)
    n = len(delta[0]) if m else 0
    r = len(s_vals)
    sqrt_s = [math.sqrt(max(s, 0.0)) for s in s_vals]

    # A = U_r * sqrt(S_r) [m x r], B = sqrt(S_r) * Vt_r [r x n]
    a_mat = [[u_mat[i][k] * sqrt_s[k] for k in range(r)] for i in range(m)]
    b_mat = [[sqrt_s[k] * vt_rows[k][j] for j in range(n)] for k in range(r)]

    approx = _mat_mul(a_mat, b_mat)
    err_sq = sum(
        (delta[i][j] - approx[i][j]) ** 2 for i in range(m) for j in range(n)
    )
    rel_error = math.sqrt(err_sq / total_energy) if total_energy > 0.0 else 0.0
    captured_energy = sum(s * s for s in s_vals)
    explained_variance_ratio = (
        min(1.0, captured_energy / total_energy) if total_energy > 0.0 else 1.0
    )

    return {
        "backend": "python-stdlib",
        "shape": [m, n],
        "rank": r,
        "singular_values": [round(s, 6) for s in s_vals],
        "relative_frobenius_error": rel_error,
        "explained_variance_ratio": explained_variance_ratio,
        "A": a_mat,
        "B": b_mat,
    }


def extract_lora_from_delta_numpy(delta_arr: Any, rank: int) -> Dict[str, Any]:
    """Extract rank-r LoRA (A, B) from a 2D numpy weight delta matrix."""
    if not HAS_NUMPY:
        raise RuntimeError(
            "numpy is required to extract SVD LoRA from real .npy/.npz weight arrays. "
            "Install numpy (`pip install numpy`) or run `--self-test` / `--inspect-tensors`."
        )
    w = np.asarray(delta_arr, dtype=np.float32)
    if w.ndim != 2:
        raise ValueError(f"Expected 2D weight matrix, got shape {w.shape}")
    u, s, vt = np.linalg.svd(w, full_matrices=False)
    r = min(rank, s.shape[0])
    sqrt_s = np.sqrt(np.maximum(s[:r], 0.0))
    a_mat = u[:, :r] * sqrt_s[None, :]
    b_mat = sqrt_s[:, None] * vt[:r, :]

    approx = a_mat @ b_mat
    total_norm = float(np.linalg.norm(w, ord="fro"))
    err_norm = float(np.linalg.norm(w - approx, ord="fro"))
    rel_error = (err_norm / total_norm) if total_norm > 0.0 else 0.0
    total_energy = float(np.sum(s.astype(np.float64) ** 2))
    explained = (
        float(np.sum(s[:r].astype(np.float64) ** 2) / total_energy)
        if total_energy > 0.0
        else 1.0
    )

    return {
        "backend": f"numpy-{np.__version__}",
        "shape": [int(w.shape[0]), int(w.shape[1])],
        "rank": int(r),
        "singular_values": [round(float(x), 6) for x in s[:r]],
        "relative_frobenius_error": rel_error,
        "explained_variance_ratio": min(1.0, explained),
        "A": a_mat.astype(np.float16),
        "B": b_mat.astype(np.float16),
    }


def inspect_gguf_compatibility(base_path: str, expert_path: str) -> Dict[str, Any]:
    """Compare tensor geometry & architecture compatibility between two GGUF models."""
    base = parse_gguf(base_path)
    expert = parse_gguf(expert_path)

    reasons: List[str] = []
    checks = [
        ("general_architecture", "Residual stream / layer math family differs"),
        ("block_count", "Transformer depth L1 != L2"),
        ("embedding_dim", "Residual stream hidden width d_model mismatch (cannot subtract W_exp - W_base)"),
        ("attention_head_count", "Attention head projection geometry differs"),
        ("attention_kv_head_count", "GQA KV-head projection geometry differs"),
        ("vocab_size", "Embedding / LM-head vocabulary size V1 != V2"),
    ]
    for field, explanation in checks:
        bv = base.get(field)
        ev = expert.get(field)
        if bv != ev:
            reasons.append(f"{field}: base={bv!r} vs expert={ev!r} -- {explanation}")

    base_tensors = {t["name"]: t["dims"] for t in base["tensors"]}
    expert_tensors = {t["name"]: t["dims"] for t in expert["tensors"]}

    shared = sorted(set(base_tensors) & set(expert_tensors))
    shape_mismatches: List[str] = []
    for name in shared:
        if base_tensors[name] != expert_tensors[name]:
            shape_mismatches.append(f"{name}: base={base_tensors[name]} vs expert={expert_tensors[name]}")

    only_base = sorted(set(base_tensors) - set(expert_tensors))
    only_expert = sorted(set(expert_tensors) - set(base_tensors))

    if shape_mismatches:
        reasons.append(
            f"{len(shape_mismatches)} shared tensor(s) have incompatible dimensions (e.g. {shape_mismatches[0]})"
        )

    compatible = len(reasons) == 0 and len(shared) > 0
    math_note = (
        "COMPATIBLE: Identical architecture, residual stream basis d_model, layer count, and tensor shapes. "
        "Delta W = W_expert - W_base is algebraically well-defined for SVD LoRA extraction."
        if compatible
        else "INCOMPATIBLE: Cross-architecture or cross-shape weight subtraction/stitching is mathematically invalid "
        "because independently trained models occupy orthogonal residual-stream bases (R^d1 vs R^d2) and "
        "incompatible token spaces; Delta W = W_expert - W_base is undefined without a learned linear alignment map."
    )

    return {
        "compatible": compatible,
        "base_model": base["general_name"],
        "base_arch": base["general_architecture"],
        "expert_model": expert["general_name"],
        "expert_arch": expert["general_architecture"],
        "shared_tensor_count": len(shared),
        "only_in_base_count": len(only_base),
        "only_in_expert_count": len(only_expert),
        "shape_mismatches": shape_mismatches[:20],
        "reasons": reasons,
        "mathematical_explanation": math_note,
    }


def emit_llama_commands(
    base_model: str = "/path/to/Jarvis/models/llm/qwen2.5-0.5b-instruct-q4_0.gguf",
    lora_path: str = "/sdcard/Jarvis/models/adapters/tool_use_r8.gguf",
    merged_out: str = "bench/out/qwen2.5-0.5b-tool-merged-f16.gguf",
    cvec_out: str = "bench/out/cvector_concise_direct.gguf",
) -> str:
    """Emit ready-to-run commands referencing this repo's bench/out/tools/ binaries."""
    lines = [
        "# === Jarvis llama.cpp LoRA Export & Control Vector Commands ===",
        "# Build targets (configured in bench/out/tools/export-lora and bench/out/tools/cvector-generator):",
        "cmake --build bench/out --target llama-export-lora llama-cvector-generator -j4",
        "",
        "# 1. Merge one or more SVD-extracted GGUF LoRA adapters into a base model (bench/out/tools/export-lora):",
        f"./bench/out/bin/llama-export-lora \\",
        f"  -m {base_model} \\",
        f"  --lora-scaled {lora_path}:0.85 \\",
        f"  -o {merged_out}",
        "",
        "# 2. Generate a zero-RAM-overhead behavioral Control Vector (bench/out/tools/cvector-generator):",
        "#    (e.g., anti-echo / concise-assistant steering across layers 8..22)",
        f"./bench/out/bin/llama-cvector-generator \\",
        f"  -m {base_model} \\",
        f"  --positive-file third_party/llama.cpp/tools/cvector-generator/positive.txt \\",
        f"  --negative-file third_party/llama.cpp/tools/cvector-generator/negative.txt \\",
        f"  --pca-iter 2000 --pca-batch 100 \\",
        f"  -o {cvec_out}",
        "",
        "# 3. Test generated control vector with llama-cli / Jarvis JNI (llama_set_adapter_cvec):",
        f"./bench/out/bin/llama-cli -m {base_model} \\",
        f"  --control-vector-scaled {cvec_out} 0.8 \\",
        f"  --control-vector-layer-range 8 22 \\",
        f"  -p \"<|im_start|>user\\nWhat is 17 times 4?<|im_end|>\\n<|im_start|>assistant\\n\"",
    ]
    return "\n".join(lines)


def run_self_test() -> int:
    rng = random.Random(1337)
    rows, cols, true_rank, extract_rank = 48, 32, 4, 4

    w_base = [[rng.gauss(0.0, 0.25) for _ in range(cols)] for _ in range(rows)]
    u_true = [[rng.gauss(0.0, 1.0) for _ in range(true_rank)] for _ in range(rows)]
    v_true = [[rng.gauss(0.0, 1.0) for _ in range(cols)] for _ in range(true_rank)]
    delta_true = _mat_mul(u_true, v_true)
    # Add tiny orthogonal perturbation (~1%)
    delta_noisy = [
        [delta_true[i][j] + rng.gauss(0.0, 0.01) for j in range(cols)]
        for i in range(rows)
    ]
    w_spec = [
        [w_base[i][j] + delta_noisy[i][j] for j in range(cols)]
        for i in range(rows)
    ]
    delta_obs = [
        [w_spec[i][j] - w_base[i][j] for j in range(cols)]
        for i in range(rows)
    ]

    pure_res = extract_lora_from_delta_pure(delta_obs, rank=extract_rank)
    assert pure_res["shape"] == [rows, cols], f"Bad shape: {pure_res['shape']}"
    assert pure_res["rank"] == extract_rank, f"Bad rank: {pure_res['rank']}"
    assert pure_res["relative_frobenius_error"] < 0.05, (
        f"Reconstruction error too high: {pure_res['relative_frobenius_error']}"
    )
    assert pure_res["explained_variance_ratio"] > 0.99, (
        f"Explained variance too low: {pure_res['explained_variance_ratio']}"
    )

    summary: Dict[str, Any] = {
        "self_test": "PASSED",
        "pure_stdlib_svd": {
            "shape": pure_res["shape"],
            "rank": pure_res["rank"],
            "singular_values": pure_res["singular_values"],
            "relative_frobenius_error": round(pure_res["relative_frobenius_error"], 6),
            "explained_variance_ratio": round(pure_res["explained_variance_ratio"], 6),
            "lora_param_count": rows * extract_rank + extract_rank * cols,
            "full_delta_param_count": rows * cols,
            "compression_ratio": round((rows * cols) / (rows * extract_rank + extract_rank * cols), 2),
        },
    }

    if HAS_NUMPY:
        np_res = extract_lora_from_delta_numpy(np.array(delta_obs, dtype=np.float32), rank=extract_rank)
        assert np_res["relative_frobenius_error"] < 0.05
        assert np_res["explained_variance_ratio"] > 0.99
        summary["numpy_svd"] = {
            "backend": np_res["backend"],
            "shape": np_res["shape"],
            "rank": np_res["rank"],
            "relative_frobenius_error": round(np_res["relative_frobenius_error"], 6),
            "explained_variance_ratio": round(np_res["explained_variance_ratio"], 6),
        }

    print(json.dumps(summary, indent=2))
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Reverse-engineer rank-r LoRA adapters via SVD on task deltas (W_specialist - W_base)."
    )
    parser.add_argument("--self-test", action="store_true", help="Run synthetic SVD extraction & error validation")
    parser.add_argument(
        "--inspect-tensors",
        nargs=2,
        metavar=("BASE_GGUF", "EXPERT_GGUF"),
        help="Check tensor shape and architecture compatibility between two GGUF models",
    )
    parser.add_argument(
        "--emit-llama-cmds",
        action="store_true",
        help="Print ready-to-run llama.cpp export-lora and cvector-generator commands",
    )
    parser.add_argument("--base", help="Base weights (.npy or .npz) for real SVD extraction (requires numpy)")
    parser.add_argument("--expert", help="Specialist weights (.npy or .npz) for real SVD extraction (requires numpy)")
    parser.add_argument("--rank", type=int, default=8, help="Target LoRA rank r (default: 8)")
    parser.add_argument("--out", help="Output .npz path for extracted (lora_A, lora_B) matrices")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    if args.inspect_tensors:
        report = inspect_gguf_compatibility(args.inspect_tensors[0], args.inspect_tensors[1])
        print(json.dumps(report, indent=2))
        return 0 if report["compatible"] else 2

    if args.emit_llama_cmds:
        print(emit_llama_commands())
        return 0

    if args.base and args.expert:
        if not HAS_NUMPY:
            print(
                "ERROR: numpy is not installed. Real .npy/.npz tensor extraction requires numpy; "
                "use --self-test or --inspect-tensors for pure-stdlib operation.",
                file=sys.stderr,
            )
            return 1
        base_obj = np.load(args.base)
        exp_obj = np.load(args.expert)
        if isinstance(base_obj, np.lib.npyio.NpzFile) and isinstance(exp_obj, np.lib.npyio.NpzFile):
            out_arrays: Dict[str, Any] = {}
            summary_layers: Dict[str, Any] = {}
            for key in sorted(set(base_obj.files) & set(exp_obj.files)):
                b_arr = base_obj[key]
                e_arr = exp_obj[key]
                if b_arr.shape != e_arr.shape or b_arr.ndim != 2:
                    continue
                res = extract_lora_from_delta_numpy(e_arr - b_arr, rank=args.rank)
                out_arrays[f"{key}.lora_A"] = res["A"]
                out_arrays[f"{key}.lora_B"] = res["B"]
                summary_layers[key] = {
                    "shape": res["shape"],
                    "rank": res["rank"],
                    "relative_frobenius_error": round(res["relative_frobenius_error"], 6),
                    "explained_variance_ratio": round(res["explained_variance_ratio"], 6),
                }
            if args.out:
                os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
                np.savez(args.out, **out_arrays)
            print(json.dumps({"layers": summary_layers, "out": args.out}, indent=2))
            return 0
        res = extract_lora_from_delta_numpy(exp_obj - base_obj, rank=args.rank)
        if args.out:
            os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
            np.savez(args.out, lora_A=res["A"], lora_B=res["B"])
        res_out = {k: v for k, v in res.items() if k not in ("A", "B")}
        res_out["out"] = args.out
        print(json.dumps(res_out, indent=2))
        return 0

    parser.print_help()
    return 1


if __name__ == "__main__":
    sys.exit(main())
