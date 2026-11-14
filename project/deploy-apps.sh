#!/bin/bash
# Build + install the Glass companion apps and grant everything they need.
set -e
cd "$(dirname "$0")"
source ./env.sh
./gradlew :apps:assembleRelease -q
adb install -r apps/build/outputs/apk/release/apps-release.apk | tail -1
P=com.glass.apps
for perm in CAMERA RECORD_AUDIO READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO READ_CALENDAR WRITE_CALENDAR READ_CONTACTS WRITE_CONTACTS READ_CALL_LOG CALL_PHONE READ_SMS SEND_SMS RECEIVE_SMS READ_PHONE_STATE POST_NOTIFICATIONS; do
  adb shell pm grant $P android.permission.$perm 2>/dev/null || true
done
for op in MANAGE_EXTERNAL_STORAGE USE_FULL_SCREEN_INTENT SCHEDULE_EXACT_ALARM; do adb shell appops set $P $op allow 2>/dev/null || true; done
adb shell appops set $P 10008 allow 2>/dev/null || true          # Xiaomi/HyperOS "Autostart" (harmless elsewhere)
adb shell cmd deviceidle whitelist +$P >/dev/null 2>&1 || true   # let alarms fire on time
adb shell cmd package compile -m speed -f $P >/dev/null 2>&1 || true
echo "apps deployed"
