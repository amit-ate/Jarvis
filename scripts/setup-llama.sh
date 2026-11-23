#!/usr/bin/env bash
# Fetches llama.cpp at the version Jarvis pins (official v1.0.0) and applies Jarvis's own
# changes from patches/llama.cpp/ on top. Safe to run again: patches already applied are skipped.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA_DIR="$ROOT_DIR/third_party/llama.cpp"
PATCH_DIR="$ROOT_DIR/patches/llama.cpp"

git -C "$ROOT_DIR" submodule update --init third_party/llama.cpp

for patch in "$PATCH_DIR"/*.patch; do
  name="$(basename "$patch")"
  if git -C "$LLAMA_DIR" apply --reverse --check "$patch" 2>/dev/null; then
    echo "already applied: $name"
  else
    git -C "$LLAMA_DIR" apply --whitespace=nowarn "$patch"
    echo "applied: $name"
  fi
done
