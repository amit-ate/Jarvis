#!/usr/bin/env python3
"""Generate, validate, and push Jarvis ExpertRouter manifest (/sdcard/Jarvis/models/adapters/experts.json)."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from typing import Any, Dict, List, Optional

REQUIRED_DOMAINS = (
    "CONVERSATION",
    "TOOL_USE",
    "REASONING",
    "KNOWLEDGE",
    "MEMORY",
    "VISION",
)

MAX_JARVIS_RAM_BUDGET_MB = 2600


DEFAULT_HOST_ADAPTERS_DIR = "/path/to/Jarvis/models/adapters"


def build_default_manifest() -> Dict[str, Any]:
    return {
        "version": 1,
        "base_model": "qwen2.5-0.5b-instruct-q4_0.gguf",
        "vlm_model": "SmolVLM-256M-Instruct-Q8_0.gguf",
        "max_ram_budget_mb": 2600,
        "estimated_resident_mb": 1150,
        "experts": [
            {
                "domain": "CONVERSATION",
                "lora_path": None,
                "lora_scale": 1.0,
                "cvec_path": "conversation_expert.jvec",
                "cvec_scale": 0.35,
                "cvec_layer_start": 8,
                "cvec_layer_end": 21,
                "optional": True,
                "temperature": 0.50,
                "repeat_penalty": 1.20,
                "keywords": ["hello", "hi", "chat", "joke", "story"],
            },
            {
                "domain": "TOOL_USE",
                "lora_path": None,
                "lora_scale": 1.0,
                "cvec_path": "tool_expert.jvec",
                "cvec_scale": 0.30,
                "cvec_layer_start": 8,
                "cvec_layer_end": 21,
                "optional": True,
                "temperature": 0.25,
                "repeat_penalty": 1.12,
                "keywords": ["turn", "set", "timer", "alarm", "volume", "brightness", "tv", "ac"],
            },
            {
                "domain": "REASONING",
                "lora_path": None,
                "lora_scale": 1.0,
                "cvec_path": "reasoning_expert.jvec",
                "cvec_scale": 0.40,
                "cvec_layer_start": 8,
                "cvec_layer_end": 21,
                "optional": True,
                "temperature": 0.40,
                "repeat_penalty": 1.22,
                "keywords": ["why", "how", "explain", "compare", "calculate", "difference"],
            },
            {
                "domain": "KNOWLEDGE",
                "lora_path": None,
                "lora_scale": 1.0,
                "cvec_path": "anti_echo_direct.jvec",
                "cvec_scale": 0.35,
                "cvec_layer_start": 8,
                "cvec_layer_end": 21,
                "optional": True,
                "temperature": 0.35,
                "repeat_penalty": 1.18,
                "keywords": ["who", "what", "when", "where", "capital", "president", "search"],
            },
            {
                "domain": "MEMORY",
                "lora_path": None,
                "lora_scale": 1.0,
                "cvec_path": "anti_echo_direct.jvec",
                "cvec_scale": 0.30,
                "cvec_layer_start": 8,
                "cvec_layer_end": 21,
                "optional": True,
                "temperature": 0.35,
                "repeat_penalty": 1.18,
                "keywords": ["remember", "recall", "forget", "note", "learned", "know about me"],
            },
            {
                "domain": "VISION",
                "lora_path": None,
                "lora_scale": 1.0,
                "cvec_path": "anti_echo_direct.jvec",
                "cvec_scale": 0.25,
                "cvec_layer_start": 8,
                "cvec_layer_end": 21,
                "optional": True,
                "temperature": 0.25,
                "repeat_penalty": 1.15,
                "keywords": ["see", "look", "holding", "read this", "camera"],
            },
        ],
    }


def validate_manifest(
    manifest: Dict[str, Any],
    adapters_dir: Optional[str] = None,
) -> List[str]:
    errors: List[str] = []

    if manifest.get("version") != 1:
        errors.append(f"Expected version == 1, got {manifest.get('version')!r}")
    if not isinstance(manifest.get("base_model"), str) or not manifest["base_model"].strip():
        errors.append("base_model must be a non-empty string")
    if not isinstance(manifest.get("vlm_model"), str) or not manifest["vlm_model"].strip():
        errors.append("vlm_model must be a non-empty string")

    max_budget = manifest.get("max_ram_budget_mb")
    est_resident = manifest.get("estimated_resident_mb")
    if not isinstance(max_budget, (int, float)) or max_budget <= 0 or max_budget > MAX_JARVIS_RAM_BUDGET_MB:
        errors.append(
            f"max_ram_budget_mb must be in (0, {MAX_JARVIS_RAM_BUDGET_MB}], got {max_budget!r}"
        )
    if not isinstance(est_resident, (int, float)) or est_resident <= 0:
        errors.append(f"estimated_resident_mb must be > 0, got {est_resident!r}")
    elif isinstance(max_budget, (int, float)) and est_resident > max_budget:
        errors.append(
            f"estimated_resident_mb ({est_resident}) exceeds max_ram_budget_mb ({max_budget})"
        )

    experts = manifest.get("experts")
    if not isinstance(experts, list) or not experts:
        errors.append("experts must be a non-empty list")
        return errors

    seen_domains = set()
    for idx, entry in enumerate(experts):
        if not isinstance(entry, dict):
            errors.append(f"experts[{idx}] must be an object")
            continue
        domain = entry.get("domain")
        if not isinstance(domain, str) or not domain.strip():
            errors.append(f"experts[{idx}].domain must be a non-empty string")
            continue
        if domain in seen_domains:
            errors.append(f"Duplicate expert domain: {domain}")
        seen_domains.add(domain)

        temp = entry.get("temperature")
        if not isinstance(temp, (int, float)) or not (0.0 <= float(temp) <= 2.0):
            errors.append(f"experts[{idx}] ({domain}) invalid temperature: {temp!r}")

        rp = entry.get("repeat_penalty")
        if not isinstance(rp, (int, float)) or not (1.0 <= float(rp) <= 2.5):
            errors.append(f"experts[{idx}] ({domain}) invalid repeat_penalty: {rp!r}")

        scale = entry.get("lora_scale")
        if not isinstance(scale, (int, float)) or not (0.0 <= float(scale) <= 2.0):
            errors.append(f"experts[{idx}] ({domain}) invalid lora_scale: {scale!r}")

        kws = entry.get("keywords")
        if not isinstance(kws, list) or not kws or not all(isinstance(k, str) and k.strip() for k in kws):
            errors.append(f"experts[{idx}] ({domain}) keywords must be a non-empty list of strings")

        lora_path = entry.get("lora_path")
        is_optional = bool(entry.get("optional", False))
        if lora_path is not None:
            if not isinstance(lora_path, str) or not lora_path.strip():
                errors.append(f"experts[{idx}] ({domain}) lora_path must be null or non-empty string")
            elif not is_optional:
                candidate = (
                    lora_path
                    if os.path.isabs(lora_path) or not adapters_dir
                    else os.path.join(adapters_dir, lora_path)
                )
                if not os.path.isfile(candidate):
                    errors.append(
                        f"experts[{idx}] ({domain}) lora_path {candidate!r} does not exist locally "
                        "(set \"optional\": true if provisioned only on-device)"
                    )

        cvec_scale = entry.get("cvec_scale")
        if cvec_scale is not None:
            if isinstance(cvec_scale, bool) or not isinstance(cvec_scale, (int, float)) or not (0.0 <= float(cvec_scale) <= 2.0):
                errors.append(f"experts[{idx}] ({domain}) invalid cvec_scale: {cvec_scale!r}")

        layer_start = entry.get("cvec_layer_start")
        layer_end = entry.get("cvec_layer_end")
        if layer_start is not None or layer_end is not None:
            if (
                isinstance(layer_start, bool)
                or isinstance(layer_end, bool)
                or not isinstance(layer_start, int)
                or not isinstance(layer_end, int)
                or layer_start < 1
                or layer_start > layer_end
            ):
                errors.append(
                    f"experts[{idx}] ({domain}) invalid cvec layer range: "
                    f"[{layer_start!r}, {layer_end!r}] (must satisfy 1 <= start <= end)"
                )

        cvec_path = entry.get("cvec_path")
        if cvec_path is not None:
            if not isinstance(cvec_path, str) or not cvec_path.strip():
                errors.append(f"experts[{idx}] ({domain}) cvec_path must be null or non-empty string")
            elif not is_optional:
                candidate = (
                    cvec_path
                    if os.path.isabs(cvec_path) or not adapters_dir
                    else os.path.join(adapters_dir, cvec_path)
                )
                if not os.path.isfile(candidate):
                    errors.append(
                        f"experts[{idx}] ({domain}) cvec_path {candidate!r} does not exist locally "
                        "(set \"optional\": true if provisioned only on-device)"
                    )

    missing_domains = [d for d in REQUIRED_DOMAINS if d not in seen_domains]
    if missing_domains:
        errors.append(f"Missing required expert domains: {', '.join(missing_domains)}")

    return errors


def push_manifest_adb(
    manifest_path: str,
    serial: str,
    adapters_dir: Optional[str] = None,
) -> None:
    remote_dir = "/sdcard/Jarvis/models/adapters"
    remote_file = f"{remote_dir}/experts.json"
    subprocess.run(
        ["adb", "-s", serial, "shell", "mkdir", "-p", remote_dir],
        check=True,
    )
    subprocess.run(
        ["adb", "-s", serial, "push", manifest_path, remote_file],
        check=True,
    )

    search_dir = adapters_dir
    if not search_dir:
        if os.path.isdir(DEFAULT_HOST_ADAPTERS_DIR):
            search_dir = DEFAULT_HOST_ADAPTERS_DIR
        else:
            search_dir = os.path.dirname(os.path.abspath(manifest_path))

    if search_dir and os.path.isdir(search_dir):
        for fname in sorted(os.listdir(search_dir)):
            if fname.endswith((".jvec", ".gguf")):
                local_path = os.path.join(search_dir, fname)
                if os.path.isfile(local_path):
                    subprocess.run(
                        ["adb", "-s", serial, "push", local_path, f"{remote_dir}/{fname}"],
                        check=True,
                    )


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Generate and validate Jarvis ExpertRouter experts.json manifest.")
    parser.add_argument("--out", default="bench/eval/experts.json", help="Manifest path to write/validate (default: bench/eval/experts.json)")
    parser.add_argument("--validate", action="store_true", help="Validate manifest schema, RAM budget, and adapter paths")
    parser.add_argument("--adapters-dir", default=None, help="Local directory used to resolve relative lora_path/cvec_path entries")
    parser.add_argument("--push-adb", metavar="SERIAL", help="Push validated manifest and .jvec/.gguf adapters via ADB to /sdcard/Jarvis/models/adapters/")
    args = parser.parse_args(argv)

    if args.validate and os.path.isfile(args.out):
        with open(args.out, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        source_desc = args.out
    else:
        manifest = build_default_manifest()
        source_desc = "generated default ExpertRouter manifest"

    adapters_dir = args.adapters_dir
    if not adapters_dir:
        if os.path.isdir(DEFAULT_HOST_ADAPTERS_DIR):
            adapters_dir = DEFAULT_HOST_ADAPTERS_DIR
        elif os.path.isfile(args.out):
            adapters_dir = os.path.dirname(os.path.abspath(args.out))

    errors = validate_manifest(manifest, adapters_dir=adapters_dir)
    if errors:
        print(f"VALIDATION FAILED ({source_desc}):", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    if not args.validate:
        out_dir = os.path.dirname(os.path.abspath(args.out))
        if out_dir:
            os.makedirs(out_dir, exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2)
            f.write("\n")
        print(f"Wrote valid ExpertRouter manifest -> {args.out}")

    if args.push_adb:
        if os.path.isfile(args.out):
            push_manifest_adb(args.out, args.push_adb, adapters_dir=adapters_dir)
        else:
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as tmp:
                json.dump(manifest, tmp, indent=2)
                tmp.write("\n")
                tmp_path = tmp.name
            try:
                push_manifest_adb(tmp_path, args.push_adb, adapters_dir=adapters_dir)
            finally:
                os.unlink(tmp_path)
        print(f"Pushed experts.json and adapters to {args.push_adb}:/sdcard/Jarvis/models/adapters/")

    domains = [e["domain"] for e in manifest["experts"]]
    print(
        f"VALID ({source_desc}): version={manifest['version']}, base={manifest['base_model']}, "
        f"vlm={manifest['vlm_model']}, resident={manifest['estimated_resident_mb']}/"
        f"{manifest['max_ram_budget_mb']} MB, domains={domains}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
