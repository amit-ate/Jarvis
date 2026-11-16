#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${SERIAL:-$ANDROID_SERIAL}"
ADB="adb -s $SERIAL"
REMOTE_DIR="/data/local/tmp/jarvis"
RESULTS_FILE="$ROOT_DIR/bench/results.md"
MODELS_DIR="$ROOT_DIR/models/llm"

read_batt_temp() {
  $ADB shell dumpsys battery | awk '/temperature:/ {printf "%.1f", $2/10}'
}

run_one_bench() {
  local bin_local="$1"
  local bin_name="$2"
  local model_local="$3"
  local fname
  fname="$(basename "$model_local")"
  local remote_bin="$REMOTE_DIR/$bin_name"
  local remote_model="$REMOTE_DIR/$fname"

  $ADB push --sync "$bin_local" "$remote_bin" >/dev/null
  $ADB shell "chmod +x $remote_bin"
  $ADB push "$model_local" "$remote_model" >/dev/null

  local temp_before
  temp_before="$(read_batt_temp)"
  local out rc=0
  out="$($ADB shell "timeout -k 5 180 taskset f0 $remote_bin -m $remote_model -t 4 --poll 0 --no-warmup -r 1 --delay 5 -p 256 -n 64 -o md" | tr -d '\r')" || rc=$?
  local temp_after
  temp_after="$(read_batt_temp)"

  $ADB shell "rm -f $remote_model"

  printf "### %s (%s)\n" "$fname" "$bin_name" >> "$RESULTS_FILE"
  printf -- "- Battery temp: %s°C -> %s°C\n\n" "$temp_before" "$temp_after" >> "$RESULTS_FILE"
  if [ -n "$out" ]; then
    printf "%s\n\n" "$out" >> "$RESULTS_FILE"
  fi
  if [ "$rc" -ne 0 ]; then
    printf -- "- **Status:** TIMED OUT (>180s, exit=%d) / DISQUALIFIED\n\n" "$rc" >> "$RESULTS_FILE"
    echo "$fname [$bin_name] TIMED OUT (rc=$rc) temp: ${temp_before}C -> ${temp_after}C"
    echo "$out" | grep -E "pp256|tg64" || true
  else
    echo "$fname [$bin_name] temp: ${temp_before}C -> ${temp_after}C"
    echo "$out" | grep -E "pp256|tg64" || true
  fi
}

$ADB shell "mkdir -p $REMOTE_DIR"

if [ "${1:-}" = "--thermal-loop" ]; then
  MODEL_PATH="${2:?Pass path to winning GGUF model}"
  BIN_LOCAL="${3:-$ROOT_DIR/bench/out/bin/llama-bench}"
  FNAME="$(basename "$MODEL_PATH")"
  REMOTE_BIN="$REMOTE_DIR/llama-bench"
  REMOTE_MODEL="$REMOTE_DIR/$FNAME"
  $ADB push --sync "$BIN_LOCAL" "$REMOTE_BIN" >/dev/null
  $ADB shell "chmod +x $REMOTE_BIN"
  $ADB push "$MODEL_PATH" "$REMOTE_MODEL" >/dev/null

  echo "" >> "$RESULTS_FILE"
  echo "## Thermal Soak Test (10 minutes, 20s idle between runs): $FNAME" >> "$RESULTS_FILE"
  echo "" >> "$RESULTS_FILE"
  echo "| Iteration | Elapsed (s) | pp256 (t/s) | tg64 (t/s) | Battery Temp (°C) |" >> "$RESULTS_FILE"
  echo "| :--- | :--- | :--- | :--- | :--- |" >> "$RESULTS_FILE"

  START_TS="$(date +%s)"
  ITER=1
  while true; do
    NOW_TS="$(date +%s)"
    ELAPSED=$((NOW_TS - START_TS))
    if [ "$ELAPSED" -ge 600 ] && [ "$ITER" -gt 1 ]; then
      break
    fi
    OUT="$($ADB shell "timeout -k 5 180 taskset f0 $REMOTE_BIN -m $REMOTE_MODEL -t 4 --poll 0 --no-warmup -r 1 --delay 2 -p 256 -n 64 -o md" | tr -d '\r')"
    PP="$(echo "$OUT" | awk -F'|' '/pp256/ {gsub(/ /, "", $(NF-1)); split($(NF-1), a, "±"); print a[1]}' | head -n 1)"
    TG="$(echo "$OUT" | awk -F'|' '/tg64/ {gsub(/ /, "", $(NF-1)); split($(NF-1), a, "±"); print a[1]}' | head -n 1)"
    TEMP="$(read_batt_temp)"
    NOW_TS="$(date +%s)"
    ELAPSED=$((NOW_TS - START_TS))
    echo "| $ITER | ${ELAPSED}s | $PP | $TG | $TEMP |" >> "$RESULTS_FILE"
    echo "Thermal iter $ITER (${ELAPSED}s): pp256=$PP tg64=$TG batt=${TEMP}C"
    if [ "$ELAPSED" -ge 600 ]; then
      break
    fi
    ITER=$((ITER + 1))
    sleep 20
  done

  $ADB shell "rm -f $REMOTE_MODEL"
  exit 0
