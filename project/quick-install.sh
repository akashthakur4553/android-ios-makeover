#!/bin/bash
# Install the PREBUILT launcher + apps (no build) and re-enable everything. ~30-60 s.
cd "$(dirname "$0")"; source ./env.sh
adb wait-for-device
adb install -r app/build/outputs/apk/release/app-release.apk | tail -1
adb install -r apps/build/outputs/apk/release/apps-release.apk | tail -1
P=com.glass.launcher
for perm in READ_CALENDAR ACCESS_COARSE_LOCATION READ_CONTACTS READ_EXTERNAL_STORAGE WRITE_SECURE_SETTINGS; do adb shell pm grant $P android.permission.$perm 2>/dev/null || true; done
for op in WRITE_SETTINGS SYSTEM_ALERT_WINDOW GET_USAGE_STATS; do adb shell appops set $P $op allow; done
adb shell cmd notification allow_listener $P/$P.services.NotificationService >/dev/null 2>&1 || true
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r'); NEW="$P/$P.services.IslandService"
case "$CUR" in *"$NEW"*) V="$CUR";; null|"") V="$NEW";; *) V="$CUR:$NEW";; esac
adb shell settings put secure enabled_accessibility_services "$V"; adb shell settings put secure accessibility_enabled 1
adb shell cmd package set-home-activity $P/.MainActivity >/dev/null
A=com.glass.apps
for perm in CAMERA RECORD_AUDIO READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO READ_CALENDAR WRITE_CALENDAR READ_CONTACTS WRITE_CONTACTS READ_CALL_LOG CALL_PHONE READ_SMS SEND_SMS RECEIVE_SMS READ_PHONE_STATE POST_NOTIFICATIONS; do adb shell pm grant $A android.permission.$perm 2>/dev/null || true; done
for op in MANAGE_EXTERNAL_STORAGE USE_FULL_SCREEN_INTENT SCHEDULE_EXACT_ALARM; do adb shell appops set $A $op allow 2>/dev/null || true; done
adb shell appops set $A 10008 allow 2>/dev/null || true
adb shell input keyevent KEYCODE_HOME
echo "installed — now compiling for speed (can run while you use the phone)"
adb shell cmd package compile -m speed -f $P >/dev/null; adb shell cmd package compile -m speed -f $A >/dev/null; echo done
