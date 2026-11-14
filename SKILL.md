---
name: android-ios-makeover
description: Turn any Android phone connected over USB (ADB) into a clean, fast, iPhone-like device — security/spyware audit, user-approved debloat, privacy hardening, speed-up (incl. old phones), system-wide ad blocking, and install "Glass" (an iOS-style launcher with Liquid Glass, Dynamic Island, Control/Notification Centre, Spotlight, App Library, app switcher) plus iOS-style Photos, Camera, Clock, Calendar, Phone, Contacts, Messages, Music and Files apps. Everything is reversible and the phone's stock launcher/apps stay as a fallback. Use when a user says things like "clean my phone", "remove bloatware", "is my phone hacked", "make my phone faster", "block ads on my phone", "make my Android look like an iPhone".
---

# Android → iPhone-style makeover (Glass)

You (the AI agent) will work on the user's real phone over ADB. Follow the phases **in order**.
Each phase says exactly which command to run, what to show the user, and when you **must stop and ask**.

> **Golden rules (never break these)**
> 1. **Ask before removing anything.** Show lists, let the user pick. Default to keeping things.
> 2. **Never touch `debloat/never_remove.txt` packages.** Never remove/disable the stock launcher or stock apps that Glass replaces — they are the fallback.
> 3. **Only reversible actions**: `pm uninstall -k --user 0`, `pm disable-user`, `settings put`, `appops set`. Never use root, never flash, never wipe, never `rm` on the phone outside our own files.
> 4. **Don't tap on the phone while the user is using it.** Before automated tapping, ask them to leave it idle. If a screenshot shows an app you didn't open, pause and ask.
> 5. **Never turn off Play Protect, verification, or other security features** to make installs easier. If Play Protect asks, the user approves on the phone.
> 6. **Report honestly.** If something failed or is impossible without root, say so plainly.
> 7. **Don't put the user's personal info** (name, email, phone numbers, contacts) in commits, issues, logs you publish or URLs.

Repository layout:

```
SKILL.md                ← you are here (the runbook)
scripts/                ← every phase as a script (bash; macOS/Linux/WSL/Git-Bash)
debloat/                ← curated per-brand package lists + never_remove.txt
project/                ← full source: Glass launcher (app/) + Glass apps (apps/)
reference/              ← OEM quirks, troubleshooting, architecture, old-phone tips, YouTube ads
```

---

## Phase 0 — Set expectations (tell the user, 5 lines max)

* What will happen: health/security check → they choose what to remove → privacy + speed tweaks → ad blocking → the iPhone-style launcher and apps.
* Everything is reversible (`scripts/08_revert.sh`), the original launcher/apps are kept, and Glass auto-falls back to the stock launcher if it ever crashes.
* Without root, Android does **not** allow replacing: the lock screen, the system Settings app, the in-call screen, or the stock Recents engine (Glass covers Recents with its own switcher). Say this up front.

## Phase 1 — Computer setup

Needed: `adb` (Android platform-tools). For Phase 8 also a **JDK 17+** and the **Android SDK** (easiest: install Android Studio once and open it, which also accepts the SDK licences).

| OS | Install |
|---|---|
| macOS | `brew install --cask android-platform-tools` and (Phase 8) `brew install --cask android-studio` |
| Linux | `sudo apt install adb` (or platform-tools zip) + Android Studio tarball |
| Windows | Use **Git Bash** or **WSL**. Install Android Studio; add `%LOCALAPPDATA%\Android\Sdk\platform-tools` to PATH |

Run: `bash scripts/00_check_setup.sh` → it prints what's missing.

## Phase 2 — Connect the phone

Tell the user (adapt to their brand, see `reference/oem-quirks.md`):
1. Settings → About phone → tap **Build number** 7 times → Developer options appear.
2. Developer options → enable **USB debugging**.
3. Plug in USB, unlock the phone, tap **Allow** on the "Allow USB debugging?" prompt (tick "Always allow").

Run `bash scripts/00_check_setup.sh` again. It tells you whether ADB can **change settings** and **tap the screen**.
If not (very common on Xiaomi/Redmi/POCO, OPPO/realme, vivo), it prints the exact extra toggle for that brand.
**Xiaomi: after enabling "USB debugging (Security settings)" the phone must be REBOOTED** (`adb reboot`, ask first) — the toggle silently does nothing until then.

## Phase 3 — Health, security & spyware check (read-only)

