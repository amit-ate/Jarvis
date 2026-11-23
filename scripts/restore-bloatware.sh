#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-${ANDROID_SERIAL:-}}"

ALL_PACKAGES=(
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

for pkg in "${ALL_PACKAGES[@]}"; do
  inst=$(adb ${SERIAL:+-s "$SERIAL"} shell cmd package install-existing "$pkg" 2>&1 || true)
  enab=$(adb ${SERIAL:+-s "$SERIAL"} shell pm enable "$pkg" 2>&1 || true)
  echo "$pkg -> install-existing: $inst | enable: $enab"
done