fi

BIN_GENERIC="$ROOT_DIR/bench/out/bin/llama-bench"
BIN_A73="$ROOT_DIR/bench/out-a73/bin/llama-bench"

if grep -q "^- Selected binary:" "$RESULTS_FILE" 2>/dev/null; then
  CHOSEN_LABEL="$(sed -n 's/^- Selected binary: `\([^`]*\)`.*/\1/p' "$RESULTS_FILE" | head -n 1)"
  if [ "$CHOSEN_LABEL" = "llama-bench-a73" ]; then
    CHOSEN_BIN="$BIN_A73"
  else
    CHOSEN_BIN="$BIN_GENERIC"
    CHOSEN_LABEL="llama-bench-armv8a"
  fi
  echo "Resuming with previously selected binary: $CHOSEN_LABEL"
else
  FIRST_MODEL="$(ls -1 "$MODELS_DIR"/*0.5[Bb]*.gguf 2>/dev/null | head -n 1 || ls -1 "$MODELS_DIR"/*.gguf | head -n 1)"
  if [ -z "$FIRST_MODEL" ]; then
    echo "No GGUF models found in $MODELS_DIR" >&2
    exit 1
  fi

  echo "" >> "$RESULTS_FILE"
  echo "## Compiler Flags Comparison (\`-march=armv8-a\` vs \`-mtune=cortex-a73\`)" >> "$RESULTS_FILE"
  echo "" >> "$RESULTS_FILE"

  run_one_bench "$BIN_GENERIC" "llama-bench-armv8a" "$FIRST_MODEL"
  sleep 10
  run_one_bench "$BIN_A73" "llama-bench-a73" "$FIRST_MODEL"
  sleep 10

  TG_GENERIC="$(awk '/### .* \(llama-bench-armv8a\)/{f=1} f && /tg64/{print $(NF-1); exit}' FS='|' "$RESULTS_FILE" | awk '{print $1}')"
  TG_A73="$(awk '/### .* \(llama-bench-a73\)/{f=1} f && /tg64/{print $(NF-1); exit}' FS='|' "$RESULTS_FILE" | awk '{print $1}')"

  CHOSEN_BIN="$BIN_GENERIC"
  CHOSEN_LABEL="llama-bench-armv8a"
  if awk "BEGIN {exit !($TG_A73 > $TG_GENERIC)}"; then
    CHOSEN_BIN="$BIN_A73"
    CHOSEN_LABEL="llama-bench-a73"
  fi

  echo "Selected compiler variant: $CHOSEN_LABEL (generic tg64=$TG_GENERIC vs a73 tg64=$TG_A73)"
  echo "- Selected binary: \`$CHOSEN_LABEL\` (\`-march=armv8-a\` tg64=$TG_GENERIC vs \`-mtune=cortex-a73\` tg64=$TG_A73)" >> "$RESULTS_FILE"
  echo "" >> "$RESULTS_FILE"
  echo "## Candidate LLM Benchmark Results (\`taskset f0\`, 4 threads, \`--poll 0\`)" >> "$RESULTS_FILE"
  echo "" >> "$RESULTS_FILE"
fi

for model in "$MODELS_DIR"/*.gguf; do
  [ -e "$model" ] || continue
  fname="$(basename "$model")"
  if awk '/^## Candidate LLM Benchmark Results/{ok=1} ok' "$RESULTS_FILE" 2>/dev/null | grep -Fq "### $fname ("; then
    echo "Skipping already-benchmarked candidate: $fname"
    continue
  fi
  run_one_bench "$CHOSEN_BIN" "$CHOSEN_LABEL" "$model"
  sleep 15
done
