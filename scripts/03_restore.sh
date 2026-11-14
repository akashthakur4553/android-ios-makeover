#!/usr/bin/env bash
# Restore one or more removed system packages. Usage: scripts/03_restore.sh com.pkg.one [com.pkg.two ...]
source "$(dirname "$0")/lib.sh"; need_device
for p in "$@"; do adb shell cmd package install-existing "$p"; adb shell pm enable "$p" >/dev/null 2>&1; done
