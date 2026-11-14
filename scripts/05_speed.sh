#!/usr/bin/env bash
# Phase 6: make the phone feel faster. Safe and reversible.
# Usage: scripts/05_speed.sh [--old-phone]
#   --old-phone : stronger tweaks for 2-4 GB RAM / Android 8-12 phones
source "$(dirname "$0")/lib.sh"; need_device
OLD=0; [ "${1:-}" = "--old-phone" ] && OLD=1
echo "Before: anim=$(sh1 settings get global window_animation_scale)/$(sh1 settings get global transition_animation_scale)/$(sh1 settings get global animator_duration_scale) free=$(sh1 df -h /data | tail -1 | awk '{print $4}')"
if can_write_settings; then
  adb shell settings put global window_animation_scale 0.5
  adb shell settings put global transition_animation_scale 0.5
  adb shell settings put global animator_duration_scale $([ $OLD = 1 ] && echo 0.5 || echo 1.0)
  if [ $OLD = 1 ]; then
    adb shell settings put global cached_apps_freezer enabled 2>/dev/null                 # Android 11+: freeze idle background apps
    adb shell device_config put activity_manager max_cached_processes 24 2>/dev/null       # fewer apps kept in RAM (default 32-60)
    adb shell settings put global app_standby_enabled 1 2>/dev/null
    adb shell settings put global adaptive_battery_management_enabled 1 2>/dev/null
    adb shell settings put secure lockscreen.lockscreen_animations 0 2>/dev/null
  fi
else
  echo "⚠️  Can't change settings over adb on this phone (see 00_check_setup.sh) — skipping animation tweaks."
fi
echo "Clearing app caches (does not delete data)…"; adb shell pm trim-caches 999G
echo "Optimising (compiling) apps — can take several minutes…"; adb shell cmd package bg-dexopt-job >/dev/null 2>&1 || adb shell cmd package compile -m speed-profile -a >/dev/null 2>&1
echo "After: free=$(sh1 df -h /data | tail -1 | awk '{print $4}')"
echo; echo "Apps not opened in 60+ days (candidates to uninstall — ASK the user first):"
NOW=$(date +%s)
sh1 dumpsys usagestats | grep -E "package=.*lastTimeUsed=" | sed -E 's/.*package=([^ ]+).*lastTimeUsed="?([0-9-]+ [0-9:]+)"?.*/\1 \2/' | sort -u -k1,1 | while read -r p d t; do
  ts=$(date -j -f "%Y-%m-%d %H:%M:%S" "$d $t" +%s 2>/dev/null || date -d "$d $t" +%s 2>/dev/null || echo "$NOW")
  [ $(( (NOW - ts) / 86400 )) -gt 60 ] && grep -qx "$p" <(sh1 pm list packages -3 | sed 's/package://') && echo "  $p (last used $d)"
done
echo; echo "Undo animation tweaks: adb shell settings put global window_animation_scale 1; ... transition_animation_scale 1; ... animator_duration_scale 1"
