package com.glass.launcher.buddy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.glass.launcher.services.IslandService
import com.glass.launcher.ui.Ios
import com.glass.launcher.ui.frosted
import com.glass.launcher.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale

/**
 * Buddy: the user's cute 3D cartoon twin. Opens on long-press Home (default assistant), greets by voice,
 * listens, thinks with any LLM provider, sees the screen, and answers with matching emotions/moves.
 */
class BuddyActivity : ComponentActivity() {
    private lateinit var cfg: BuddyConfig
    private var web: WebView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stt: SpeechRecognizer? = null
    private var screenshot: ByteArray? = null
    private var screenshotUsed = false
    private val history = mutableStateListOf<Msg>()

    private var bubble by mutableStateOf("")
    private var heard by mutableStateOf("")
    private var listening by mutableStateOf(false)
    private var thinking by mutableStateOf(false)
    private var showUi by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var webReady = false
    private var pendingGreeting = true

    private var askingPermission = false
    private val mic = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> askingPermission = false; if (ok) listen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = BuddyConfig(this)
        applySetupExtras(intent)
        if (floatInstead(intent)) { finish(); overridePendingTransition(0, 0); return }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)

        // 1) grab what's on screen (before our UI appears), 2) then show Buddy
        IslandService.instance?.captureScreen { bmp -> screenshot = bmp?.let { jpeg(it) }; reveal() }
            ?: reveal()
        window.decorView.postDelayed({ if (!showUi) reveal() }, 700)

        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) {
                val loc = Locale("en", "IN")
                if (tts?.isLanguageAvailable(loc) ?: -1 >= TextToSpeech.LANG_AVAILABLE) tts?.language = loc
                tts?.let { BuddyBrain.applyVoice(it, cfg.pitch) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = js("buddy.setTalking(true)")
                    override fun onDone(id: String?) {
                        js("buddy.setTalking(false)")
                        if (cfg.keepListening && cfg.ready && id != "poke") runOnUiThread { if (!isFinishing) listen() }
                    }
                    @Deprecated("") override fun onError(id: String?) = js("buddy.setTalking(false)")
                })
            }
        }

        setContent {
            Screen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applySetupExtras(intent)
        if (floatInstead(intent)) { finish(); overridePendingTransition(0, 0) }
    }

    /** Default: float Buddy over whatever app is open (full-screen chat only on double-tap or if the island isn't running). */
    private fun floatInstead(i: Intent): Boolean {
        if (i.getBooleanExtra("chat", false) || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val isl = IslandService.instance ?: return false
        val q = i.getStringExtra("ask"); val a = i.getStringExtra("play")
        isl.showBuddy(greet = q == null && a == null)
        // demo/automation hooks: --es ask "question"  /  --es play dance
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        if (q != null) h.postDelayed({ isl.buddy?.ask(q) }, 400)
        if (a != null) h.postDelayed({ isl.buddy?.play(a) }, 200)
        return true
    }

    /** adb / skill setup:  am start -n com.glass.launcher/.buddy.BuddyActivity --es setup_provider Gemini --es setup_key KEY ... */
    private fun applySetupExtras(i: Intent?) {
        i ?: return
        i.getStringExtra("setup_provider")?.let { p -> runCatching { cfg.provider = Provider.valueOf(p) } }
        i.getStringExtra("setup_key")?.let { cfg.apiKey = it }
        i.getStringExtra("setup_model")?.let { cfg.model = it }
        i.getStringExtra("setup_base")?.let { cfg.baseUrl = it }
        i.getStringExtra("setup_name")?.let { cfg.name = it }
        // fallback provider (used automatically when the main one fails)
        val fb = i.getStringExtra("setup_fallback_provider")?.let { p -> runCatching { Provider.valueOf(p) }.getOrNull() }
        if (fb != null) { i.getStringExtra("setup_fallback_key")?.let { cfg.setKey(fb, it) }; i.getStringExtra("setup_fallback_model")?.let { cfg.setModel(fb, it) } }
        if (i.hasExtra("demo")) getSharedPreferences("buddy", MODE_PRIVATE).edit().putBoolean("demo", i.getBooleanExtra("demo", false)).apply()
        i.getStringExtra("setup_spec_b64")?.let { runCatching { cfg.spec = String(Base64.decode(it, Base64.DEFAULT)) } }
    }

    private fun reveal() = runOnUiThread {
        if (showUi) return@runOnUiThread
        showUi = true
        if (Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = (40 * resources.displayMetrics.density).toInt() }
            window.setDimAmount(0.25f)
        } else window.setDimAmount(0.55f)
    }

    private fun jpeg(b: Bitmap): ByteArray {
        val w = 720; val h = (b.height * w / b.width.toFloat()).toInt()
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        return ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
    }

    private fun js(code: String) = runOnUiThread { web?.evaluateJavascript(code, null) }

    // ------------------------------------------------------------------ speech
    private fun say(text: String, id: String = "reply") {
        bubble = text
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { askingPermission = true; mic.launch(Manifest.permission.RECORD_AUDIO); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { bubble = "Speech recognition isn't available. Type to me instead!"; return }
        tts?.stop()
        if (stt == null) stt = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { listening = true; heard = ""; js("buddy.setListening(true)") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; js("buddy.setListening(false)") }
                override fun onError(e: Int) { listening = false; js("buddy.setListening(false)") }
                override fun onPartialResults(r: Bundle?) { r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard = it } }
                override fun onEvent(t: Int, b: Bundle?) {}
                override fun onResults(r: Bundle?) {
                    listening = false; js("buddy.setListening(false)")
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) ask(text)
                }
            })
        }
        stt?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
    }

    private fun ask(text: String) {
        heard = text
        if (!cfg.ready) { say("I need an AI key first. Tap the gear and add one, then we can chat!"); js("buddy.play('think')"); showSettings = true; return }
        history.add(Msg(true, text))
        thinking = true; js("buddy.setThinking(true)")
        val wantsScreen = cfg.seeScreen && screenshot != null &&
            (!screenshotUsed || Regex("screen|this|that|here|see|look|what.*(open|showing)|dekh|yeh|ye ", RegexOption.IGNORE_CASE).containsMatchIn(text))
        val img = if (wantsScreen) screenshot else null
        if (img != null) screenshotUsed = true
        lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching { Llm.chat(cfg, history.takeLast(12), img) }.getOrElse { "[sad] Oops, I couldn't reach my brain. ${it.message?.take(90) ?: ""}" }
            }
            thinking = false; js("buddy.setThinking(false)")
            val (tags, clean) = Llm.parse(reply)
            history.add(Msg(false, reply))
            tags.firstOrNull()?.let { js("buddy.play('$it')") }
            if (tags.size > 1) window.decorView.postDelayed({ js("buddy.play('${tags[1]}')") }, 2600)
            say(clean.ifBlank { "Hmm?" })
        }
    }

    private fun greet() {
        if (!pendingGreeting) return
        pendingGreeting = false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hey night owl" }
        val name = cfg.name.ifBlank { "" }
        val line = listOf(
            "$part${if (name.isNotBlank()) ", $name" else ""}! What can I do for you?",
            "Hiii${if (name.isNotBlank()) " $name" else ""}! I'm here. What's up?",
            "$part! Your buddy reporting for duty. Ask me anything!",
        ).random()
        window.decorView.postDelayed({ say(line, "greet") }, 1900)
    }

    // ------------------------------------------------------------------ JS bridge
    inner class Bridge {
        @JavascriptInterface fun onReady() = runOnUiThread {
            webReady = true
            cfg.spec.takeIf { it.isNotBlank() }?.let { s -> web?.evaluateJavascript("buddy.setSpec(${org.json.JSONObject.quote(s)})", null) }
            web?.evaluateJavascript("buddy.enter()", null)
            greet()
        }
        @JavascriptInterface fun onPoke(where: String) = runOnUiThread {
            val lines = if (where == "head") listOf("Hehe, that tickles!", "Aww, head pats!", "Stop it, I'm blushing!") else listOf("Hey! That's me!", "Hehe!", "Boop!", "Whoa, careful!")
            if (tts?.isSpeaking != true) say(lines.random(), "poke")
        }
        @JavascriptInterface fun onAction(name: String) {}
        @JavascriptInterface fun onTapEmpty() {}
    }

    // ------------------------------------------------------------------ UI
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun Screen() {
        val p by animateFloatAsState(if (showUi) 1f else 0f, Ios.bouncy(), label = "in")
        BackHandler { if (showSettings) showSettings = false else close() }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }) {
            // tap the empty top area to dismiss (like Siri)
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } })
            // the 3D character
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    WebView.setWebContentsDebuggingEnabled(true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.i("BuddyJS", "${m.messageLevel()} ${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()} ${m.message()}"); return true
                        }
                    }
                    addJavascriptInterface(Bridge(), "Android")
                    loadUrl("file:///android_asset/buddy/index.html")
                    web = this
                }
            }, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f).align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 74.dp))

            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Buddy", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    RoundIcon(if (cfg.seeScreen) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, cfg.seeScreen) { cfg.seeScreen = !cfg.seeScreen; bubble = if (cfg.seeScreen) "Okay, I can peek at your screen when you ask." else "Got it, I won't look at your screen." }
                    Spacer(Modifier.width(10.dp))
                    RoundIcon(Icons.Rounded.Settings, false) { showSettings = true }
                    Spacer(Modifier.width(10.dp))
                    RoundIcon(Icons.Rounded.Close, false) { close() }
                }
                Spacer(Modifier.height(14.dp))
                AnimatedVisibility(bubble.isNotBlank() || thinking, enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.fillMaxWidth().frosted(Ios.rr(22f), Color.White.copy(alpha = 0.18f)).padding(16.dp)) {
                        if (heard.isNotBlank()) Text("“$heard”", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                        Text(if (thinking) "Thinking…" else bubble, color = Color.White, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            InputBar(Modifier.align(Alignment.BottomCenter))

            AnimatedVisibility(showSettings, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
                SettingsSheet { showSettings = false }
            }
        }
    }

    @Composable
    private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
        Box(Modifier.size(40.dp).frosted(CircleShape, if (on) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)).pressable(onClick, haptic = true),
            contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
    }

    @Composable
    private fun InputBar(modifier: Modifier) {
        var text by remember { mutableStateOf("") }
        val pulse by rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.18f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p")
        Row(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(48.dp).frosted(Ios.capsule, Color.White.copy(alpha = 0.16f)).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("Ask Buddy anything…", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                BasicTextField(text, { text = it }, singleLine = true, textStyle = Ios.body.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { ask(text.trim()); text = "" } }), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(52.dp).graphicsLayer { val s = if (listening) pulse else 1f; scaleX = s; scaleY = s }
                .clip(CircleShape).background(if (listening) Color(0xFFFF4F8B) else Color(0xFFE0558F))
                .pressable({ if (text.isNotBlank()) { ask(text.trim()); text = "" } else if (listening) stt?.stopListening() else listen() }, haptic = true),
                contentAlignment = Alignment.Center) {
                Icon(if (text.isNotBlank()) Icons.Rounded.ArrowUpward else Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }

    @Composable
    private fun SettingsSheet(onClose: () -> Unit) {
        var provider by remember { mutableStateOf(cfg.provider) }
        var key by remember(provider) { mutableStateOf(cfg.apiKey) }
        var model by remember(provider) { mutableStateOf(cfg.model) }
        var base by remember(provider) { mutableStateOf(cfg.baseUrl) }
        var name by remember { mutableStateOf(cfg.name) }
        Column(Modifier.fillMaxWidth().clip(Ios.rr(30f)).background(Color(0xF21C1C1E)).navigationBarsPadding().imePadding().padding(20.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Buddy settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Done", color = Ios.Blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable({
                    cfg.provider = provider; cfg.apiKey = key; cfg.model = model; if (provider == Provider.Custom || base != provider.baseUrl) cfg.baseUrl = base; cfg.name = name
                    onClose(); say("All set! Let's talk.")
                }))
            }
            Label("Your name"); Field(name, "What should Buddy call you?") { name = it }
            Label("AI provider (free or paid)")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Provider.entries.take(3).forEach { Chip(it, provider == it) { provider = it } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Provider.entries.drop(3).forEach { Chip(it, provider == it) { provider = it } }
            }
            Label("API key"); Field(key, "Paste your ${provider.title} key", secret = true) { key = it }
            Label("Model"); Field(model, provider.defaultModel.ifBlank { "model name" }) { model = it }
            if (provider == Provider.Custom || provider == Provider.OpenRouter) { Label("Base URL"); Field(base, "https://…/v1") { base = it } }
            Text(when (provider) {
                Provider.Gemini -> "Free key: aistudio.google.com → Get API key."
                Provider.Groq -> "Free key: console.groq.com → API Keys."
                Provider.OpenRouter -> "openrouter.ai/keys — many free models (\":free\")."
                Provider.OpenAI -> "platform.openai.com/api-keys (paid)."
                Provider.Anthropic -> "console.anthropic.com (paid)."
                Provider.Custom -> "Any OpenAI-compatible server, e.g. Ollama/LM Studio on your Wi-Fi."
            }, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }

    @Composable private fun Label(t: String) = Text(t.uppercase(), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

    @Composable
    private fun Field(v: String, hint: String, secret: Boolean = false, on: (String) -> Unit) {
        Box(Modifier.fillMaxWidth().clip(Ios.rr(12f)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (v.isEmpty()) Text(hint, color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp)
            BasicTextField(v, on, singleLine = true, textStyle = Ios.body.copy(color = Color.White), cursorBrush = SolidColor(Ios.Blue),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.Chip(p: Provider, on: Boolean, onClick: () -> Unit) {
        Box(Modifier.weight(1f).clip(Ios.capsule).background(if (on) Color(0xFFE0558F) else Color.White.copy(alpha = 0.1f))
            .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.12f), Ios.capsule).pressable(onClick).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(p.title.substringBefore(" ("), color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
    }

    private fun close() {
        tts?.stop(); stt?.cancel()
        finish(); overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations && !askingPermission) { tts?.stop(); stt?.cancel(); if (!isFinishing) finish() }
    }

    override fun onDestroy() {
        tts?.shutdown(); stt?.destroy(); web?.destroy()
        super.onDestroy()
    }
}
