#!/usr/bin/env bash
# Phase 7: system-wide ad & tracker blocking via Private DNS (works in every app and browser, Wi-Fi + mobile data).
# Usage: scripts/06_adblock_dns.sh on [adguard|adguard-family|mullvad|nextdns:<id>]   |   scripts/06_adblock_dns.sh off
# Note: cannot block YouTube in-app ads (served from the same servers as the videos).
source "$(dirname "$0")/lib.sh"; need_device
case "${1:-on}" in
  off) adb shell settings put global private_dns_mode opportunistic; echo "Private DNS back to Automatic."; exit 0;;
esac
case "${2:-adguard}" in
  adguard) H=dns.adguard-dns.com;; adguard-family) H=family.adguard-dns.com;; mullvad) H=adblock.dns.mullvad.net;;
  nextdns:*) H="${2#nextdns:}.dns.nextdns.io";; *) H="$2";;
esac
can_write_settings || { echo "❌ adb can't change settings here. Ask the user: Settings → (Connection/Network) → Private DNS → hostname: $H"; exit 1; }
adb shell settings put global private_dns_specifier "$H"; adb shell settings put global private_dns_mode hostname; sleep 4
echo "Private DNS = $H"
for d in google.com doubleclick.net googleads.g.doubleclick.net app-measurement.com; do
  printf "  %-30s " "$d"; adb shell ping -c1 -W2 "$d" 2>&1 | head -1 | grep -oE "\([0-9a-f.:]+\)|unknown host" || echo "?"
done
echo "(ad domains should show 0.0.0.0 / 127.0.0.1 / unknown host; google.com should resolve normally)"
