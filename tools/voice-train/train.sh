#!/usr/bin/env bash
# Fine-tune Jarvis's speech recogniser on your own voice and install it on the phone if it is better.
#
#   1. On the phone: CHAT -> Train voice, read the sentences (30+ is a good start; more is better).
#   2. Phone on USB with adb, then:  tools/voice-train/train.sh            (add --use-turns to also learn from
#      recordings of your everyday requests; their labels are what Jarvis heard, so check them first)
#
# Needs Python 3.10+ with: nemo_toolkit[asr] onnx onnxruntime sherpa-onnx jiwer   (PY=/path/to/python to choose one)
# Training runs on the laptop CPU (Apple GPU/MPS lacks the transducer loss); ~20-40 s per epoch for 100 recordings.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$HOME/.cache/jarvis-voice-train}
# Training environment (NeMo etc.) set up once in ~/.cache/jarvis-voice-train/venv; PY overrides it.
if [ -z "${PY:-}" ] && [ -x "$HOME/.cache/jarvis-voice-train/venv/bin/python" ]; then
  PY="$HOME/.cache/jarvis-voice-train/venv/bin/python"
fi
PY=${PY:-python3}
EPOCHS=${EPOCHS:-10}
mkdir -p "$WORK"

echo "== pulling recordings"
rm -rf "$WORK/samples" "$WORK/base"
adb pull /sdcard/Jarvis/voice_samples "$WORK/samples" >/dev/null
adb pull /sdcard/Jarvis/models/asr-parakeet "$WORK/base" >/dev/null
"$PY" "$HERE/prepare.py" "$WORK/samples" "$WORK/data" --check-model "$WORK/base" "$@"

echo "== stock recogniser on held-out sentences"
"$PY" "$HERE/evaluate.py" "$WORK/base" "$WORK/data/test.json" --show | tee "$WORK/base.json"

echo "== fine-tuning ($EPOCHS epochs)"
"$PY" "$HERE/finetune.py" "$WORK/data" "$WORK/custom.nemo" --epochs "$EPOCHS" > "$WORK/finetune.log" 2>&1 \
  || { tail -30 "$WORK/finetune.log"; echo "fine-tuning failed (full log: $WORK/finetune.log)"; exit 1; }
rm -rf "$WORK/asr-custom"
"$PY" "$HERE/export.py" "$WORK/custom.nemo" "$WORK/asr-custom" > "$WORK/export.log" 2>&1 \
  || { tail -30 "$WORK/export.log"; echo "export failed (full log: $WORK/export.log)"; exit 1; }

echo "== fine-tuned recogniser on held-out sentences"
"$PY" "$HERE/evaluate.py" "$WORK/asr-custom" "$WORK/data/test.json" --show | tee "$WORK/custom.json"

if "$PY" - "$WORK/base.json" "$WORK/custom.json" <<'EOF'
import json, sys
base, custom = (json.loads(open(p).read().strip().splitlines()[-1]) for p in sys.argv[1:3])
print(f"stock WER {base['wer']:.3f}  ->  fine-tuned WER {custom['wer']:.3f}  ({custom['n']} held-out recordings)")
sys.exit(0 if custom["n"] >= 5 and custom["wer"] <= base["wer"] else 1)
EOF
then
  echo "== installing on the phone (models/asr-custom) and restarting Jarvis"
  adb shell rm -rf /sdcard/Jarvis/models/asr-custom
  adb push "$WORK/asr-custom" /sdcard/Jarvis/models/ >/dev/null
  adb shell am force-stop com.jarvis.edge
  adb shell monkey -p com.jarvis.edge -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "done: Jarvis now uses the recogniser tuned to your voice (delete /sdcard/Jarvis/models/asr-custom to undo)"
else
  echo "not installed: the fine-tuned model was not better on held-out sentences (record more and retry)"
fi
