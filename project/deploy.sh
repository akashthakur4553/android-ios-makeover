#!/bin/bash
# Build, install and (re)enable all Glass services on the connected phone.
set -e
cd "$(dirname "$0")"
source ./env.sh
./gradlew :app:assembleRelease -q 2>&1 | grep -E "^e:|FAIL" && exit 1 || true
adb install -r app/build/outputs/apk/release/app-release.apk | tail -1
P=com.glass.launcher
for perm in READ_CALENDAR ACCESS_COARSE_LOCATION READ_CONTACTS READ_EXTERNAL_STORAGE WRITE_SECURE_SETTINGS; do adb shell pm grant $P android.permission.$perm 2>/dev/null || true; done
adb shell appops set $P WRITE_SETTINGS allow; adb shell appops set $P SYSTEM_ALERT_WINDOW allow; adb shell appops set $P GET_USAGE_STATS allow
adb shell cmd notification allow_listener $P/$P.services.NotificationService >/dev/null 2>&1 || true
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
NEW="$P/$P.services.IslandService"
case "$CUR" in *"$NEW"*) V="$CUR";; null|"") V="$NEW";; *) V="$CUR:$NEW";; esac
adb shell settings put secure enabled_accessibility_services "$V"
adb shell settings put secure accessibility_enabled 1
adb shell cmd package set-home-activity $P/.MainActivity >/dev/null
echo "deployed"
