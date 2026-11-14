# Buddy — the user's 3D cartoon assistant

Buddy is a cute chibi 3D version of the user, built **procedurally with three.js** (no paid 3D services,
no downloads). It **floats on top of any app like a chat head** — the app underneath stays fully usable.
It appears on **long-press Home** (Buddy is set as the phone's digital assistant), walks in,
greets the user by voice, listens (Android speech recognition), thinks with **any LLM provider**, can look
at the screen (screenshot via the Glass accessibility service), and answers out loud with matching
emotions and moves: wave, namaste, talk, walk, laugh, cry, sad, angry, surprised, think, love, shy,
sleepy, dance, jump, clap, thumbs-up, yes, no, spin, excited, check-watch, stretch, plus tap/poke reactions.

Files: `project/app/src/main/assets/buddy/` (three.js engine + `buddy.js`), `project/app/.../buddy/` (Android: `BuddyActivity`, `Llm.kt`).
Preview the character on a computer: open `project/app/src/main/assets/buddy/index.html?act=wave` in Chrome.

## How the user controls him
* **Long-press Home** (or the Buddy icon) → floating Buddy walks in and greets.
* **Tap him** → he listens (speech recognition); ask anything. Say "what's on my screen?" and he looks.
* **Drag him** anywhere. **Drag onto the ✕** at the bottom to hide him.
* **Double-tap him** → full chat screen (type messages, settings: provider, key, model, name, screen-peeking on/off).
* The speech bubble is a separate *non-touchable* window, so it never blocks the app below.
* Voice: a deep male Google voice by default (`BuddyBrain.applyVoice` picks `en-in-x-end` / `en-us-x-iom` / `en-us-x-iol` / `en-us-x-tpd`, pitch 0.82). For a female voice, change the preferred list (e.g. `en-us-x-tpc`, `en-in-x-ena`) and pitch ~1.1.

## Architecture (for changing it)
* `assets/buddy/buddy.js` — the character + 20+ animations; `buddy.play(name)`, `setTalking/Thinking/Listening`, `setSpec(json)`, `enter()`.
* `buddy/BuddyBrain.kt` — TTS/STT, LLM call with **provider fallback**, emotion tags, screenshot on demand.
* `buddy/BuddyOverlay.kt` — the floating windows (WebView character, bubble, ✕ target), drag/tap/double-tap.
* `buddy/Llm.kt` — providers (Gemini native, Anthropic native, everything else OpenAI-compatible). `chain()` = provider in use first, then every other provider with a key; on failure/rate-limit it moves on and **sticks** with the one that works.
* Hosted by the Glass accessibility service (`IslandService.showBuddy()`), which can draw above other apps and take the screenshot. `BuddyActivity` is only the entry point (long-press Home) and the full chat screen.

### Gotchas we hit (keep these)
* In an Android WebView the page `<body>` can measure **0 px tall** → a canvas inside it is clipped to nothing. The canvas is `position:fixed` full-size when hosted in `<body>`.
* `adb shell am start --es ask 'a b c'` — wrap the whole command in quotes, or the phone's shell splits the text and only "a" arrives.
* Posting work on a closing Activity's view drops it; use `Handler(Looper.getMainLooper())`.
* `screenrecord` on many phones can't encode full resolution (use `--size 720x1640`) and **does not record audio**; stop it with its `--time-limit` (killing it leaves a 0-byte file).

## 1. Ask the user (required)
1. **A full-body photo** (front, standing) — a face-only photo works but the agent must guess the outfit.
2. **Which AI provider + key.** Any of these, free or paid:
   | Provider | Free? | Key page | Sees screenshots |
   |---|---|---|---|
   | Google Gemini | free tier (rate-limited) | aistudio.google.com → Get API key | yes |
   | Groq | free tier | console.groq.com → API Keys | depends on model |
   | OpenRouter | many `:free` models | openrouter.ai/keys | depends on model |
   | OpenAI | paid | platform.openai.com/api-keys | yes |
   | Anthropic Claude | paid | console.anthropic.com | yes |
   | Custom | any OpenAI-compatible URL (Ollama, LM Studio, Together, DeepSeek…) | — | depends |
   Offer a **fallback** provider too (e.g. Gemini main + Groq fallback): Buddy automatically switches when one fails or hits its rate limit, stays on the working one until it fails, then switches back.
3. What Buddy should call them (optional).

**Never write API keys into files, commits, the repo or logs.** Pass them to the phone only (step 4), where they live in the app's private settings.
Tell the user: the photo-derived look stays on the phone; chat text (and, if "see screen" is on, a screenshot when they ask about the screen) is sent to the AI provider they chose.

## 2. Turn the photo into an avatar spec (you, the agent, look at the photo)
Write `buddy-spec.json` (keep it out of git). Every field is optional; unknown fields are ignored.
```json
{
  "skin": "#cf9670",                        // sample the cheek colour; keep it warm & a bit lighter than the photo
  "blush": "#ff8fa3",
  "hair": { "color": "#1b1512", "style": "messy", "lean": -0.35 },
                                            // style: messy | quiff | short | curly | long | ponytail | bun | bald
                                            // lean: -0.5..0.5 which way the top sweeps (- = to the viewer's left)
  "beard": { "style": "none", "color": "#1e1714" },   // none | stubble | short | full   (many users prefer "none" — ask)
  "mustache": false,
  "eyes": { "color": "#3a2618" },
  "brows": { "color": "#1b1512", "thickness": 1.25 }, // 0.7 thin … 1.5 very thick
  "earring": "left",                        // none | left | right | both  (the PERSON's left = viewer's right)
  "earringColor": "#e3c46b",
  "glasses": false,                         // or a frame colour like "#111111"
  "top": { "type": "tshirt", "color": "#d38fb0" },    // tshirt | shirt | hoodie
  "bottom": { "type": "cargo", "color": "#2b2b2f" },  // cargo | jeans | shorts | skirt
  "shoes": { "color": "#f4f4f4", "sole": "#d9d9d9" },
  "watch": "left",                          // none | left | right (person's side)
  "watchColor": "#151515",
  "build": "slim",                          // slim | regular | broad
  "lashes": false                           // true for long lashes
}
```
Tips: pick colours from the photo (clothes are usually the strongest likeness cue), keep it cute (big eyes and blush are built in).
Beards drawn on a chibi face can look odd — show the user and let them decide; switching to `"none"` is a common request.
Preview before installing: copy `index.html`, add `<script>buddy.setSpec(<json>)</script>` after `buddy.js`, open in Chrome
(or `?act=laugh` etc. to test moods). Iterate with the user's feedback.

## 3. Install
Buddy ships inside the Glass launcher (`scripts/07_install_glass.sh` already installs it).

## 4. Configure on the phone (keys go straight to the phone)
```bash
BUDDY_KEY='<main key>' BUDDY_FALLBACK=Groq BUDDY_FALLBACK_KEY='<groq key>' \
  project/buddy-setup.sh Gemini "" buddy-spec.json "Name"
```
`buddy-setup.sh` grants the microphone, makes Buddy the **default assistant** (`cmd role add-role-holder android.app.role.ASSISTANT com.glass.launcher 0`) and sends provider/model/key/spec/name to the app via intent extras.
Models: Gemini default `gemini-2.5-flash` (fast); Groq default `openai/gpt-oss-120b` (text only). Check what a key can use:
* Gemini: `curl -s https://generativelanguage.googleapis.com/v1beta/models -H "x-goog-api-key: $KEY"`
* OpenAI-compatible: `curl -s $BASE/models -H "Authorization: Bearer $KEY"`
The user can change everything later in Buddy → ⚙.

## 5. Automation / demo hooks
* `adb shell "am start -n com.glass.launcher/.buddy.BuddyActivity --es ask 'what is on my screen?'"` — ask a question (as if spoken).
* `adb shell "am start -n com.glass.launcher/.buddy.BuddyActivity --es play dance"` — play any action.
* `--ez demo true` — also saves every spoken line (Buddy's voice + a second voice reading the question) as timestamped WAVs in
  `/sdcard/Android/data/com.glass.launcher/files/demo/` so you can build a demo video **with sound** (mux with ffmpeg, `adelay` each clip by `timestamp - recordingStart`). Turn off with `--ez demo false`.

## 6. Verify
* Long-press Home (or open the "Buddy" app): he walks in and greets by voice.
* Ask "what's on my screen?" from inside another app → he describes it (needs a vision-capable model).
* Ask him to dance / jump / tell a joke → matching animation + voice.
* If long-press Home still opens Google: phone Settings → Apps → Default apps → **Digital assistant app** → Glass/Buddy (some brands: Settings → Additional settings → Gesture shortcuts → Press and hold Home → assistant).
