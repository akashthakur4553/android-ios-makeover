package com.glass.launcher.buddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale

/**
 * Buddy's brain: voice in (speech recognition), voice out (deep male TTS), the LLM call (with provider
 * fallback), screen peeking and emotion tags. UI-agnostic: the floating overlay and the full chat screen both use it.
 */
class BuddyBrain(
    private val ctx: Context,
    private val js: (String) -> Unit,
    private val onBubble: (String) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onState: (listening: Boolean, thinking: Boolean) -> Unit,
    /** Returns a fresh screenshot (JPEG) of what the user is looking at, or null. */
    private val screenshot: (cb: (ByteArray?) -> Unit) -> Unit,
) {
    val cfg = BuddyConfig(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stt: SpeechRecognizer? = null
    private val history = ArrayList<Msg>()
    var listening = false; private set
    var thinking = false; private set
    var continuous = true
    private var pendingSay: String? = null
    /** Demo mode: also save every spoken line (Buddy + the typed/asked question) as WAV files with timestamps, to build videos with sound. */
    private val demoDir: java.io.File? get() = if (ctx.getSharedPreferences("buddy", Context.MODE_PRIVATE).getBoolean("demo", false))
        java.io.File(ctx.getExternalFilesDir(null), "demo").apply { mkdirs() } else null
    private var userTts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (!ttsReady) return@TextToSpeech
            pickVoice()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = js("buddy.setTalking(true)")
                override fun onDone(id: String?) {
                    js("buddy.setTalking(false)")
                    if (continuous && cfg.keepListening && cfg.ready && id == "reply") main.post { listen() }
                }
                @Deprecated("") override fun onError(id: String?) = js("buddy.setTalking(false)")
            })
            pendingSay?.let { say(it, "greet"); pendingSay = null }
        }
    }

    private fun pickVoice() { tts?.let { applyVoice(it, cfg.pitch) } }

    companion object Voices {
    /** Deep male voice: prefer known male Google voices, then lower the pitch. */
    fun applyVoice(t: TextToSpeech, pitch: Float) {
        val voices: Set<Voice> = runCatching { t.voices }.getOrNull() ?: emptySet()
        Log.i("BuddyVoice", "voices: " + voices.filter { it.locale.language == "en" }.joinToString { it.name })
        val preferred = listOf(
            "en-in-x-end-network", "en-in-x-end-local", "en-us-x-iom-network", "en-us-x-iom-local",
            "en-us-x-iol-network", "en-us-x-iol-local", "en-us-x-tpd-network", "en-us-x-tpd-local",
            "en-gb-x-rjs-network", "en-gb-x-rjs-local", "en-gb-x-gbd-network", "en-gb-x-gbd-local",
        )
        val v = preferred.firstNotNullOfOrNull { n -> voices.firstOrNull { it.name == n && !it.isNetworkConnectionRequired } }
            ?: preferred.firstNotNullOfOrNull { n -> voices.firstOrNull { it.name == n } }
            ?: voices.firstOrNull { it.locale.language == "en" && it.name.contains("male", true) && !it.name.contains("female", true) }
        if (v != null) t.voice = v else t.language = Locale.US
        Log.i("BuddyVoice", "using ${t.voice?.name}")
        t.setPitch(pitch)
        t.setSpeechRate(0.98f)
    }

        fun jpeg(b: Bitmap): ByteArray {
            val w = 720; val h = (b.height * w / b.width.toFloat()).toInt()
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            return ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
        }
    }

    fun say(text: String, id: String = "reply") {
        Log.i("BuddySay", text)
        onBubble(text)
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            demoDir?.let { d -> tts?.synthesizeToFile(text, null, java.io.File(d, "${System.currentTimeMillis()}_buddy.wav"), "file$id") }
        } else pendingSay = text
    }

    fun greet() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hey night owl" }
        val n = cfg.name
        val line = listOf(
            "$part${if (n.isNotBlank()) ", $n" else ""}! Tap me when you want to talk.",
            "Hey${if (n.isNotBlank()) " $n" else ""}! I'm right here if you need me.",
            "$part! Your buddy is on duty. Tap me anytime.",
        ).random()
        main.postDelayed({ say(line, "greet") }, 1600)
    }

    fun poke(where: String) {
        if (tts?.isSpeaking == true) return
        val lines = if (where == "head") listOf("Hehe, that tickles!", "Aww, head pats!", "Stop it, I'm blushing!") else listOf("Hey! That's me!", "Hehe!", "Boop!", "Whoa, careful!")
        say(lines.random(), "poke")
    }

    fun toggleListen() { if (listening) stopListening() else listen() }

    fun stopListening() { stt?.stopListening(); listening = false; onState(false, thinking); js("buddy.setListening(false)") }

    fun listen() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("I need microphone permission to hear you. Open Buddy and allow the mic, please!", "info"); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { say("Speech recognition isn't available on this phone. Double-tap me to type instead.", "info"); return }
        tts?.stop()
        if (stt == null) stt = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { listening = true; onHeard(""); onState(true, thinking); js("buddy.setListening(true)") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; onState(false, thinking); js("buddy.setListening(false)") }
                override fun onError(e: Int) {
                    listening = false; onState(false, thinking); js("buddy.setListening(false)")
                    Log.i("BuddyVoice", "stt error $e")
                }
                override fun onPartialResults(r: Bundle?) { r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onHeard(it) } }
                override fun onEvent(t: Int, b: Bundle?) {}
                override fun onResults(r: Bundle?) {
                    listening = false; onState(false, thinking); js("buddy.setListening(false)")
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

    private val screenWords = Regex("screen|this|that|here|see|look|reading|showing|what.*(open|on)|dekh|yeh|ye |kya hai", RegexOption.IGNORE_CASE)

    fun ask(text: String) {
        onHeard(text)
        demoDir?.let { d -> speakAsUser(text, java.io.File(d, "${System.currentTimeMillis()}_user.wav")) }
        if (!cfg.ready) { say("I need an AI key first. Double-tap me and open settings!", "info"); js("buddy.play('think')"); return }
        history.add(Msg(true, text))
        thinking = true; onState(listening, true); js("buddy.setThinking(true)")
        val wantScreen = cfg.seeScreen && screenWords.containsMatchIn(text)
        val go: (ByteArray?) -> Unit = { img ->
            scope.launch {
                val reply = withContext(Dispatchers.IO) {
                    runCatching { Llm.chat(cfg, history.takeLast(12), img) }
                        .getOrElse { "[sad] Oops, I couldn't reach my brain right now. ${it.message?.take(80) ?: ""}" }
                }
                thinking = false; onState(listening, false); js("buddy.setThinking(false)")
                val (tags, clean) = Llm.parse(reply)
                history.add(Msg(false, reply))
                tags.firstOrNull()?.let { js("buddy.play('$it')") }
                if (tags.size > 1) main.postDelayed({ js("buddy.play('${tags[1]}')") }, 2600)
                say(clean.ifBlank { "Hmm?" })
            }
        }
        if (wantScreen) screenshot(go) else go(null)
    }

    /** A second, different male voice that reads the user's question (demo videos only). */
    private fun speakAsUser(text: String, out: java.io.File) {
        val go = { userTts?.synthesizeToFile(text, null, out, "u${out.name}") }
        if (userTts == null) userTts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val v = runCatching { userTts!!.voices }.getOrNull()?.firstOrNull { it.name == "en-us-x-iol-local" || it.name == "en-us-x-tpd-local" }
                if (v != null) userTts!!.voice = v
                userTts!!.setPitch(1.0f); userTts!!.setSpeechRate(1.05f); go()
            }
        } else go()
    }

    fun release() {
        runCatching { userTts?.shutdown() }
        runCatching { tts?.stop(); tts?.shutdown() }; runCatching { stt?.destroy() }
        scope.cancel()
    }

}
