#!/usr/bin/env python3
"""Pure-stdlib GGUF v2/v3 inspector and Redmi Note 5 Pro (whyred) RAM estimator."""

from __future__ import annotations

import argparse
import collections
import json
import os
import struct
import sys
from typing import Any, BinaryIO, Dict, List, Optional, Tuple

GGUF_MAGIC = b"GGUF"

# GGUF metadata value types (gguf.h enum gguf_type 0..12)
GGUF_TYPE_UINT8 = 0
GGUF_TYPE_INT8 = 1
GGUF_TYPE_UINT16 = 2
GGUF_TYPE_INT16 = 3
GGUF_TYPE_UINT32 = 4
GGUF_TYPE_INT32 = 5
GGUF_TYPE_FLOAT32 = 6
GGUF_TYPE_BOOL = 7
GGUF_TYPE_STRING = 8
GGUF_TYPE_ARRAY = 9
GGUF_TYPE_UINT64 = 10
GGUF_TYPE_INT64 = 11
GGUF_TYPE_FLOAT64 = 12

SCALAR_FORMATS: Dict[int, Tuple[str, int]] = {
    GGUF_TYPE_UINT8: ("<B", 1),
    GGUF_TYPE_INT8: ("<b", 1),
    GGUF_TYPE_UINT16: ("<H", 2),
    GGUF_TYPE_INT16: ("<h", 2),
    GGUF_TYPE_UINT32: ("<I", 4),
    GGUF_TYPE_INT32: ("<i", 4),
    GGUF_TYPE_FLOAT32: ("<f", 4),
    GGUF_TYPE_BOOL: ("<B", 1),
    GGUF_TYPE_UINT64: ("<Q", 8),
    GGUF_TYPE_INT64: ("<q", 8),
    GGUF_TYPE_FLOAT64: ("<d", 8),
}

# GGML tensor quantization types (ggml.h enum ggml_type)
# Mapping: type_id -> (name, block_size, bytes_per_block)
GGML_TYPES: Dict[int, Tuple[str, int, int]] = {
    0: ("F32", 1, 4),
    1: ("F16", 1, 2),
    2: ("Q4_0", 32, 18),
    3: ("Q4_1", 32, 20),
    6: ("Q5_0", 32, 22),
    7: ("Q5_1", 32, 24),
    8: ("Q8_0", 32, 34),
    9: ("Q8_1", 32, 36),
    10: ("Q2_K", 256, 84),
    11: ("Q3_K", 256, 110),
    12: ("Q4_K", 256, 144),
    13: ("Q5_K", 256, 176),
    14: ("Q6_K", 256, 210),
    15: ("Q8_K", 256, 292),
    16: ("IQ2_XXS", 256, 66),
    17: ("IQ2_XS", 256, 74),
    18: ("IQ3_XXS", 256, 98),
    19: ("IQ1_S", 256, 50),
    20: ("IQ4_NL", 32, 18),
    21: ("IQ3_S", 256, 110),
    22: ("IQ2_S", 256, 82),
    23: ("IQ4_XS", 256, 136),
    24: ("I8", 1, 1),
    25: ("I16", 1, 2),
    26: ("I32", 1, 4),
    27: ("I64", 1, 8),
    28: ("F64", 1, 8),
    29: ("IQ1_M", 256, 56),
    30: ("BF16", 1, 2),
    34: ("TQ1_0", 256, 54),
    35: ("TQ2_0", 256, 66),
    39: ("MXFP4", 32, 17),
    40: ("NVFP4", 64, 34),
    41: ("Q1_0", 128, 18),
    42: ("Q2_0", 32, 10),
    43: ("F8_E4M3", 1, 1),
}

REDMI_NOTE_5_PRO_AVAIL_RAM_MB = 3506.0
JARVIS_LLM_BUDGET_MB = 2600.0


def _read_exact(f: BinaryIO, n: int) -> bytes:
    buf = f.read(n)
    if len(buf) != n:
        raise EOFError(f"Unexpected EOF: wanted {n} bytes, got {len(buf)}")
    return buf


def _read_string(f: BinaryIO) -> str:
    (length,) = struct.unpack("<Q", _read_exact(f, 8))
    if length > 64 * 1024 * 1024:
        raise ValueError(f"Unreasonable GGUF string length: {length}")
    raw = _read_exact(f, length)
    return raw.decode("utf-8", errors="replace")


