#!/usr/bin/env bash
# Phase 3: full read-only health/security/privacy report + debloat candidates.
# Usage: scripts/01_device_report.sh [out_dir]   (default ./phone-report)
source "$(dirname "$0")/lib.sh"; need_device
OUT="${1:-./phone-report}"; mkdir -p "$OUT"
O=$(oem)
{
echo "### DEVICE"
echo "brand=$(prop ro.product.brand) model=$(prop ro.product.model) name=$(prop ro.product.marketname) device=$(prop ro.product.device) oem_family=$O"
echo "android=$(prop ro.build.version.release) sdk=$(prop ro.build.version.sdk) security_patch=$(prop ro.build.version.security_patch)"
echo "skin: miui=$(prop ro.miui.ui.version.name) hyperos=$(prop ro.mi.os.version.name) oneui=$(prop ro.build.version.oneui) coloros=$(prop ro.build.version.opporom) oxygen=$(prop ro.oxygen.version) funtouch=$(prop ro.vivo.os.version)"
echo "fingerprint=$(prop ro.build.fingerprint)"
echo; echo "### INTEGRITY (green/locked/user/release-keys = good)"
echo "verifiedbootstate=$(prop ro.boot.verifiedbootstate) flash.locked=$(prop ro.boot.flash.locked) vbmeta=$(prop ro.boot.vbmeta.device_state) build.type=$(prop ro.build.type) tags=$(prop ro.build.tags) debuggable=$(prop ro.debuggable)"
echo "su binaries: $(sh1 'ls /system/bin/su /system/xbin/su /sbin/su 2>/dev/null' | tr '\n' ' ')"
echo "magisk-ish packages: $(sh1 pm list packages | grep -iE 'magisk|supersu|kingroot|kingo|xposed|lsposed' | tr '\n' ' ')"
echo; echo "### SPYWARE CHECKPOINTS (anything unknown here is a red flag)"
echo "accessibility_services=$(sh1 settings get secure enabled_accessibility_services)"
echo "notification_listeners=$(sh1 settings get secure enabled_notification_listeners)"
echo "device_admins:"; sh1 dumpsys device_policy | grep -E "admin=|Device Owner|Profile Owner|ComponentInfo" | head -20
echo "input_methods=$(sh1 settings get secure enabled_input_methods)"
echo "default_ime=$(sh1 settings get secure default_input_method)"
echo "always_on_vpn=$(sh1 settings get secure always_on_vpn_app)  private_dns=$(sh1 settings get global private_dns_mode)/$(sh1 settings get global private_dns_specifier)  http_proxy=$(sh1 settings get global http_proxy)"
echo "apps allowed to install other apps:"; sh1 appops query-op REQUEST_INSTALL_PACKAGES allow
echo "apps that can read SMS:"; sh1 appops query-op READ_SMS allow | tr '\n' ' '; echo
echo "apps that can draw over others:"; sh1 appops query-op SYSTEM_ALERT_WINDOW allow | tr '\n' ' '; echo
echo "developer_options=$(sh1 settings get global development_settings_enabled) adb_wifi=$(sh1 settings get global adb_wifi_enabled)"
echo; echo "### HEALTH"
echo "storage: $(sh1 df -h /data | tail -1)"
sh1 dumpsys battery | grep -E "level|health|temperature"
sh1 dumpsys batterystats | grep -m2 -iE "Estimated battery capacity|Max learned"
echo "ram: $(sh1 cat /proc/meminfo | head -3 | tr '\n' ' ')"
echo "uptime: $(sh1 uptime)"
echo "recent crashes (24h):"; sh1 dumpsys dropbox | grep -oE "(data_app_crash|data_app_anr|system_app_crash|system_app_anr|SYSTEM_TOMBSTONE)" | sort | uniq -c
echo; echo "### DISPLAY"
echo "size=$(sh1 wm size) density=$(sh1 wm density) nav_mode=$(sh1 settings get secure navigation_mode) (0=3-button,2=gestures)"
echo "home_launcher: $(sh1 cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | grep -m1 packageName)"
} > "$OUT/report.txt" 2>&1

# packages + installer (sideloaded = not com.android.vending)
sh1 pm list packages -3 -i | sed 's/package://' | sort > "$OUT/user_apps_with_installer.txt"
grep -vE "installer=com.android.vending" "$OUT/user_apps_with_installer.txt" > "$OUT/sideloaded_or_preloaded.txt"
sh1 pm list packages -s | sed 's/package://' | sort > "$OUT/system_apps.txt"
sh1 pm list packages -d | sed 's/package://' | sort > "$OUT/disabled_apps.txt"
cat "$OUT/user_apps_with_installer.txt" | cut -d' ' -f1 > "$OUT/user_apps.txt"
cat "$OUT/system_apps.txt" "$OUT/user_apps.txt" | sort -u > "$OUT/all_apps.txt"

# debloat candidates = packages present on THIS phone that appear in our curated lists
: > "$OUT/debloat_candidates.txt"
for list in "$ROOT/debloat/common.txt" "$ROOT/debloat/$O.txt"; do
  [ -f "$list" ] || continue
  grep -vE '^\s*(#|$)' "$list" | while read -r pkg rest; do
    grep -qx "$pkg" "$OUT/all_apps.txt" && echo "$pkg $rest" >> "$OUT/debloat_candidates.txt"
  done
done
echo "Report written to $OUT/ :"; ls "$OUT"
echo; echo "Debloat candidates found on this phone: $(wc -l < "$OUT/debloat_candidates.txt")"
