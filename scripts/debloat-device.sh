#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-$ANDROID_SERIAL}"

STAGE1_UNINSTALL=(
  com.amazon.appmanager
  com.facebook.services
  com.facebook.system
  com.facebook.appmanager
  com.miui.msa.global
  com.miui.analytics
  com.miui.bugreport
  com.miui.yellowpage
  com.miui.android.fashiongallery
  com.miui.hybrid
  com.miui.hybrid.accessory
  com.xiaomi.mipicks
  com.xiaomi.discover
  com.android.updater
  com.mipay.wallet.in
  com.mipay.wallet.id
  com.xiaomi.payment
  com.xiaomi.mirecycle
  com.miui.cleanmaster
  com.xiaomi.midrop
  com.miui.miservice
  com.miui.smsextra
  com.android.egg
)

STAGE2_DISABLE_OR_UNINSTALL=(
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

STAGE3_DISABLE_OR_UNINSTALL=(
  com.miui.powerkeeper
  com.xiaomi.joyose
  com.miui.mishare.connectivity
  com.xiaomi.mi_connect_service
  com.milink.service
  com.xiaomi.miplay_client
  com.xiaomi.upnp
  com.miui.cloudbackup
  com.miui.micloudsync
  com.miui.touchassistant
)

echo "=== Stage 1: Uninstalling third-party bloat, ads & telemetry for user 0 ==="
for pkg in "${STAGE1_UNINSTALL[@]}"; do
  out=$(adb -s "$SERIAL" shell pm uninstall -k --user 0 "$pkg" 2>&1 || true)
  echo "$pkg -> $out"
done

echo "=== Stage 2 & 3: Disabling consumer apps & MIUI killers (fallback to uninstall --user 0) ==="
for pkg in "${STAGE2_DISABLE_OR_UNINSTALL[@]}" "${STAGE3_DISABLE_OR_UNINSTALL[@]}"; do
  if out=$(adb -s "$SERIAL" shell pm disable-user --user 0 "$pkg" 2>&1); then
    echo "$pkg -> $out"
  else
    fallback=$(adb -s "$SERIAL" shell pm uninstall -k --user 0 "$pkg" 2>&1 || true)
    echo "$pkg -> disable failed ($out); uninstall -> $fallback"
  fi
done
