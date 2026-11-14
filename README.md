# 📱 Android → iPhone-style makeover (an AI-agent skill)

Plug an Android phone into your computer, point your AI coding agent (Claude Code, Codex, Gemini/Antigravity, Cursor, …) at this repo, and it will:

1. **Check if the phone is hacked** — integrity, root, spyware hiding spots (accessibility, device admin, notification listeners, VPN/DNS/proxy), sideloaded apps, storage & battery health.
2. **Remove bloatware & trackers — only what you approve** (curated lists for Xiaomi/Redmi/POCO, Samsung, OPPO/realme/OnePlus, vivo/iQOO, Motorola, Google…), fully reversible.
3. **Harden privacy** and **speed it up** (incl. an old-phone mode).
4. **Block ads in every app and browser** (Private DNS).
5. **Install "Glass"** — an iPhone-style launcher: Liquid Glass dock & widgets, folders, jiggle-mode editing, App Library, Today View, Spotlight, Control Centre, Notification Centre, **Dynamic Island**, iOS-style banners and app switcher — plus iOS-style **Photos, Camera, Clock, Calendar, Phone, Contacts, Messages, Music, Files**.

Everything is reversible, the phone's original launcher and apps stay installed as a fallback, and Glass automatically falls back to the stock launcher if it ever crashes.

> Glass is an original, iOS-*inspired* design — it doesn't ship Apple's icons, fonts or artwork.

## Use it

**Claude Code**
```bash
git clone https://github.com/akashthakur4553/android-ios-makeover ~/.claude/skills/android-ios-makeover
# then in Claude Code: "my phone is connected over USB — run the android-ios-makeover skill"
```
**Codex / Gemini / Antigravity / Cursor / any agent**
```bash
git clone https://github.com/akashthakur4553/android-ios-makeover && cd android-ios-makeover
# then tell the agent: "Read SKILL.md and follow it for my phone connected over USB"
```
**By hand:** run the scripts in `scripts/` in order (00 → 08) and read `SKILL.md`.

## Requirements
* A computer with `adb` (and Android Studio / JDK 17 + Android SDK to build Glass).
* Android 10+ phone with **USB debugging** enabled (Glass apps need Android 10+, launcher works on 9+).
* No root needed.

## Undo
`bash scripts/08_revert.sh` — sets the original launcher back, restores pop-up notifications, removes Glass, resets DNS & animations. Debloated apps: run the `restore-debloat-*.sh` script that was generated.

## Limits (Android without root)
Lock screen, the Settings app, the in-call screen and the system Recents engine can't be replaced; Glass covers Recents with its own switcher where the OS allows. YouTube in-app ads can't be blocked by DNS (see `reference/youtube-ads.md`).

## Credits
Liquid Glass rendering: [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (`io.github.kyant0:backdrop`). Weather: [Open-Meteo](https://open-meteo.com). Built with Jetpack Compose, CameraX, Media3, Coil.

MIT licensed. Use at your own risk; always read what a script does before running it on your phone.
