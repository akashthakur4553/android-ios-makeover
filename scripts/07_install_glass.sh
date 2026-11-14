#!/usr/bin/env bash
# Phase 8: build + install the Glass launcher and the Glass companion apps, grant permissions, make Glass the home screen.
# Usage: scripts/07_install_glass.sh [--launcher-only|--apps-only] [--no-home]
source "$(dirname "$0")/lib.sh"; need_device
cd "$ROOT/project"
WHAT="${1:-all}"
if [ "$WHAT" != "--apps-only" ]; then ./deploy.sh; fi
if [ "$WHAT" != "--launcher-only" ]; then ./deploy-apps.sh; fi
adb shell cmd package compile -m speed -f com.glass.launcher >/dev/null 2>&1 || true
if [[ " $* " != *" --no-home "* ]]; then
  adb shell cmd role add-role-holder android.app.role.HOME com.glass.launcher 0 >/dev/null 2>&1 || true
  adb shell cmd package set-home-activity com.glass.launcher/.MainActivity >/dev/null 2>&1 || true
  echo "Home role holder: $(sh1 cmd role get-role-holders android.app.role.HOME)"
fi
adb shell input keyevent KEYCODE_HOME
echo "✅ Glass installed. If the phone shows a 'Play Protect / unsafe app' prompt, the USER must approve it on the phone."
