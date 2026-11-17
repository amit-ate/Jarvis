#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODELS_DIR="$ROOT_DIR/models"
LLM_DIR="$MODELS_DIR/llm"
KWS_DIR="$MODELS_DIR/kws"
VAD_DIR="$MODELS_DIR/vad"
MOONSHINE_DIR="$MODELS_DIR/asr-moonshine"
ZIPFORMER_DIR="$MODELS_DIR/asr-zipformer"
TTS_DIR="$MODELS_DIR/tts"
VLM_DIR="$MODELS_DIR/vlm"
mkdir -p "$LLM_DIR" "$KWS_DIR" "$VAD_DIR" "$MOONSHINE_DIR" "$ZIPFORMER_DIR" "$TTS_DIR" "$VLM_DIR"

download_hf() {
  local repo="$1"
  local file="$2"
  local dest="$3/$file"
  local url="https://huggingface.co/$repo/resolve/main/$file?download=true"
  if [ -s "$dest" ]; then
    echo "Already exists: $dest"
    return 0
  fi
  echo "Downloading $repo/$file ..."
  curl -fL --retry 3 -C - -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
}

download_url() {
  local url="$1"
  local dest="$2"
  if [ -s "$dest" ]; then
    echo "Already exists: $dest"
    return 0
  fi
  echo "Downloading $url ..."
  curl -fL --retry 3 -C - -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
}

download_tar_bz2() {
  local url="$1"
  local dest_dir="$2"
  local marker="$dest_dir/.extracted"
  if [ -f "$marker" ] && [ -n "$(ls -A "$dest_dir" 2>/dev/null)" ]; then
    echo "Already extracted: $dest_dir"
    return 0
  fi
  local tmp_tar="$dest_dir/archive.tar.bz2"
  echo "Downloading & extracting $url -> $dest_dir ..."
  curl -fL --retry 3 -o "$tmp_tar.part" "$url"
  mv "$tmp_tar.part" "$tmp_tar"
  tar -xjf "$tmp_tar" -C "$dest_dir" --strip-components=1
  rm -f "$tmp_tar"
  touch "$marker"
}

# Candidate LLMs (Task 2)
download_hf "Qwen/Qwen2.5-0.5B-Instruct-GGUF"       "qwen2.5-0.5b-instruct-q4_0.gguf"   "$LLM_DIR"
download_hf "Qwen/Qwen2.5-0.5B-Instruct-GGUF"       "qwen2.5-0.5b-instruct-q4_k_m.gguf" "$LLM_DIR"
download_hf "bartowski/Llama-3.2-1B-Instruct-GGUF"  "Llama-3.2-1B-Instruct-Q4_0.gguf"   "$LLM_DIR"
download_hf "bartowski/Llama-3.2-1B-Instruct-GGUF"  "Llama-3.2-1B-Instruct-Q4_K_M.gguf" "$LLM_DIR"
download_hf "bartowski/google_gemma-3-1b-it-GGUF"   "google_gemma-3-1b-it-Q4_0.gguf"    "$LLM_DIR"
download_hf "bartowski/google_gemma-3-1b-it-GGUF"   "google_gemma-3-1b-it-Q4_K_M.gguf"  "$LLM_DIR"
download_hf "LiquidAI/LFM2-1.2B-GGUF"               "LFM2-1.2B-Q4_0.gguf"               "$LLM_DIR"
download_hf "LiquidAI/LFM2-1.2B-GGUF"               "LFM2-1.2B-Q4_K_M.gguf"             "$LLM_DIR"
download_hf "Qwen/Qwen2.5-1.5B-Instruct-GGUF"       "qwen2.5-1.5b-instruct-q4_0.gguf"   "$LLM_DIR"
download_hf "Qwen/Qwen2.5-1.5B-Instruct-GGUF"       "qwen2.5-1.5b-instruct-q4_k_m.gguf" "$LLM_DIR"
download_hf "unsloth/Qwen3-1.7B-GGUF"               "Qwen3-1.7B-Q4_0.gguf"              "$LLM_DIR"
download_hf "unsloth/Qwen3-1.7B-GGUF"               "Qwen3-1.7B-Q4_K_M.gguf"            "$LLM_DIR"

echo "=== LLM Candidates Downloaded ==="
ls -lh "$LLM_DIR"

# Speech & Wake Word Models (Task 3, sherpa-onnx release assets)
download_tar_bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2" "$KWS_DIR"
cat > "$KWS_DIR/keywords.txt" <<'EOF'
▁HE Y ▁JA R VI S
▁HE Y ▁JA R V IS
▁HE Y ▁JA R V ES
▁HE Y ▁JA R V US
▁HE Y ▁ J AR VI S
▁HE Y ▁ J AR V IS
▁HA Y ▁JA R VI S
▁HA Y ▁JA R V IS
▁HI ▁JA R VI S
▁HI ▁JA R V IS
▁JA R VI S
▁JA R V IS
▁JA R V ES
▁JA R V US
▁ J AR VI S
EOF
download_url     "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" "$VAD_DIR/silero_vad.onnx"
download_tar_bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2" "$MOONSHINE_DIR"
download_tar_bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2" "$ZIPFORMER_DIR"
download_tar_bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2" "$TTS_DIR"

# Vision-Language Model (Task 3 / Task 11: SmolVLM-256M for <= 25s CPU inference on SDM636)
download_hf "ggml-org/SmolVLM-256M-Instruct-GGUF" "SmolVLM-256M-Instruct-Q8_0.gguf"        "$VLM_DIR"
download_hf "ggml-org/SmolVLM-256M-Instruct-GGUF" "mmproj-SmolVLM-256M-Instruct-Q8_0.gguf" "$VLM_DIR"

echo "=== All Model Directories ==="
du -sh "$MODELS_DIR"/*
