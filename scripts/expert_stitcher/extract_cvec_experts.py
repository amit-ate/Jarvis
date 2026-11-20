#!/usr/bin/env python3
"""Extract real neural Control-Vector experts (.jvec) via llama-cvector-generator."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import struct
import subprocess
import sys
import tempfile
from typing import Any, BinaryIO, Dict, List, Optional, Tuple

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DATASETS_DIR = os.path.join(SCRIPT_DIR, "cvec_datasets")
DEFAULT_CVEC_BIN = "/path/to/Jarvis/bench/host-tools/bin/llama-cvector-generator"
DEFAULT_BASE_MODEL = "/path/to/Jarvis/models/llm/qwen2.5-0.5b-instruct-q4_0.gguf"
DEFAULT_OUT_DIR = "/path/to/Jarvis/models/adapters"

ALL_EXPERTS = (
    "anti_echo_direct",
    "reasoning_expert",
    "tool_expert",
    "conversation_expert",
)

JVEC_MAGIC = b"JVEC"
JVEC_VERSION = 1
JVEC_HEADER_FMT = "<4s5i"
JVEC_HEADER_BYTES = struct.calcsize(JVEC_HEADER_FMT)  # 24 bytes

GGUF_MAGIC = b"GGUF"
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


def _read_exact(f: BinaryIO, n: int) -> bytes:
    buf = f.read(n)
    if len(buf) != n:
        raise EOFError(f"Unexpected EOF: wanted {n} bytes, got {len(buf)}")
    return buf


def _read_gguf_string(f: BinaryIO) -> str:
    (length,) = struct.unpack("<Q", _read_exact(f, 8))
    if length > 64 * 1024 * 1024:
        raise ValueError(f"Unreasonable GGUF string length: {length}")
    return _read_exact(f, length).decode("utf-8", errors="replace")


def _read_gguf_value(f: BinaryIO, vtype: int) -> Any:
    if vtype == GGUF_TYPE_STRING:
        return _read_gguf_string(f)
    if vtype == GGUF_TYPE_BOOL:
        (raw,) = struct.unpack("<B", _read_exact(f, 1))
        return bool(raw)
    if vtype in SCALAR_FORMATS:
        fmt, size = SCALAR_FORMATS[vtype]
        return struct.unpack(fmt, _read_exact(f, size))[0]
    if vtype == GGUF_TYPE_ARRAY:
        elem_type, count = struct.unpack("<IQ", _read_exact(f, 12))
        if elem_type in SCALAR_FORMATS:
            fmt, size = SCALAR_FORMATS[elem_type]
            raw = _read_exact(f, count * size)
            return list(struct.unpack("<" + fmt[1:] * count, raw)) if count else []
        return [_read_gguf_value(f, elem_type) for _ in range(count)]
    raise ValueError(f"Unknown GGUF metadata type: {vtype}")


def parse_cvec_gguf(gguf_path: str) -> Tuple[Dict[int, List[float]], int, int]:
    """Parse direction.<layer> F32 tensors from a llama-cvector-generator GGUF output."""
    with open(gguf_path, "rb") as f:
        magic = _read_exact(f, 4)
        if magic != GGUF_MAGIC:
            raise ValueError(f"Invalid GGUF magic {magic!r} in {gguf_path}")
        (version,) = struct.unpack("<I", _read_exact(f, 4))
        if version not in (2, 3):
            raise ValueError(f"Unsupported GGUF version {version}")

        tensor_count, kv_count = struct.unpack("<QQ", _read_exact(f, 16))
        metadata: Dict[str, Any] = {}
        for _ in range(kv_count):
            key = _read_gguf_string(f)
            (vtype,) = struct.unpack("<I", _read_exact(f, 4))
            metadata[key] = _read_gguf_value(f, vtype)

        tensor_infos: List[Tuple[str, List[int], int, int]] = []
        for _ in range(tensor_count):
            name = _read_gguf_string(f)
            (n_dims,) = struct.unpack("<I", _read_exact(f, 4))
            dims = list(struct.unpack(f"<{n_dims}Q", _read_exact(f, 8 * n_dims))) if n_dims else []
            ggml_type, offset = struct.unpack("<IQ", _read_exact(f, 12))
            tensor_infos.append((name, dims, ggml_type, offset))

        alignment = int(metadata.get("general.alignment", 32))
        if alignment <= 0:
            alignment = 32
        pos = f.tell()
        data_offset = ((pos + alignment - 1) // alignment) * alignment

        directions: Dict[int, List[float]] = {}
        n_embd: Optional[int] = None
        dir_re = re.compile(r"^direction\.(\d+)$")

        for name, dims, ggml_type, offset in tensor_infos:
            m = dir_re.match(name)
            if not m:
                continue
            layer_idx = int(m.group(1))
            if ggml_type != 0:
                raise ValueError(f"Expected F32 tensor for {name}, got ggml_type={ggml_type}")
            if len(dims) != 1 or dims[0] <= 0:
                raise ValueError(f"Expected 1D tensor for {name}, got dims={dims}")
            if n_embd is None:
                n_embd = dims[0]
            elif dims[0] != n_embd:
                raise ValueError(f"Inconsistent n_embd in {name}: {dims[0]} != {n_embd}")

            f.seek(data_offset + offset)
            raw = _read_exact(f, n_embd * 4)
            vec = list(struct.unpack(f"<{n_embd}f", raw))
            directions[layer_idx] = vec

    if n_embd is None or not directions:
        raise ValueError(f"No direction.<layer> tensors found in {gguf_path}")

    n_layers_minus_1 = max(directions.keys())
    expected = set(range(1, n_layers_minus_1 + 1))
    missing = sorted(expected - set(directions.keys()))
    if missing:
        raise ValueError(f"Missing direction layers in {gguf_path}: {missing}")

    return directions, n_embd, n_layers_minus_1


def l2_norm(vec: List[float]) -> float:
    return math.sqrt(sum(x * x for x in vec))


def normalize_layers(
    directions: Dict[int, List[float]],
    n_embd: int,
    n_layers_minus_1: int,
    il_start: int,
    il_end: int,
) -> Tuple[List[float], Dict[int, float], Dict[int, float]]:
    """Zero layers outside [il_start, il_end] and L2-normalize layers inside range."""
    if not (1 <= il_start <= il_end <= n_layers_minus_1):
        raise ValueError(
            f"Invalid layer range [{il_start}, {il_end}] for n_layers_minus_1={n_layers_minus_1}"
        )

    payload: List[float] = []
    raw_norms: Dict[int, float] = {}
    final_norms: Dict[int, float] = {}
    zeros = [0.0] * n_embd

    for layer in range(1, n_layers_minus_1 + 1):
        vec = directions[layer]
        if len(vec) != n_embd:
            raise ValueError(f"Layer {layer} length {len(vec)} != n_embd {n_embd}")
        norm = l2_norm(vec)
        raw_norms[layer] = norm

        if il_start <= layer <= il_end:
            if norm <= 1e-12 or not math.isfinite(norm):
                raise ValueError(f"Layer {layer} inside [{il_start}, {il_end}] has degenerate norm={norm}")
            inv = 1.0 / norm
            normed = [x * inv for x in vec]
            payload.extend(normed)
            final_norms[layer] = l2_norm(normed)
        else:
            payload.extend(zeros)
            final_norms[layer] = 0.0

    return payload, raw_norms, final_norms


def write_jvec(
    out_path: str,
    n_embd: int,
    n_layers_minus_1: int,
    il_start: int,
    il_end: int,
    payload: List[float],
) -> None:
    expected_len = n_layers_minus_1 * n_embd
    if len(payload) != expected_len:
        raise ValueError(f"Payload length {len(payload)} != {expected_len}")

    out_dir = os.path.dirname(os.path.abspath(out_path))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    header = struct.pack(
        JVEC_HEADER_FMT,
        JVEC_MAGIC,
        JVEC_VERSION,
        n_embd,
        n_layers_minus_1,
        il_start,
        il_end,
    )
    body = struct.pack(f"<{expected_len}f", *payload)

    fd, tmp_path = tempfile.mkstemp(prefix=".tmp_jvec_", suffix=".jvec", dir=out_dir or None)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(header)
            f.write(body)
        os.chmod(tmp_path, 0o644)
        os.replace(tmp_path, out_path)
    except BaseException:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)
        raise


def read_jvec(path: str) -> Tuple[int, int, int, int, List[float]]:
    with open(path, "rb") as f:
        raw_header = f.read(JVEC_HEADER_BYTES)
        if len(raw_header) != JVEC_HEADER_BYTES:
            raise ValueError(f"Truncated JVEC header in {path}")
        magic, version, n_embd, n_layers_minus_1, il_start, il_end = struct.unpack(
            JVEC_HEADER_FMT, raw_header
        )
        if magic != JVEC_MAGIC:
            raise ValueError(f"Bad JVEC magic {magic!r}")
        if version != JVEC_VERSION:
            raise ValueError(f"Unsupported JVEC version {version}")
        if n_embd <= 0 or n_layers_minus_1 <= 0:
            raise ValueError(f"Invalid JVEC dimensions: n_embd={n_embd}, n_layers_minus_1={n_layers_minus_1}")
        if not (1 <= il_start <= il_end <= n_layers_minus_1):
            raise ValueError(f"Invalid JVEC layer range [{il_start}, {il_end}]")

        expected_floats = n_layers_minus_1 * n_embd
        raw_body = f.read()
        if len(raw_body) != expected_floats * 4:
            raise ValueError(
                f"JVEC payload size mismatch: got {len(raw_body)} bytes, expected {expected_floats * 4}"
            )
        payload = list(struct.unpack(f"<{expected_floats}f", raw_body))
    return n_embd, n_layers_minus_1, il_start, il_end, payload


def _escape_prompt_line(text: str) -> str:
    # ctrlvec_load_prompt_file reads one prompt per line and unescapes \n, \t, \\ via string_process_escapes
    return (
        text.replace("\\", "\\\\")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )


def extract_dataset(
    dataset_path: str,
    model_path: str,
    out_dir: str,
    cvec_bin: str = DEFAULT_CVEC_BIN,
    threads: Optional[int] = None,
) -> Dict[str, Any]:
    if not os.path.isfile(dataset_path):
        raise FileNotFoundError(f"Dataset not found: {dataset_path}")
    if not os.path.isfile(model_path):
        raise FileNotFoundError(f"Base GGUF model not found: {model_path}")
    if not os.path.isfile(cvec_bin) or not os.access(cvec_bin, os.X_OK):
        raise FileNotFoundError(f"llama-cvector-generator binary not executable: {cvec_bin}")

    with open(dataset_path, "r", encoding="utf-8") as f:
        ds = json.load(f)

    name = str(ds.get("name") or os.path.splitext(os.path.basename(dataset_path))[0]).strip()
    positive = ds.get("positive")
    negative = ds.get("negative")
    il_start = int(ds.get("il_start", 8))
    il_end = int(ds.get("il_end", 21))
    method = str(ds.get("method", "mean")).strip().lower()

    if not isinstance(positive, list) or not isinstance(negative, list) or not positive or len(positive) != len(negative):
        raise ValueError(f"Dataset {dataset_path} must have equal non-empty positive and negative lists")
    if method not in ("mean", "pca"):
        raise ValueError(f"Unsupported method {method!r} in {dataset_path}")

    n_threads = threads or min(8, os.cpu_count() or 4)
    os.makedirs(out_dir, exist_ok=True)
    out_jvec = os.path.join(out_dir, f"{name}.jvec")

    with tempfile.TemporaryDirectory(prefix=f"cvec_{name}_") as tmp_dir:
        pos_file = os.path.join(tmp_dir, "positive.txt")
        neg_file = os.path.join(tmp_dir, "negative.txt")
        tmp_gguf = os.path.join(tmp_dir, f"{name}.gguf")

        with open(pos_file, "w", encoding="utf-8") as pf:
            for item in positive:
                pf.write(_escape_prompt_line(str(item)) + "\n")
        with open(neg_file, "w", encoding="utf-8") as nf:
            for item in negative:
                nf.write(_escape_prompt_line(str(item)) + "\n")

        cmd = [
            cvec_bin,
            "-m", model_path,
            "--positive-file", pos_file,
            "--negative-file", neg_file,
            "--method", method,
            "-t", str(n_threads),
            "-o", tmp_gguf,
        ]
        if method == "pca":
            cmd.extend([
                "--pca-iter", str(int(ds.get("pca_iter", 1000))),
                "--pca-batch", str(int(ds.get("pca_batch", 100))),
            ])

        print(f"[{name}] Running llama-cvector-generator ({len(positive)} pairs, method={method}, layers={il_start}..{il_end})...")
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            sys.stderr.write(proc.stdout)
            sys.stderr.write(proc.stderr)
            raise RuntimeError(f"llama-cvector-generator failed for {name} (exit {proc.returncode})")

        directions, n_embd, n_layers_minus_1 = parse_cvec_gguf(tmp_gguf)
        payload, raw_norms, final_norms = normalize_layers(
            directions, n_embd, n_layers_minus_1, il_start, il_end
        )
        write_jvec(out_jvec, n_embd, n_layers_minus_1, il_start, il_end, payload)

    byte_size = os.path.getsize(out_jvec)
    # Verify roundtrip read
    r_embd, r_nl_m1, r_start, r_end, _ = read_jvec(out_jvec)
    assert (r_embd, r_nl_m1, r_start, r_end) == (n_embd, n_layers_minus_1, il_start, il_end)

    print(
        f"[{name}] Wrote {out_jvec} ({byte_size} bytes, n_embd={n_embd}, "
        f"n_layers_minus_1={n_layers_minus_1}, active={il_start}..{il_end})"
    )
    return {
        "name": name,
        "jvec_path": out_jvec,
        "bytes": byte_size,
        "n_embd": n_embd,
        "n_layers_minus_1": n_layers_minus_1,
        "il_start": il_start,
        "il_end": il_end,
        "raw_norms": raw_norms,
        "final_norms": final_norms,
    }


def run_self_test() -> int:
    print("Running ControlVector (.jvec) binary format self-test...")
    n_embd = 896
    n_layers_minus_1 = 23
    il_start = 8
    il_end = 21

    directions: Dict[int, List[float]] = {}
    for layer in range(1, n_layers_minus_1 + 1):
        directions[layer] = [((layer * 0.13) + ((d % 17) - 8) * 0.25) for d in range(n_embd)]

    payload, _, final_norms = normalize_layers(directions, n_embd, n_layers_minus_1, il_start, il_end)

    with tempfile.TemporaryDirectory(prefix="jvec_selftest_") as tmp_dir:
        path = os.path.join(tmp_dir, "test.jvec")
        write_jvec(path, n_embd, n_layers_minus_1, il_start, il_end, payload)

        expected_size = JVEC_HEADER_BYTES + 4 * n_layers_minus_1 * n_embd
        actual_size = os.path.getsize(path)
        assert actual_size == expected_size, f"Size mismatch: {actual_size} != {expected_size}"

        r_embd, r_nl_m1, r_start, r_end, r_payload = read_jvec(path)
        assert r_embd == n_embd
        assert r_nl_m1 == n_layers_minus_1
        assert r_start == il_start
        assert r_end == il_end
        assert len(r_payload) == n_layers_minus_1 * n_embd

        for layer in range(1, n_layers_minus_1 + 1):
            offset = (layer - 1) * n_embd
            vec = r_payload[offset : offset + n_embd]
            norm = l2_norm(vec)
            if il_start <= layer <= il_end:
                assert abs(norm - 1.0) < 1e-5, f"Layer {layer} norm={norm} != 1.0"
                assert abs(final_norms[layer] - 1.0) < 1e-5
            else:
                assert norm == 0.0, f"Layer {layer} outside range should be zeroed, got norm={norm}"
                assert final_norms[layer] == 0.0

        # Verify corrupt magic fails cleanly
        bad_path = os.path.join(tmp_dir, "bad.jvec")
        with open(bad_path, "wb") as bf:
            bf.write(b"NOPE" + b"\x00" * 20)
        try:
            read_jvec(bad_path)
            raise AssertionError("Expected ValueError for bad magic")
        except ValueError:
            pass

    print(
        f"SELF-TEST PASSED: header={JVEC_HEADER_BYTES}B, "
        f"payload={n_layers_minus_1 * n_embd * 4}B, total={expected_size}B, "
        f"active layers {il_start}..{il_end} unit-normalized, inactive layers zeroed."
    )
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract real neural Control-Vector experts (.jvec) using llama-cvector-generator."
    )
    parser.add_argument("--self-test", action="store_true", help="Verify .jvec binary header + payload roundtrip")
    parser.add_argument("--all", action="store_true", help="Extract all 4 core Jarvis Control-Vector experts")
    parser.add_argument("--dataset", help="Path to a single dataset JSON file")
    parser.add_argument("--model", default=DEFAULT_BASE_MODEL, help=f"Base GGUF model path (default: {DEFAULT_BASE_MODEL})")
    parser.add_argument("--out-dir", default=DEFAULT_OUT_DIR, help=f"Output directory for .jvec files (default: {DEFAULT_OUT_DIR})")
    parser.add_argument("--cvec-bin", default=DEFAULT_CVEC_BIN, help=f"Path to llama-cvector-generator (default: {DEFAULT_CVEC_BIN})")
    parser.add_argument("--threads", type=int, default=None, help="CPU threads for llama-cvector-generator")
    args = parser.parse_args(argv)

    if not args.self_test and not args.all and not args.dataset:
        parser.print_help()
        return 1

    if args.self_test:
        rc = run_self_test()
        if rc != 0:
            return rc

    datasets: List[str] = []
    if args.all:
        datasets.extend(os.path.join(DEFAULT_DATASETS_DIR, f"{name}.json") for name in ALL_EXPERTS)
    if args.dataset:
        datasets.append(args.dataset)

    results: List[Dict[str, Any]] = []
    for ds_path in datasets:
        res = extract_dataset(
            dataset_path=ds_path,
            model_path=args.model,
            out_dir=args.out_dir,
            cvec_bin=args.cvec_bin,
            threads=args.threads,
        )
        results.append(res)

    if results:
        print("\n=== Extraction Summary ===")
        for res in results:
            norms_str = ", ".join(
                f"L{layer}:{res['final_norms'][layer]:.4f}(raw={res['raw_norms'][layer]:.4f})"
                for layer in range(1, res["n_layers_minus_1"] + 1)
            )
            print(f"- {res['name']}: {res['jvec_path']} | {res['bytes']} bytes | {norms_str}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
