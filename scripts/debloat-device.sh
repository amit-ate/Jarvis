#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-$ANDROID_SERIAL}"

STAGE1_UNINSTALL=(
  com.amazon.appmanager
  com.facebook.services
  com.facebook.system
  com.facebook.appmanager
  com.miui.android.fashiongallery
  com.xiaomi.mipicks
  com.xiaomi.discover
  com.mipay.wallet.in
  com.mipay.wallet.id
  com.xiaomi.payment
  com.xiaomi.mirecycle
  com.xiaomi.midrop
  com.miui.smsextra
  com.android.egg
)

STAGE2_DISABLE_ONLY=(
  com.google.android.googlequicksearchbox
  com.google.android.apps.maps
  com.google.android.youtube
  com.google.android.apps.tachyon
  com.google.android.gm
  com.google.android.apps.wellbeing
  com.google.android.apps.turbo
  com.google.android.marvin.talkback
  com.google.android.feedback
  com.google.android.partnersetup
  com.google.android.printservice.recommendation
  com.android.chrome
  com.mi.android.globalminusscreen
  com.miui.notes
  com.miui.player
  com.gallery.player
  com.miui.weather2
)

echo "=== Stage 1: Uninstalling optional third-party bloat for user 0 (MIUI core packages preserved) ==="
for pkg in "${STAGE1_UNINSTALL[@]}"; do
  out=$(adb -s "$SERIAL" shell pm uninstall -k --user 0 "$pkg" 2>&1 || true)
  echo "$pkg -> $out"
done

echo "=== Stage 2: Disabling optional consumer apps only (no pm uninstall of MIUI system components) ==="
for pkg in "${STAGE2_DISABLE_ONLY[@]}"; do
  out=$(adb -s "$SERIAL" shell pm disable-user --user 0 "$pkg" 2>&1 || true)
  echo "$pkg -> $out"
done
