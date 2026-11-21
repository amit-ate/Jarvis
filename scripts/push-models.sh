#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${SERIAL:-$ANDROID_SERIAL}"
ADB="adb -s $SERIAL"
SRC_DIR="$ROOT_DIR/models"
DEST_DIR="/sdcard/Jarvis/models"
RESULTS_FILE="$ROOT_DIR/bench/results.md"

if [ -z "${LLM_MODEL:-}" ]; then
  if [ -n "${1:-}" ]; then
    LLM_MODEL="$1"
  elif [ -f "$RESULTS_FILE" ]; then
    LLM_MODEL="$(awk -F= '/^LLM_MODEL=/{print $2; exit}' "$RESULTS_FILE" | tr -d ' `\r')"
  fi
fi

if [ -z "${LLM_MODEL:-}" ] || [ ! -f "$SRC_DIR/llm/$LLM_MODEL" ]; then
  echo "ERROR: Valid LLM_MODEL not found (got '${LLM_MODEL:-}'). Run Task 2 benchmark first or pass filename." >&2
  exit 1
fi

$ADB shell "mkdir -p $DEST_DIR/kws $DEST_DIR/vad $DEST_DIR/asr-moonshine $DEST_DIR/asr-zipformer $DEST_DIR/asr-parakeet $DEST_DIR/tts $DEST_DIR/vlm $DEST_DIR/embed $DEST_DIR/adapters $DEST_DIR/llm"

for sub in kws vad asr-moonshine asr-zipformer asr-parakeet tts vlm embed; do
  if [ ! -d "$SRC_DIR/$sub" ] || [ -z "$(ls -A "$SRC_DIR/$sub" 2>/dev/null)" ]; then
    echo "ERROR: Missing or empty $SRC_DIR/$sub" >&2
    exit 1
  fi
  echo "Syncing $sub -> $DEST_DIR/$sub"
  $ADB push --sync "$SRC_DIR/$sub/." "$DEST_DIR/$sub/"
done

if [ -d "$SRC_DIR/adapters" ] && [ -n "$(ls -A "$SRC_DIR/adapters" 2>/dev/null)" ]; then
  echo "Syncing adapters -> $DEST_DIR/adapters"
  $ADB push --sync "$SRC_DIR/adapters/." "$DEST_DIR/adapters/"
fi

echo "Syncing llm/$LLM_MODEL -> $DEST_DIR/llm/$LLM_MODEL"
$ADB push --sync "$SRC_DIR/llm/$LLM_MODEL" "$DEST_DIR/llm/$LLM_MODEL"

echo "=== On-Device Model Sizes ==="
$ADB shell "du -sh $DEST_DIR/*"
