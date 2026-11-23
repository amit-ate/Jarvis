#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-${ANDROID_SERIAL:-}}"
S="adb${SERIAL:+ -s $SERIAL} shell"

$S cmd appops set com.google.android.googlequicksearchbox RECORD_AUDIO ignore 2>/dev/null || true
$S am force-stop com.google.android.googlequicksearchbox 2>/dev/null || true
CUR_STAY="$($S settings get global stay_on_while_plugged_in | tr -d '\r')"
if [ "$CUR_STAY" != "3" ] && [ "$CUR_STAY" != "7" ]; then
  $S settings put global stay_on_while_plugged_in 7 || echo "Warning: MIUI blocked WRITE_SECURE_SETTINGS; toggle 'Stay awake' in Developer Options."
fi
$S dumpsys deviceidle whitelist +com.jarvis.edge || true
$S cmd appops set com.jarvis.edge RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
$S pm grant com.jarvis.edge android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
$S pm grant com.jarvis.edge android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true

echo "=== Device Prep Verification ==="
STAY_VAL="$($S settings get global stay_on_while_plugged_in | tr -d '\r')"
echo "stay_on_while_plugged_in=$STAY_VAL"
if [ "$STAY_VAL" != "3" ] && [ "$STAY_VAL" != "7" ]; then
  echo "Note: stay_on_while_plugged_in is $STAY_VAL (expected 3 or 7 — toggle 'Stay awake' in Developer Options)"
fi
HOTWORD_COUNT=$($S "dumpsys media.audio_policy | grep -c HOTWORD || true" | tr -d '\r')
echo "HOTWORD_COUNT=$HOTWORD_COUNT"
if [ "$HOTWORD_COUNT" != "0" ]; then
  echo "ERROR: HOTWORD_COUNT is $HOTWORD_COUNT (expected 0)" >&2
  exit 1
fi
$S dumpsys deviceidle whitelist | grep com.jarvis.edge || echo "com.jarvis.edge not yet installed (re-run after Task 4 install)"