```bash
bash scripts/01_device_report.sh ./phone-report
```
Read `phone-report/report.txt` and `phone-report/sideloaded_or_preloaded.txt`, then tell the user in plain words:

| Check | Good | Red flag → say so |
|---|---|---|
| `verifiedbootstate` | `green` | `orange`/`yellow` = unlocked/modified system |
| `flash.locked` | `1` | `0` = bootloader unlocked |
| `build.type / tags` | `user / release-keys` | `userdebug`, `test-keys` = custom/modified ROM |
| su / Magisk / Xposed | none | present = rooted (could be the user's own doing — ask) |
| Accessibility services | empty, or things the user knows (TalkBack, password manager) | **unknown app = top spyware sign** |
| Notification listeners | known apps (smartwatch, Android Auto, launcher) | unknown app can read every message |
| Device admins / Device Owner | none, or work profile/MDM the user knows | unknown admin = can lock/wipe/track |
| VPN / Private DNS / proxy | none or user's own | unknown = traffic interception |
| Install-unknown-apps | only app stores | browsers/chat apps allowed = risk |
| Sideloaded apps | — | piracy/cracked/"mod" APKs, unknown names → recommend removal |
| Storage | <85% used | >90% = slowness |
| Battery capacity | >80% of design | <75% = battery aging |

**Spyware verdict:** If integrity is clean AND no unknown accessibility/admin/listener/VPN → "no signs of compromise". Never claim certainty; say "no signs found".
Mention risky user apps (piracy streamers, free VPNs, caller-ID apps that upload contacts) but **don't remove user apps they want** (the user decides).

## Phase 4 — Debloat (USER MUST APPROVE)

1. Open `phone-report/debloat_candidates.txt` (packages found on THIS phone that are in our lists).
2. Present them to the user as a **numbered list in 3 groups**: *Ads & tracking (safe)*, *Bloat apps (optional — only if unused)*, *Their own risky apps (their call)*. One short line per item ("what it is").
3. Say what you will **not** remove (security app, push service, updater, Find My Device, stock launcher, Play Services).
4. **Wait** for the user to answer with numbers. Write the chosen package names to `approved.txt`.
5. Run `bash scripts/02_debloat.sh approved.txt` (type YES, or pass `--yes` after the user approved in chat).
6. It writes `restore-debloat-<time>.sh`. Tell the user where it is.

Unknown package not in our lists? Research it first (search "<package> safe to remove"). If unsure, don't remove.
Some OEM apps are protected from uninstall (`Failure [-1000]`) — the script automatically **disables** them instead.

## Phase 5 — Privacy hardening

```bash
bash scripts/04_privacy.sh
```
* Revokes "install unknown apps" from everything except app stores.
* Opens Google → Ads. If ADB can tap (`scripts/ui.sh texts` / `scripts/ui.sh tap "Opt out of Ads Personalisation"`), turn on the opt-out (it shows an OK dialog → tap **OK**), otherwise ask the user to do it. The page sometimes renders under its own header — use a screenshot (`scripts/ui.sh shot`) and tap by coordinates.
* Ad-engine apps (MSA, opos.ads, etc.) were already handled in Phase 4.

## Phase 6 — Speed

```bash
bash scripts/05_speed.sh                # normal phones
bash scripts/05_speed.sh --old-phone    # ≤4 GB RAM or Android ≤12 — stronger
```
Animations 0.5×, clears app caches (not data), compiles apps ahead-of-time, lists apps unused for 60+ days (**ask before uninstalling those**). See `reference/old-phones.md` for more (lighter wallpaper, Glass "lite" settings, storage cleanup, battery tips). After a big debloat a reboot helps (ask first).

## Phase 7 — Block ads everywhere

```bash
bash scripts/06_adblock_dns.sh on adguard     # or: mullvad | adguard-family | nextdns:<id>
```
Explain: all DNS lookups now go through that provider (privacy trade-off), works in every app/browser on Wi-Fi and mobile data. Undo: `scripts/06_adblock_dns.sh off`.
**YouTube app ads cannot be blocked by DNS.** If the user asks, explain the options in `reference/youtube-ads.md` (Brave browser, YouTube Premium, ReVanced — a patched app, against YouTube's terms; only set it up if the user explicitly chooses it).

## Phase 8 — Install Glass (the iPhone-style launcher + apps)

Ask the user to keep the phone **unlocked and idle** for ~10 minutes. Then:
```bash
bash scripts/07_install_glass.sh
```
This builds (first build: 5–15 min; it downloads Gradle deps; R8 is slow) and installs:

* `com.glass.launcher` — Home screen (Liquid Glass dock/widgets/folders, jiggle-mode drag & drop, App Library, Today View, Spotlight, Control Centre, Notification Centre, Dynamic Island, app switcher, crash-guard fallback).
* `com.glass.apps` — Photos, Camera, Clock, Calendar, Phone, Contacts, Messages, Music, Files (each its own icon/task).

Then it grants permissions, enables the Notification listener + the "Glass Dynamic Island" accessibility service, and makes Glass the default Home.

**What the user may need to do on the phone:**
* **Play Protect "Unsafe app blocked / Install anyway?"** → they confirm with fingerprint/PIN (expected: sideloaded app using accessibility/notification access).
* **Choose Home app** dialog → pick **Glass**, "Always".
* If the accessibility service didn't enable (Android 13+ "Restricted setting"): App info → ⋮ → **Allow restricted settings**, then Settings → Accessibility → Glass Dynamic Island → On.

**Verify (take screenshots with `scripts/ui.sh shot`, read the `_s.png`):**
1. Home shows wallpaper, widgets, icons, glass dock. Press Home on another page → returns to page 1.
2. Swipe down in the middle → Spotlight; type an app name and `12*8+4` (→100).
3. Swipe down from the top-right of the status bar → Control Centre (torch toggles; check `dumpsys media.camera | grep -i torch`). Top-left → Notification Centre.
4. Long-press an icon → menu with app shortcuts. Drag an icon → jiggle mode, reorder works. Drag onto another → folder.
5. Press Recents → Glass switcher (cards).
6. Post a test notification: `adb shell cmd notification post -t Test tag "Hello"` → it drops from the Dynamic Island and the stock pop-up does NOT also appear.
7. Open each Glass app once.
8. Measure smoothness: `adb shell dumpsys gfxinfo com.glass.launcher reset`, swipe pages 10×, then `dumpsys gfxinfo com.glass.launcher | grep -E "Janky frames:|50th|90th"` → aim for <5% janky.

**Device adaptation (Glass already handles these; check them):**
* Camera cutout anywhere (centre/corner/notch) → island auto-positions via `DisplayCutout`.
* 3-button vs gesture navigation → both work; with gestures the system home/recents gestures still apply.
* Recents: Glass detects the stock launcher's `*Recents*` activity and covers it instantly. On Pixels (recents inside the launcher itself) the stock recents stays — say so.
* Screens < 360dp wide or tablets: grid is fixed 4×6; widgets resize. If icons look cramped, Glass Settings → turn off app names.
* Old/slow GPU (see `reference/old-phones.md`): Glass Settings → **Liquid Glass Refraction OFF** (dock uses plain frosted glass).

## Phase 9 — Hand-over

Tell the user (short):
* What changed (counts: apps removed, MB freed, DNS provider, Glass installed).
* **How to go back to the original launcher**: Glass Settings → *Switch back to original launcher*, or phone Settings → Apps → Default apps → Home app → (stock launcher). Full undo: `bash scripts/08_revert.sh`.
* Safety: if Glass crashes it opens the stock launcher automatically; after 3 crashes in 2 minutes it steps aside entirely.
* Gestures cheat-sheet: swipe down (middle) Spotlight · top-right Control Centre · top-left Notification Centre · long-press icon menu · long-press empty space edit mode · swipe left from page 1 Today View · swipe right past last page App Library · tap/long-press/swipe the Dynamic Island.

---

## Reference

* `reference/oem-quirks.md` — per-brand toggles and gotchas (Xiaomi reboot, ColorOS permission monitoring, Samsung, vivo).
* `reference/troubleshooting.md` — every error we hit and its fix.
* `reference/architecture.md` — how Glass works (to modify/extend it), performance rules.
* `reference/old-phones.md` — speed playbook for 2–4 GB phones.
* `reference/youtube-ads.md` — honest options for YouTube ads.
* Rebuild after code changes: `project/deploy.sh` (launcher) / `project/deploy-apps.sh` (apps). Prebuilt fast reinstall: `project/quick-install.sh`.
  **Re-installing Glass disables its accessibility service** — the deploy scripts re-enable it; if you install manually, re-run the accessibility `settings put` lines from `deploy.sh`.
