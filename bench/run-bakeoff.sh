#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${SERIAL:-${ANDROID_SERIAL:-}}"
ADB="adb${SERIAL:+ -s $SERIAL}"
REMOTE_DIR="/data/local/tmp/jarvis"
COOLDOWN_SECS="${COOLDOWN_SECS:-10}"

JARVIS_HOME="$ROOT_DIR"
if [ -x "${LLAMA_BENCH_BIN:-}" ]; then
  BIN_LOCAL="$LLAMA_BENCH_BIN"
elif [ -x "$ROOT_DIR/bench/out/bin/llama-bench" ]; then
  BIN_LOCAL="$ROOT_DIR/bench/out/bin/llama-bench"
else
  BIN_LOCAL="$JARVIS_HOME/bench/out/bin/llama-bench"
fi

if [ -d "${MODELS_DIR:-}" ]; then
  LLM_DIR="$MODELS_DIR"
elif [ -d "$ROOT_DIR/models/llm" ] && [ -n "$(ls -A "$ROOT_DIR/models/llm" 2>/dev/null)" ]; then
  LLM_DIR="$ROOT_DIR/models/llm"
else
  LLM_DIR="$JARVIS_HOME/models/llm"
fi

CANDIDATES=(
  "qwen2.5-0.5b-instruct-q4_0.gguf"
  "Qwen3-0.6B-Q4_0.gguf"
  "LFM2-700M-Q4_0.gguf"
  "LFM2-350M-Q4_0.gguf"
  "gemma-3-270m-it-Q4_0.gguf"
)

if [ ! -x "$BIN_LOCAL" ]; then
  echo "ERROR: llama-bench binary not found at $BIN_LOCAL" >&2
  exit 1
fi

for fname in "${CANDIDATES[@]}"; do
  if [ ! -s "$LLM_DIR/$fname" ]; then
    echo "ERROR: Missing model $LLM_DIR/$fname" >&2
    exit 1
  fi
done

read_batt_temp() {
  $ADB shell dumpsys battery | awk '/temperature:/ {printf "%.1f", $2/10}'
}

echo "=== Stopping com.jarvis.edge on $SERIAL ==="
$ADB shell am force-stop com.jarvis.edge || true
sleep 2

$ADB shell "mkdir -p $REMOTE_DIR"
REMOTE_BIN="$REMOTE_DIR/llama-bench"
$ADB push --sync "$BIN_LOCAL" "$REMOTE_BIN" >/dev/null
$ADB shell "chmod +x $REMOTE_BIN"

echo "=== Running Phase 2 Sub-1B Bake-Off on $SERIAL ==="
echo "Binary: $BIN_LOCAL (llama-bench-armv8a)"
echo "Flags: taskset f0, -t 4 --poll 0 --no-warmup -r 1 --delay 5 -p 256 -n 64 -o md"
echo ""

for idx in "${!CANDIDATES[@]}"; do
  fname="${CANDIDATES[$idx]}"
  model_local="$LLM_DIR/$fname"
  remote_model="$REMOTE_DIR/$fname"

  echo ">>> [$((idx + 1))/${#CANDIDATES[@]}] Pushing $fname ..."
  $ADB push "$model_local" "$remote_model" >/dev/null

  temp_before="$(read_batt_temp)"
  rc=0
  out="$($ADB shell "timeout -k 5 180 taskset f0 $REMOTE_BIN -m $remote_model -t 4 --poll 0 --no-warmup -r 1 --delay 5 -p 256 -n 64 -o md" | tr -d '\r')" || rc=$?
  temp_after="$(read_batt_temp)"

  $ADB shell "rm -f $remote_model"

  echo "### $fname (llama-bench-armv8a)"
  echo "- Battery temp: ${temp_before}°C -> ${temp_after}°C (rc=$rc)"
  echo ""
  echo "$out"
  echo ""

  if [ "$idx" -lt "$((${#CANDIDATES[@]} - 1))" ]; then
    echo "Cooling ${COOLDOWN_SECS}s before next candidate..."
    sleep "$COOLDOWN_SECS"
  fi
done
