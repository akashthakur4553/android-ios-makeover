# Debloat lists

Format: `package   # what it is   [safe|optional]`

* **[safe]** — ads, telemetry, stubs, duplicate stores. Removing them does not break the phone.
* **[optional]** — real apps/features some people use. Only remove if the user says they don't use it.
* `never_remove.txt` — the debloat script refuses these. Removing them can break calls, updates, Play Store, push notifications, or boot.

`scripts/01_device_report.sh` intersects these lists with what is actually installed and writes
`phone-report/debloat_candidates.txt`. **Always show that list to the user, grouped, and wait for their choice.**

Everything is removed with `pm uninstall -k --user 0` (reversible; a factory reset or the generated
restore script brings it back). Nothing touches the system partition.
