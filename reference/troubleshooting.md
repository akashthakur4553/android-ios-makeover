# Troubleshooting (all of these were hit while building this skill)

| Symptom | Cause | Fix |
|---|---|---|
| `SecurityException ... WRITE_SECURE_SETTINGS` / `INJECT_EVENTS` | OEM blocks ADB | See oem-quirks.md (Xiaomi: security toggle + **reboot**) |
| `INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed` | Play Protect prompt timed out | Re-run install; user approves "Install anyway" on the phone (fingerprint) |
| `INSTALL_FAILED_USER_RESTRICTED` | "Install via USB" off | Enable it in Developer options |
| Glass Dynamic Island disappeared after an update | Android disables a11y services on reinstall | Re-run `project/deploy.sh` (re-enables it) |
| Home button opens a blank chooser / old launcher | home role reset | `adb shell cmd package set-home-activity com.glass.launcher/.MainActivity` |
| Home press doesn't go back to page 1 | OEM pauses launcher before delivering intent | Already handled (focus/recent-pause check in MainActivity) |
| Duplicate notifications (stock pop-up + Glass banner) | system heads-up still on | Glass turns `heads_up_notifications_enabled` off while running (needs WRITE_SECURE_SETTINGS granted by deploy) |
| Stock Recents flashes before Glass switcher | event batching | `notificationTimeout="0"` + instant cover overlay (already in code) |
| Some icons blank (e.g. dialer, gallery) | OEM icons use theme attrs or launcher-only themes | Icons are re-rendered with the app's theme; Glass draws its own icons for Phone/Contacts/Clock/Calendar |
| Swipes janky | recomposition / live blur | Keep the perf rules in architecture.md; measure with gfxinfo |
| Today View / App Library bottom blank | pager not full-screen | Pages must be full-screen with the dock floating on top (already fixed) |
| Call log / SMS / Files lists empty | Android 11+ providers reject `LIMIT` in sortOrder | Never put `LIMIT` in sortOrder; limit in code |
| Build: `requires compileSdk 37` | new AndroidX/Compose | compileSdk = 37 (AGP auto-downloads if licences accepted) |
| Build: Kotlin metadata version errors | backdrop lib built with Kotlin 2.4 | Use Kotlin compose plugin 2.4.x with AGP 9.x |
| `JVM signature clash setX` | Kotlin property setter vs function | Don't name functions `setFoo` next to a `var foo` |
| Phone disconnects mid-build | USB/battery | Builds don't need the phone; `project/quick-install.sh` installs prebuilt APKs in ~1 min |
