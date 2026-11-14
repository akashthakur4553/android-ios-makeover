#!/usr/bin/env bash
# Phase 4: remove (for the current user) ONLY the packages the human approved.
# Usage: scripts/02_debloat.sh approved.txt [--yes]
#   approved.txt: one package per line (anything after a space/# is ignored)
# Safe: uses `pm uninstall -k --user 0` (keeps the APK on the system partition) and writes a restore script.
# Packages in debloat/never_remove.txt are refused.
source "$(dirname "$0")/lib.sh"; need_device
LIST="${1:?usage: 02_debloat.sh approved.txt [--yes]}"
NEVER="$ROOT/debloat/never_remove.txt"
STAMP=$(date +%Y%m%d-%H%M%S); RESTORE="./restore-debloat-$STAMP.sh"
PKGS=(); while read -r p _; do [ -n "$p" ] && PKGS+=("$p"); done < <(grep -vE '^\s*(#|$)' "$LIST")
echo "Will remove ${#PKGS[@]} packages for user 0:"; printf '  %s\n' "${PKGS[@]}"
if [ "${2:-}" != "--yes" ]; then read -r -p "Type YES to continue: " a; [ "$a" = "YES" ] || { echo "Cancelled."; exit 1; }; fi
{ echo '#!/usr/bin/env bash'; echo "# Restores packages removed on $STAMP (system apps come back instantly; Play-store apps must be reinstalled from Play)"; } > "$RESTORE"
for p in "${PKGS[@]}"; do
  if grep -qxF "$p" <(grep -vE '^\s*(#|$)' "$NEVER" | awk '{print $1}'); then echo "⛔ $p is on never_remove list — skipped"; continue; fi
  echo "adb shell cmd package install-existing $p" >> "$RESTORE"
  r=$(adb shell pm uninstall -k --user 0 "$p" 2>&1 | tr -d '\r')
  if [[ "$r" == Success* ]]; then echo "✅ $p removed"
  else
    r2=$(adb shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')
    if [[ "$r2" == *disabled* ]]; then echo "🟡 $p could not be uninstalled ($r) → disabled instead"; echo "adb shell pm enable $p" >> "$RESTORE"
    else echo "❌ $p: $r / $r2"; fi
  fi
done
chmod +x "$RESTORE"; echo; echo "Restore script: $RESTORE"