def _read_value(f: BinaryIO, vtype: int, max_array_items: int = 32) -> Any:
    if vtype == GGUF_TYPE_STRING:
        return _read_string(f)
    if vtype == GGUF_TYPE_BOOL:
        (raw,) = struct.unpack("<B", _read_exact(f, 1))
        return bool(raw)
    if vtype in SCALAR_FORMATS:
        fmt, size = SCALAR_FORMATS[vtype]
        (val,) = struct.unpack(fmt, _read_exact(f, size))
        return val
    if vtype == GGUF_TYPE_ARRAY:
        elem_type, count = struct.unpack("<IQ", _read_exact(f, 12))
        if elem_type == GGUF_TYPE_ARRAY:
            items = [_read_value(f, elem_type, max_array_items) for _ in range(count)]
            return items if count <= max_array_items else {"_array_len": count, "sample": items[:4]}
        if elem_type in SCALAR_FORMATS and elem_type != GGUF_TYPE_BOOL:
            fmt, size = SCALAR_FORMATS[elem_type]
            raw = _read_exact(f, count * size)
            if count <= max_array_items:
                return list(struct.unpack("<" + fmt[1:] * count, raw)) if count else []
            sample_count = min(count, 4)
            sample = list(struct.unpack("<" + fmt[1:] * sample_count, raw[: sample_count * size]))
            return {"_array_len": count, "elem_type": elem_type, "sample": sample}
        sample: List[Any] = []
        for idx in range(count):
            val = _read_value(f, elem_type, max_array_items)
            if count <= max_array_items or idx < 4:
                sample.append(val)
        return sample if count <= max_array_items else {"_array_len": count, "elem_type": elem_type, "sample": sample}
    raise ValueError(f"Unknown GGUF metadata value type: {vtype}")


def _tensor_nbytes(dims: List[int], ggml_type: int) -> int:
    if not dims:
        return 0
    ne = 1
    for d in dims:
        ne *= d
    info = GGML_TYPES.get(ggml_type)
    if info is None:
        return 0
    _, block_size, bytes_per_block = info
    if block_size <= 1:
        return ne * bytes_per_block
    n_blocks = (ne + block_size - 1) // block_size
    return n_blocks * bytes_per_block


