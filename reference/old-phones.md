# Making old / low-RAM phones fast

Run `bash scripts/05_speed.sh --old-phone` first. Then, with the user's OK:

1. **Debloat harder** — Phase 4 optional items the user doesn't use (OEM browser, music, video, game centres, app stores that push apps).
2. **Uninstall unused apps** — the speed script lists apps not opened in 60+ days.
3. **Free storage to <80% used** — WhatsApp media (`Android/media/com.whatsapp/WhatsApp/Media`), Downloads, duplicate videos. Show the user, never delete without confirmation. (`adb shell du -sh /sdcard/* 2>/dev/null | sort -h`)
4. **Static wallpaper** (live wallpapers cost GPU) — Glass uses a static image already.
5. **Glass lite settings** (Glass Settings app): Liquid Glass Refraction **off**, Haptics optional. Everything else is already cheap: moving glass uses a pre-blurred wallpaper bitmap (no live blur).
6. **Keep background small**: the script sets `cached_apps_freezer`, fewer cached processes. Tell the user that messaging apps may need "No restrictions" battery setting to receive messages promptly.
7. **Reboot** after debloat + compile.
8. Android Go / ≤2 GB phones: skip the Glass apps (Camera/Music are heavier) and install only the launcher: `scripts/07_install_glass.sh --launcher-only`.
9. **Battery health**: if `Max learned capacity` < 75 % of design, a battery replacement is the biggest speed/stability upgrade (throttling).
