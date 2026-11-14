#!/usr/bin/env bash
# Phase 1/2: verify the computer + phone are ready. Safe, read-only.
source "$(dirname "$0")/lib.sh"
echo "== Computer =="
echo "adb: $(command -v adb) ($(adb version | head -1))"
( source "$ROOT/project/env.sh" >/dev/null 2>&1 && echo "JDK: $JAVA_HOME" && echo "SDK: $ANDROID_HOME" ) || echo "⚠️  JDK/SDK not ready — needed only for building the launcher (Phase 8)."
echo; echo "== Phone =="
need_device
O=$(oem)
echo "Phone: $(prop ro.product.brand) $(prop ro.product.marketname)$(prop ro.product.model) | Android $(prop ro.build.version.release) (SDK $(prop ro.build.version.sdk)) | OEM family: $O"
if can_write_settings; then echo "✅ adb can change settings"; else echo "❌ adb cannot change settings"; fi
if can_inject_input; then echo "✅ adb can tap/swipe"; else echo "❌ adb cannot tap/swipe"; fi
if ! can_write_settings || ! can_inject_input; then
  case $O in
    xiaomi) echo "➡  Xiaomi/Redmi/POCO: Developer options → enable 'USB debugging (Security settings)' (needs SIM + Mi account) AND 'Install via USB', then REBOOT the phone (the toggle only applies after a reboot).";;
    oppo|realme|oneplus) echo "➡  ColorOS/realme UI/OxygenOS: Developer options → enable 'Disable permission monitoring' (if present), then replug USB.";;
    vivo) echo "➡  vivo/iQOO: Developer options → enable 'USB debugging (Security settings)', then replug.";;
    *) echo "➡  Toggle USB debugging off/on and replug; on some phones a reboot is needed.";;
  esac
fi
