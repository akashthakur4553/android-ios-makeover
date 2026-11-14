#!/usr/bin/env bash
# Put everything back. Usage: scripts/08_revert.sh [--keep-apps] [--keep-dns]
source "$(dirname "$0")/lib.sh"; need_device
STOCK=$(sh1 cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep / | grep -vE "glass|com.android.settings|FallbackHome" | head -1 | cut -d/ -f1)
[ -z "$STOCK" ] && STOCK=$(sh1 pm list packages | sed 's/package://' | grep -E "globallauncher|miui.home|sec.android.app.launcher|nexuslauncher|launcher3|oplus.launcher|bbk.launcher2|motorola.launcher3|trebuchet|lawnchair" | head -1)
echo "Stock launcher: $STOCK"
adb shell cmd role add-role-holder android.app.role.HOME "$STOCK" 0 >/dev/null 2>&1
adb shell settings put global heads_up_notifications_enabled 1
adb shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
if [[ " $* " != *" --keep-apps "* ]]; then adb uninstall com.glass.apps >/dev/null 2>&1; adb uninstall com.glass.launcher >/dev/null 2>&1; echo "Glass apps uninstalled."; fi
if [[ " $* " != *" --keep-dns "* ]]; then adb shell settings put global private_dns_mode opportunistic; fi
for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $k 1.0; done
adb shell input keyevent KEYCODE_HOME
echo "✅ Reverted. Debloated apps can be restored with the restore-debloat-*.sh script that 02_debloat.sh wrote."
