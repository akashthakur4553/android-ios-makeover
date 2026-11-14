#!/usr/bin/env bash
# Shared helpers. Source me:  source "$(dirname "$0")/lib.sh"
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
if ! command -v adb >/dev/null 2>&1; then
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/c/Users/${USER:-x}/AppData/Local/Android/Sdk"; do
    [ -x "$c/platform-tools/adb" ] && export PATH="$c/platform-tools:$PATH" && break
  done
fi
command -v adb >/dev/null 2>&1 || { echo "❌ adb not found. Install Android platform-tools (see SKILL.md → Phase 1)."; exit 1; }

need_device() {
  local n; n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  if [ "$n" = "0" ]; then
    echo "❌ No authorised device. Plug in USB, enable USB debugging, tap 'Allow' on the phone."; adb devices; exit 1
  elif [ "$n" != "1" ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "❌ More than one device. Set ANDROID_SERIAL=<serial>."; adb devices; exit 1
  fi
}
prop() { adb shell getprop "$1" 2>/dev/null | tr -d '\r'; }
sh1() { adb shell "$@" 2>/dev/null | tr -d '\r'; }

# Returns: xiaomi | samsung | oppo | realme | oneplus | vivo | iqoo | motorola | google | nothing | huawei | honor | other
oem() {
  local b; b=$(prop ro.product.brand | tr '[:upper:]' '[:lower:]'); local m; m=$(prop ro.product.manufacturer | tr '[:upper:]' '[:lower:]')
  case "$b$m" in
    *xiaomi*|*redmi*|*poco*) echo xiaomi;; *samsung*) echo samsung;; *oneplus*) echo oneplus;; *realme*) echo realme;; *oppo*) echo oppo;;
    *vivo*|*iqoo*) echo vivo;; *motorola*|*moto*) echo motorola;; *google*) echo google;; *nothing*) echo nothing;;
    *huawei*) echo huawei;; *honor*) echo honor;; *) echo other;;
  esac
}
can_write_settings() { adb shell settings put global glass_probe 1 >/dev/null 2>&1 && adb shell settings delete global glass_probe >/dev/null 2>&1; }
can_inject_input() { ! adb shell input keyevent KEYCODE_UNKNOWN 2>&1 | grep -q SecurityException; }
