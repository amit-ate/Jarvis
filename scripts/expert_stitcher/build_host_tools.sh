#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
LLAMA_DIR="$ROOT_DIR/third_party/llama.cpp"

HOST_TOOLS_DIR="${HOST_TOOLS_DIR:-$ROOT_DIR/bench/host-tools}"

if [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
  echo "llama.cpp source not found at $LLAMA_DIR" >&2
  exit 1
fi

CMAKE_BIN="$(command -v cmake || true)"
if [ -z "$CMAKE_BIN" ]; then
  CMAKE_BIN="$(ls -d "$HOME"/Library/Android/sdk/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [ -z "$CMAKE_BIN" ] || [ ! -x "$CMAKE_BIN" ]; then
  echo "cmake not found in PATH or \$HOME/Library/Android/sdk/cmake" >&2
  exit 1
fi

NINJA_BIN="$(command -v ninja || true)"
if [ -z "$NINJA_BIN" ]; then
  CANDIDATE_NINJA="$(dirname "$CMAKE_BIN")/ninja"
  if [ -x "$CANDIDATE_NINJA" ]; then
    NINJA_BIN="$CANDIDATE_NINJA"
  fi
fi

NCPU="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
mkdir -p "$HOST_TOOLS_DIR"

CMAKE_ARGS=(
  -S "$LLAMA_DIR"
  -B "$HOST_TOOLS_DIR"
  -DCMAKE_BUILD_TYPE=Release
  -DLLAMA_BUILD_TESTS=OFF
  -DLLAMA_BUILD_EXAMPLES=OFF
  -DLLAMA_BUILD_SERVER=OFF
  -DGGML_METAL=OFF
  -DBUILD_SHARED_LIBS=OFF
)

if [ -n "$NINJA_BIN" ] && [ -x "$NINJA_BIN" ]; then
  CMAKE_ARGS+=(-G Ninja "-DCMAKE_MAKE_PROGRAM=$NINJA_BIN")
fi

echo "=== Configuring llama-cvector-generator in $HOST_TOOLS_DIR ==="
"$CMAKE_BIN" "${CMAKE_ARGS[@]}"

echo "=== Building llama-cvector-generator (-j$NCPU) ==="
"$CMAKE_BIN" --build "$HOST_TOOLS_DIR" --target llama-cvector-generator -j"$NCPU"

BIN_PATH="$HOST_TOOLS_DIR/bin/llama-cvector-generator"
if [ ! -x "$BIN_PATH" ]; then
  echo "Expected binary missing at $BIN_PATH" >&2
  exit 1
fi

echo "=== Built Host Tool ==="
ls -lh "$BIN_PATH"
"$BIN_PATH" --help | head -n 40
