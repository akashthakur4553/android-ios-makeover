# OEM quirks (read the section for the user's brand)

## Xiaomi / Redmi / POCO (MIUI, HyperOS)
* ADB can't change settings or tap until **Developer options → "USB debugging (Security settings)"** is ON (needs SIM + Mi account) **and the phone is rebooted**. Also enable **"Install via USB"**.
* Debug symptom: `SecurityException: ... WRITE_SECURE_SETTINGS` / `INJECT_EVENTS` even though the toggle is on → reboot.
* `pm uninstall` of some apps returns `Failure [-1000]` (protected, e.g. Facemoji) → `pm disable-user --user 0` works.
* Recents is `com.mi.android.globallauncher/com.miui.home.recents.RecentsActivity` → Glass covers it.
* HyperOS ignores global heads-up off for some apps; Glass banners still work.
* Alarms: give the Glass apps **Autostart** (`appops set com.glass.apps 10008 allow` — done by deploy script) and battery "No restrictions".
* Stray "POCO Launcher" card in recents after switching launchers: `adb shell dumpsys activity recents`, then `adb shell am stack remove <taskId>` for the old launcher's standard task.

## Samsung (One UI)
* ADB settings/taps work out of the box.
* Recents lives inside One UI Home (`com.sec.android.app.launcher`), class contains `RecentsActivity`/`QuickstepActivity` on most versions → Glass covers it; if not, stock recents remains.
* Some Samsung apps (Bixby) can only be disabled. Keep Knox, Device Care (`com.samsung.android.lool`), Software update (`com.sec.android.soagent`, `com.wssyncmldm`).
* "Auto Blocker" (One UI 6+) blocks sideloading/USB installs → user must turn it off in Settings → Security & privacy → Auto Blocker during install, then back on.

## OPPO / realme / OnePlus (ColorOS, realme UI, OxygenOS 12+)
* Developer options → **Disable permission monitoring** must be ON for `settings put` / `pm grant` on many versions.
* Installs via USB may require an on-screen confirmation and the OPPO account password.
* Keep `com.oplus.ota`, `com.coloros.safecenter`, `com.oplus.securitypermission`.

## vivo / iQOO
* Developer options → **USB debugging (Security settings)** for taps/settings.
* Installs over USB show an on-phone prompt; "Install via USB" may need enabling.
* Keep `com.vivo.permissionmanager`, `com.iqoo.secure`.

## Google Pixel / Motorola / Nothing / stock-ish
* Everything works over ADB. Recents is part of the launcher process (no separate activity) → Glass's switcher only appears via its own gestures; stock recents stays on the recents button.
* Android 13+ may mark the accessibility service as a **Restricted setting** for sideloaded apps → App info → ⋮ → Allow restricted settings (ADB `settings put secure enabled_accessibility_services` bypasses this).

## Huawei / Honor (no Google services)
* Glass works, but Google-dependent bits (Ads opt-out page, Geocoder city name) are skipped automatically.