def parse_gguf(path: str, ctx_len_eval: int = 2048) -> Dict[str, Any]:
    if not os.path.isfile(path):
        raise FileNotFoundError(f"GGUF file not found: {path}")

    file_size = os.path.getsize(path)
    metadata: Dict[str, Any] = {}
    tensors: List[Dict[str, Any]] = []
    type_counts: collections.Counter = collections.Counter()
    type_bytes: collections.Counter = collections.Counter()
    type_elems: collections.Counter = collections.Counter()

    with open(path, "rb") as f:
        magic = _read_exact(f, 4)
        if magic != GGUF_MAGIC:
            raise ValueError(f"Invalid GGUF magic {magic!r} in {path}")
        (version,) = struct.unpack("<I", _read_exact(f, 4))
        if version not in (2, 3):
            raise ValueError(f"Unsupported GGUF version {version} (expected 2 or 3)")

        tensor_count, kv_count = struct.unpack("<QQ", _read_exact(f, 16))
        for _ in range(kv_count):
            key = _read_string(f)
            (vtype,) = struct.unpack("<I", _read_exact(f, 4))
            val = _read_value(f, vtype)
            metadata[key] = val

        for _ in range(tensor_count):
            name = _read_string(f)
            (n_dims,) = struct.unpack("<I", _read_exact(f, 4))
            dims = list(struct.unpack(f"<{n_dims}Q", _read_exact(f, 8 * n_dims))) if n_dims else []
            ggml_type, offset = struct.unpack("<IQ", _read_exact(f, 12))
            type_name = GGML_TYPES.get(ggml_type, (f"UNKNOWN_{ggml_type}", 1, 0))[0]
            ne = 1
            for d in dims:
                ne *= d
            nbytes = _tensor_nbytes(dims, ggml_type)
            type_counts[type_name] += 1
            type_bytes[type_name] += nbytes
            type_elems[type_name] += ne
            tensors.append(
                {
                    "name": name,
                    "dims": dims,
                    "type_id": ggml_type,
                    "type": type_name,
                    "offset": offset,
                    "bytes": nbytes,
                }
            )

    arch = str(metadata.get("general.architecture", "unknown"))
    model_name = str(metadata.get("general.name", os.path.basename(path)))

    def arch_get(suffix: str, default: Any = None) -> Any:
        return metadata.get(f"{arch}.{suffix}", default)

    block_count = arch_get("block_count")
    embd_dim = arch_get("embedding_length")
    head_count = arch_get("attention.head_count")
    kv_head_count = arch_get("attention.head_count_kv", head_count)
    context_length = arch_get("context_length")
    key_length = arch_get("attention.key_length")
    value_length = arch_get("attention.value_length")

    vocab_meta = arch_get("vocab_size")
    tok_list = metadata.get("tokenizer.ggml.tokens")
    if isinstance(vocab_meta, int):
        vocab_size = vocab_meta
    elif isinstance(tok_list, dict) and "_array_len" in tok_list:
        vocab_size = int(tok_list["_array_len"])
    elif isinstance(tok_list, list):
        vocab_size = len(tok_list)
    else:
        vocab_size = None
        for t in tensors:
            if t["name"] == "token_embd.weight" and len(t["dims"]) == 2:
                vocab_size = t["dims"][1]
                break

    expert_count = arch_get("expert_count")
    expert_used_count = arch_get("expert_used_count")
    moe_tensor_count = sum(1 for t in tensors if "_exps." in t["name"] or ".ffn_gate_inp." in t["name"])

    # Compute Redmi Note 5 Pro (whyred) RAM footprint estimate for ctx=ctx_len_eval
    weights_mb = file_size / (1024.0 * 1024.0)
    kv_cache_f16_mb = 0.0
    kv_cache_q8_mb = 0.0
    if all(isinstance(x, int) and x > 0 for x in (block_count, kv_head_count)) and (
        isinstance(key_length, int) or (isinstance(embd_dim, int) and isinstance(head_count, int) and head_count > 0)
    ):
        head_dim_k = int(key_length) if isinstance(key_length, int) else int(embd_dim) // int(head_count)
        head_dim_v = int(value_length) if isinstance(value_length, int) else head_dim_k
        kv_elems = int(block_count) * int(kv_head_count) * (head_dim_k + head_dim_v) * ctx_len_eval
        kv_cache_f16_mb = (kv_elems * 2) / (1024.0 * 1024.0)
        kv_cache_q8_mb = (kv_elems * 1.0625) / (1024.0 * 1024.0)

    # Compute graph scratch + llama.cpp context overhead (~48-96 MB for 0.5B-1.5B)
    compute_scratch_mb = max(48.0, min(140.0, weights_mb * 0.14))
    estimated_ram_f16_kv_mb = weights_mb + kv_cache_f16_mb + compute_scratch_mb
    estimated_ram_q8_kv_mb = weights_mb + kv_cache_q8_mb + compute_scratch_mb

    quant_breakdown = {}
    for tname, count in sorted(type_counts.items()):
        quant_breakdown[tname] = {
            "tensors": count,
            "elements": type_elems[tname],
            "mb": round(type_bytes[tname] / (1024.0 * 1024.0), 3),
        }

    return {
        "file": os.path.abspath(path),
        "file_size_mb": round(weights_mb, 2),
        "gguf_version": version,
        "tensor_count": tensor_count,
        "kv_count": kv_count,
        "general_architecture": arch,
        "general_name": model_name,
        "block_count": block_count,
        "embedding_dim": embd_dim,
        "attention_head_count": head_count,
        "attention_kv_head_count": kv_head_count,
        "context_length": context_length,
        "vocab_size": vocab_size,
        "moe": {
            "is_moe": bool(expert_count and int(expert_count) > 1),
            "expert_count": expert_count,
            "expert_used_count": expert_used_count,
            "moe_tensor_count": moe_tensor_count,
        },
        "quant_breakdown": quant_breakdown,
        "redmi_note_5_pro_estimate": {
            "device": "Xiaomi Redmi Note 5 Pro (whyred, SDM636, 6GB LPDDR4X)",
            "eval_ctx": ctx_len_eval,
            "weights_mmap_mb": round(weights_mb, 2),
            "kv_cache_f16_mb": round(kv_cache_f16_mb, 2),
            "kv_cache_q8_0_mb": round(kv_cache_q8_mb, 2),
            "compute_scratch_mb": round(compute_scratch_mb, 2),
            "total_estimated_ram_f16_kv_mb": round(estimated_ram_f16_kv_mb, 2),
            "total_estimated_ram_q8_kv_mb": round(estimated_ram_q8_kv_mb, 2),
            "fits_jarvis_llm_budget_2600mb": estimated_ram_f16_kv_mb <= JARVIS_LLM_BUDGET_MB,
            "pct_of_observed_avail_ram_3506mb": round((estimated_ram_f16_kv_mb / REDMI_NOTE_5_PRO_AVAIL_RAM_MB) * 100.0, 1),
        },
        "tensors": tensors,
    }


