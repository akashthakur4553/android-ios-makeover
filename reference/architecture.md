# How Glass works (for modifying/extending)

## Modules
* `project/app` — **com.glass.launcher** (targetSdk 28 on purpose: lets Control Centre toggle Wi-Fi/Bluetooth directly and read the system wallpaper; Android 15 still installs it). Kotlin + Jetpack Compose. Liquid Glass via `io.github.kyant0:backdrop`.
  * `GlassApp` — app singletons + **crash guard** (crash → launch stock launcher; 3 crashes/2 min → safe mode forwards Home to stock launcher).
  * `data/` — `AppRepository` (LauncherApps, squircle icon renderer + disk cache), `HomeStore` (pages/dock/folders/widgets, iOS flow layout, drag & drop, JSON persistence, one-time swap of stock apps → Glass apps by intent category), `WallpaperStore` (system wallpaper / photo / generated, **pre-blurred copy**), `WeatherRepo` (Open-Meteo, rounded location), `Categories` (App Library).
  * `ui/` — `HomeScreen` (pager, grid, dock, drag engine at root with `PointerEventPass.Initial`), `Glass.kt` (glass modifiers), `Widgets`, `Spotlight`, `AppLibrary`, `TodayView`, `NotificationCentre`, `ControlCentre` (+ More Controls page), `Overlays` (context menu, folders, alerts, widget gallery).
  * `services/` — `NotificationService` (listener: NC + banners + media sessions), `MediaHub`, `IslandService` (accessibility overlay: Dynamic Island, banners, status-bar gestures, Recents detection + instant cover, app snapshots for the switcher, key interception), `RecentApps`.
  * `OverlayActivity` (CC/NC over any app with window blur), `SwitcherActivity` (iOS app switcher), `SettingsActivity`.
* `project/apps` — **com.glass.apps**, one APK, 9 launcher activities with separate task affinities: Photos (MediaStore + Coil + Media3), Camera (CameraX + extensions), Clock (AlarmManager.setAlarmClock + full-screen insistent notification), Calendar (CalendarContract), Contacts/Phone (ContactsContract, CallLog, ACTION_CALL), Messages (reads SMS provider, sends with SmsManager — **not** the default SMS app, so OTPs keep working in the stock app), Music (Media3 MediaSessionService), Files (MANAGE_EXTERNAL_STORAGE + FileProvider). Shared iOS design system in `common/Ios.kt`.

## Performance rules (learned the hard way — keep them)
1. **Never read pager offset / animation values in composition.** Read them inside `graphicsLayer {}` / draw lambdas. Each overlay reads its own animation in its own small composable.
2. **No live blur on anything that moves.** `fastGlass` paints the matching patch of the *pre-blurred* wallpaper; only the static dock uses the real lens shader.
3. **No full-screen overdraw**: window background null, no root black fill, hidden layers use alpha 0.
4. Release build + R8 + `cmd package compile -m speed -f <pkg>`.
5. Measure: `dumpsys gfxinfo <pkg>` — target <5 % janky frames.

## Safety design
* Stock launcher and stock apps are never removed; Glass apps are separate packages.
* Crash guard + safe mode; heads-up restored when the island service stops; Recents cover auto-removes after 900 ms.
* `scripts/08_revert.sh` undoes everything.
