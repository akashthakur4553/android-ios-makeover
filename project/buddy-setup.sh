#!/bin/bash
# Configure Buddy on the connected phone.
# Usage: BUDDY_KEY=<your key> ./buddy-setup.sh [provider] [model] [spec.json] [name]
#   provider: Gemini | OpenAI | Groq | OpenRouter | Anthropic | Custom   (default Gemini)
#   Optional fallback: BUDDY_FALLBACK=Groq BUDDY_FALLBACK_KEY=<key> [BUDDY_FALLBACK_MODEL=...]
#   The key is only passed to the phone (stored in the app's private settings), never saved on the computer.
cd "$(dirname "$0")"; source ./env.sh
PROVIDER="${1:-Gemini}"; MODEL="${2:-}"; SPEC="${3:-buddy-specs/me.json}"; NAME="${4:-}"
P=com.glass.launcher
adb shell pm grant $P android.permission.RECORD_AUDIO 2>/dev/null || true
# Buddy = default digital assistant -> opens on long-press Home
adb shell cmd role add-role-holder android.app.role.ASSISTANT $P 0 2>&1 | grep -v "^$" || true
echo "assistant role: $(adb shell cmd role get-role-holders android.app.role.ASSISTANT | tr -d '\r')"
ARGS=(--es setup_provider "$PROVIDER")
[ -n "${BUDDY_KEY:-}" ] && ARGS+=(--es setup_key "$BUDDY_KEY")
[ -n "$MODEL" ] && ARGS+=(--es setup_model "$MODEL")
[ -n "${BUDDY_FALLBACK:-}" ] && ARGS+=(--es setup_fallback_provider "$BUDDY_FALLBACK" --es setup_fallback_key "${BUDDY_FALLBACK_KEY:-}")
[ -n "${BUDDY_FALLBACK_MODEL:-}" ] && ARGS+=(--es setup_fallback_model "$BUDDY_FALLBACK_MODEL")
[ -n "$NAME" ] && ARGS+=(--es setup_name "$NAME")
[ -f "$SPEC" ] && ARGS+=(--es setup_spec_b64 "$(base64 < "$SPEC" | tr -d '\n')")
adb shell am start -n $P/.buddy.BuddyActivity "${ARGS[@]}" >/dev/null && echo "Buddy configured ($PROVIDER) and opened."
