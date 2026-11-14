#!/usr/bin/env bash
# Phase 5: privacy hardening that is safe on every phone.
#  - stops non-store apps (browsers, chat apps, file managers) from installing APKs
#  - opens the Google Ads page so the agent/user can tap "Delete/Opt out of advertising ID"
source "$(dirname "$0")/lib.sh"; need_device
echo "Apps currently allowed to install other apps:"
A=(); while read -r p; do [ -n "$p" ] && A+=("$p"); done < <(sh1 appops query-op REQUEST_INSTALL_PACKAGES allow)
STORES="com.android.vending|com.google.android.packageinstaller|com.android.packageinstaller|com.sec.android.app.samsungapps|com.xiaomi.market|com.heytap.market|com.vivo.appstore|com.huawei.appmarket"
for p in "${A[@]}"; do
  [ -z "$p" ] && continue
  if [[ "$p" =~ ^($STORES)$ ]]; then echo "  keep  $p (app store)"; else adb shell appops set "$p" REQUEST_INSTALL_PACKAGES deny && echo "  revoked $p"; fi
done
echo; echo "Opening Google → Ads. On the phone: turn ON 'Opt out of Ads Personalisation' or tap 'Delete advertising ID'."
adb shell am start -a com.google.android.gms.settings.ADS_PRIVACY >/dev/null 2>&1 || echo "(Ads page not available on this phone)"
