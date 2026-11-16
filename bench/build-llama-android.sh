#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
CMAKE_BIN="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
NDK_DIR="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n 1)}"
LLAMA_DIR="$ROOT_DIR/third_party/llama.cpp"

if [ ! -x "$CMAKE_BIN" ]; then
  echo "CMake 3.22.1 not found at $CMAKE_BIN" >&2
  exit 1
fi
if [ -z "$NDK_DIR" ] || [ ! -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ]; then
  echo "Android NDK toolchain not found under $ANDROID_HOME/ndk" >&2
  exit 1
fi
if [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
  echo "llama.cpp submodule missing at $LLAMA_DIR" >&2
  exit 1
fi

NCPU="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

build_variant() {
  local out_dir="$1"
  local arch_flags="$2"
  echo "=== Building llama-bench in $out_dir ($arch_flags) ==="
  "$CMAKE_BIN" -S "$LLAMA_DIR" -B "$out_dir" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="$arch_flags" \
    -DCMAKE_CXX_FLAGS="$arch_flags" \
    -DBUILD_SHARED_LIBS=OFF
  "$CMAKE_BIN" --build "$out_dir" --target llama-bench -j"$NCPU"
}

build_variant "$ROOT_DIR/bench/out" "-march=armv8-a"
build_variant "$ROOT_DIR/bench/out-a73" "-march=armv8-a -mtune=cortex-a73"

echo "=== Built Binaries ==="
ls -lh "$ROOT_DIR/bench/out/bin/llama-bench" "$ROOT_DIR/bench/out-a73/bin/llama-bench"