def format_report(info: Dict[str, Any]) -> str:
    moe = info["moe"]
    ram = info["redmi_note_5_pro_estimate"]
    lines = [
        f"=== GGUF Inspection: {os.path.basename(info['file'])} ===",
        f"GGUF Version          : v{info['gguf_version']}",
        f"File Size             : {info['file_size_mb']:.2f} MB",
        f"Tensor / KV Count     : {info['tensor_count']} tensors, {info['kv_count']} metadata entries",
        f"Architecture          : {info['general_architecture']}",
        f"Model Name            : {info['general_name']}",
        f"Blocks / Layers       : {info['block_count']}",
        f"Embedding Dim         : {info['embedding_dim']}",
        f"Heads / KV Heads      : {info['attention_head_count']} / {info['attention_kv_head_count']}",
        f"Context Length        : {info['context_length']}",
        f"Vocab Size            : {info['vocab_size']}",
        (
            f"MoE Experts           : {moe['expert_count']} total, {moe['expert_used_count']} active ({moe['moe_tensor_count']} MoE tensors)"
            if moe["is_moe"]
            else f"MoE Experts           : None (dense model; expert_count={moe['expert_count']})"
        ),
        "Quantization Breakdown:",
    ]
    for qname, qstats in info["quant_breakdown"].items():
        lines.append(
            f"  - {qname:<10s}: {qstats['tensors']:>4d} tensors | {qstats['elements']:>12,d} elems | {qstats['mb']:>8.3f} MB"
        )
    lines.extend(
        [
            f"Redmi Note 5 Pro (whyred) Runtime RAM Estimate (ctx={ram['eval_ctx']}):",
            f"  - Weights (mmap)      : {ram['weights_mmap_mb']:.2f} MB",
            f"  - KV Cache (F16)      : {ram['kv_cache_f16_mb']:.2f} MB (or {ram['kv_cache_q8_0_mb']:.2f} MB w/ Q8_0 KV)",
            f"  - Compute Scratch     : {ram['compute_scratch_mb']:.2f} MB",
            f"  - Total Footprint     : {ram['total_estimated_ram_f16_kv_mb']:.2f} MB "
            f"({ram['pct_of_observed_avail_ram_3506mb']}% of 3506 MB MemAvailable; "
            f"budget<=2600 MB: {'YES' if ram['fits_jarvis_llm_budget_2600mb'] else 'NO'})",
        ]
    )
    return "\n".join(lines)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Inspect GGUF v2/v3 model metadata, tensors, and Redmi Note 5 Pro RAM footprint.")
    parser.add_argument("gguf_path", help="Path to .gguf model file")
    parser.add_argument("--ctx", type=int, default=2048, help="Context length for KV cache RAM estimation (default: 2048)")
    parser.add_argument("--json", action="store_true", dest="as_json", help="Emit JSON output")
    parser.add_argument("--include-tensors", action="store_true", help="Include full tensor list in --json output")
    args = parser.parse_args(argv)

    info = parse_gguf(args.gguf_path, ctx_len_eval=args.ctx)
    if args.as_json:
        out = dict(info)
        if not args.include_tensors:
            out.pop("tensors", None)
        print(json.dumps(out, indent=2))
    else:
        print(format_report(info))
    return 0


if __name__ == "__main__":
    sys.exit(main())
