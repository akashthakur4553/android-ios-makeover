#!/usr/bin/env bash
# Agent helpers for seeing/tapping the phone.
#   scripts/ui.sh shot [name]        -> saves ./shots/<name>.png and a 600px preview ./shots/<name>_s.png (read the _s file)
#   scripts/ui.sh texts              -> lists on-screen texts with their tap coordinates
#   scripts/ui.sh tap "Exact text"   -> taps the element with that text/content-desc
#   scripts/ui.sh focus              -> which app/window is in front
source "$(dirname "$0")/lib.sh"; need_device
mkdir -p ./shots
case "${1:-shot}" in
  shot) n="${2:-shot}"; adb exec-out screencap -p > "./shots/$n.png"
        (command -v sips >/dev/null && sips -Z 600 "./shots/$n.png" --out "./shots/${n}_s.png" >/dev/null) || \
        (command -v convert >/dev/null && convert "./shots/$n.png" -resize 600x600 "./shots/${n}_s.png") || cp "./shots/$n.png" "./shots/${n}_s.png"
        echo "./shots/${n}_s.png";;
  texts) adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -E 'text="[^"]+"|content-desc="[^"]+"' | \
        sed -E 's/.*text="([^"]*)".*content-desc="([^"]*)".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1|\2|\3 \4 \5 \6/' | \
        awk -F'|' '{split($3,b," "); printf "%-40s %-25s tap=%d,%d\n", $1, $2, (b[1]+b[3])/2, (b[2]+b[4])/2}';;
  tap) adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
       B=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$2\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
       [ -z "$B" ] && { echo "not found: $2"; exit 1; }; set -- $B; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); echo "tapped";;
  focus) adb shell dumpsys window | grep -E "mCurrentFocus" | head -1;;
esac
