#!/usr/bin/env bash
# Non-destructive ADB hardware & AI accelerator probe for Xiaomi Redmi Note 5 Pro (whyred / SDM636).
set -uo pipefail

SERIAL="${1:-${SERIAL:-${ANDROID_SERIAL:-}}}"
ADB="adb${SERIAL:+ -s $SERIAL}"

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found in PATH" >&2
  exit 1
fi

if ! $ADB get-state >/dev/null 2>&1; then
  echo "ERROR: ADB device '$SERIAL' not connected/authorized. Run 'adb devices'." >&2
  exit 1
fi

echo "================================================================="
echo " Jarvis Mobile Accelerator Probe (ADB serial: $SERIAL)"
echo "================================================================="

echo ""
echo "--- [1] Device Identity ---"
$ADB shell '
  echo "model=$(getprop ro.product.model) device=$(getprop ro.product.device) platform=$(getprop ro.board.platform)"
  echo "android=$(getprop ro.build.version.release) sdk=$(getprop ro.build.version.sdk) build=$(getprop ro.build.version.incremental)"
  echo "abis=$(getprop ro.product.cpu.abilist)"
'

echo ""
echo "--- [2] CPU Clusters (cpu0..3 Little A53 vs cpu4..7 Big A73) ---"
$ADB shell '
  for c in 0 1 2 3 4 5 6 7; do
    p="/sys/devices/system/cpu/cpu${c}/cpufreq"
    if [ -d "$p" ]; then
      cur=$(cat "$p/scaling_cur_freq" 2>/dev/null || echo "?")
      min=$(cat "$p/scaling_min_freq" 2>/dev/null || echo "?")
      max=$(cat "$p/scaling_max_freq" 2>/dev/null || echo "?")
      gov=$(cat "$p/scaling_governor" 2>/dev/null || echo "?")
      echo "cpu${c}: cur=${cur}kHz min=${min}kHz max=${max}kHz gov=${gov}"
    else
      echo "cpu${c}: cpufreq unavailable (offline or restricted)"
    fi
  done
'

echo ""
echo "--- [3] Thermal Zones ---"
$ADB shell '
  for z in /sys/class/thermal/thermal_zone*; do
    [ -e "$z/type" ] || continue
    t=$(cat "$z/type" 2>/dev/null || echo "?")
    v=$(cat "$z/temp" 2>/dev/null || echo "?")
    echo "$(basename "$z") (${t}): ${v}"
  done
'

echo ""
echo "--- [4] Memory (/proc/meminfo) & ZRAM ---"
$ADB shell '
  grep -E "^(MemTotal|MemFree|MemAvailable|Buffers|Cached|SwapTotal|SwapFree|CmaTotal|CmaFree):" /proc/meminfo
  if [ -e /sys/block/zram0/disksize ]; then
    echo "zram0 disksize=$(cat /sys/block/zram0/disksize 2>/dev/null) comp_algo=$(cat /sys/block/zram0/comp_algorithm 2>/dev/null)"
    [ -e /sys/block/zram0/mm_stat ] && echo "zram0 mm_stat=$(cat /sys/block/zram0/mm_stat 2>/dev/null)"
  fi
'

echo ""
echo "--- [5] Adreno GPU (OpenCL / Vulkan / GLES) ---"
$ADB shell '
  ls -la /vendor/lib64/libOpenCL.so /vendor/lib64/hw/vulkan.*.so /vendor/lib64/egl/libGLESv2_adreno.so /vendor/lib64/egl/libEGL_adreno.so 2>/dev/null || true
  pm list features | grep -E "vulkan|opengles" || true
  dumpsys SurfaceFlinger 2>/dev/null | grep -iE "GLES:" | head -n 3 || true
'

echo ""
echo "--- [6] Hexagon DSP (/vendor/dsp & adsprpcd) ---"
$ADB shell '
  echo "init.svc.adsprpcd=$(getprop init.svc.adsprpcd) init.svc.adsprpcd_root=$(getprop init.svc.adsprpcd_root)"
  ls -la /vendor/lib64/libcdsprpc.so /vendor/lib64/libadsprpc.so /vendor/lib64/libsdsprpc.so /vendor/lib64/libfastcvadsp_stub.so 2>/dev/null || true
  ls -la /vendor/dsp 2>/dev/null | head -n 25 || true
'

echo ""
echo "--- [7] Android NNAPI ---"
$ADB shell 'ls -la /system/lib64/libneuralnetworks.so /vendor/lib64/libneuralnetworks*.so 2>/dev/null || true'

echo ""
echo "--- [8] Cameras ---"
$ADB shell '
  dumpsys media.camera 2>/dev/null | grep -E "Number of camera devices|Camera [0-9]|Facing|Flash unit|HAL" | head -n 30 || true
'

echo ""
echo "--- [9] Consumer IR Hardware Feature ---"
$ADB shell 'pm list features | grep -i consumerir || echo "consumerir feature not found"'

echo ""
echo "--- [10] Sensors Summary ---"
$ADB shell '
  dumpsys sensorservice 2>/dev/null | grep -E "Total [0-9]+|h/w sensors|^[0-9]+\\)" | head -n 45 || true
'

echo ""
echo "--- [11] /sdcard/Jarvis/models/ Inventory ---"
$ADB shell '
  if [ -d /sdcard/Jarvis/models ]; then
    du -sh /sdcard/Jarvis/models/* 2>/dev/null || true
    ls -lhR /sdcard/Jarvis/models/ 2>/dev/null | head -n 80 || true
  else
    echo "/sdcard/Jarvis/models does not exist yet"
  fi
'
