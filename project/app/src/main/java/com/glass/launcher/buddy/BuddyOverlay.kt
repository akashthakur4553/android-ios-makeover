package com.glass.launcher.buddy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/**
 * Buddy as a small floating companion that lives on top of ANY app (like a chat head) — the app underneath
 * stays fully usable. Tap = talk · drag = move · double-tap = full chat & settings · drag onto the ✕ = hide.
 * The speech bubble is a separate, non-touchable window so it never blocks the app.
 */
class BuddyOverlay(private val ctx: Context, private val wm: WindowManager, private val overlayType: Int, private val capture: ((android.graphics.Bitmap?) -> Unit) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private var charView: FrameLayout? = null
    private var web: WebView? = null
    private var bubbleView: TextView? = null
    private var trashView: TextView? = null
    private lateinit var charLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var brain: BuddyBrain? = null
    val showing get() = charView != null
    private var hideBubble: Runnable? = null

    private fun params(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun show(greet: Boolean = true) {
        if (showing) { if (greet) brain?.greet(); return }
        val sw = ctx.resources.displayMetrics.widthPixels
        val sh = ctx.resources.displayMetrics.heightPixels
        val w = dp(132f); val h = dp(210f)
        val sp = ctx.getSharedPreferences("buddy", Context.MODE_PRIVATE)
        charLp = params(w, h, true).apply { x = sp.getInt("ox", sw - w - dp(8f)); y = sp.getInt("oy", sh - h - dp(110f)) }

        // speech bubble (separate window, ignores touches)
        bubbleView = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(14f), dp(10f), dp(14f), dp(10f)); maxLines = 5
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(0xE62A1F2E.toInt()); setStroke(dp(1f), 0x55FFFFFF) }
            visibility = View.GONE
        }
        bubbleLp = params(dp(240f), WindowManager.LayoutParams.WRAP_CONTENT, false)
        runCatching { wm.addView(bubbleView, bubbleLp) }

        val wv = WebView(ctx).apply {
            setBackgroundColor(0)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean { android.util.Log.i("BuddyJS", m.message()); return true }
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/buddy/index.html")
        }
        web = wv
        val frame = object : FrameLayout(ctx) {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false; var lastTap = 0L
            val slop = ViewConfiguration.get(ctx).scaledTouchSlop
            override fun onInterceptTouchEvent(e: MotionEvent) = true
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = charLp.x; startY = charLp.y; dragging = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) { dragging = true; showTrash(true) }
                        if (dragging) {
                            charLp.x = (startX + (e.rawX - downX)).toInt(); charLp.y = (startY + (e.rawY - downY)).toInt()
                            runCatching { wm.updateViewLayout(this, charLp) }; placeBubble()
                            trashView?.alpha = if (overTrash(e.rawX, e.rawY)) 1f else 0.6f
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        showTrash(false)
                        if (dragging) {
                            if (overTrash(e.rawX, e.rawY)) { hide(); return true }
                            sp.edit().putInt("ox", charLp.x).putInt("oy", charLp.y).apply()
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - lastTap < 320) { // double tap -> full chat & settings
                                main.removeCallbacksAndMessages(TAP)
                                ctx.startActivity(Intent(ctx, BuddyActivity::class.java).putExtra("chat", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } else main.postAtTime({ brain?.toggleListen(); js("buddy.play('yes')") }, TAP, android.os.SystemClock.uptimeMillis() + 330)
                            lastTap = now
                        }
                    }
                }
                return true
            }
        }
        frame.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        charView = frame
        runCatching { wm.addView(frame, charLp) }

        brain = BuddyBrain(ctx, js = ::js, onBubble = ::bubble, onHeard = { if (it.isNotBlank()) bubble("“$it”", keep = true) },
            onState = { l, t -> if (t) bubble("Thinking…", keep = true) else if (l) bubble("Listening…", keep = true) },
            screenshot = { cb -> peek(cb) }).also { it.continuous = false }
    }

    private val TAP = Any()

    /** Hide Buddy for a moment so he isn't in his own screenshot, then capture. */
    private fun peek(cb: (ByteArray?) -> Unit) {
        charView?.alpha = 0f; bubbleView?.alpha = 0f
        main.postDelayed({
            capture { bmp ->
                android.util.Log.i("BuddyLLM", "screenshot ${bmp?.width}x${bmp?.height}")
                main.post { charView?.alpha = 1f; bubbleView?.alpha = 1f }
                cb(bmp?.let { BuddyBrain.jpeg(it) })
            }
        }, 120)
    }

    private fun bubble(text: String, keep: Boolean = false) = main.post {
        val b = bubbleView ?: return@post
        b.text = text; b.visibility = View.VISIBLE; placeBubble()
        hideBubble?.let { main.removeCallbacks(it) }
        if (!keep) { hideBubble = Runnable { b.visibility = View.GONE }; main.postDelayed(hideBubble!!, 4000L + text.length * 60L) }
    }

    private fun placeBubble() {
        val b = bubbleView ?: return
        val sw = ctx.resources.displayMetrics.widthPixels
        bubbleLp.x = (charLp.x + charLp.width / 2 - bubbleLp.width / 2).coerceIn(dp(8f), sw - bubbleLp.width - dp(8f))
        b.measure(View.MeasureSpec.makeMeasureSpec(bubbleLp.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        bubbleLp.y = (charLp.y - b.measuredHeight + dp(10f)).coerceAtLeast(dp(40f))
        runCatching { wm.updateViewLayout(b, bubbleLp) }
    }

    private fun showTrash(on: Boolean) {
        if (on && trashView == null) {
            trashView = TextView(ctx).apply {
                text = "✕"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; alpha = 0.6f
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1C1C1E.toInt()); setStroke(dp(2f), Color.WHITE) }
            }
            val lp = params(dp(64f), dp(64f), false).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(90f)
            }
            runCatching { wm.addView(trashView, lp) }
        } else if (!on) { trashView?.let { runCatching { wm.removeViewImmediate(it) } }; trashView = null }
    }

    private fun overTrash(x: Float, y: Float): Boolean {
        val sw = ctx.resources.displayMetrics.widthPixels; val sh = ctx.resources.displayMetrics.heightPixels
        return abs(x - sw / 2f) < dp(70f) && y > sh - dp(200f)
    }

    private fun js(code: String) = main.post { web?.evaluateJavascript(code, null) }

    inner class Bridge {
        @JavascriptInterface fun onReady() = main.post {
            val spec = BuddyConfig(ctx).spec
            if (spec.isNotBlank()) web?.evaluateJavascript("buddy.setSpec(${JSONObject.quote(spec)})", null)
            web?.evaluateJavascript("buddy.enter()", null)
            brain?.greet()
        }
        @JavascriptInterface fun onPoke(where: String) {}
        @JavascriptInterface fun onAction(name: String) {}
        @JavascriptInterface fun onTapEmpty() {}
    }

    fun hide() {
        brain?.release(); brain = null
        listOf(charView, bubbleView, trashView).forEach { v -> v?.let { runCatching { wm.removeViewImmediate(it) } } }
        web?.destroy(); web = null; charView = null; bubbleView = null; trashView = null
    }

    fun say(text: String) = brain?.say(text)
    fun ask(text: String) = brain?.ask(text)
    fun play(action: String) = js("buddy.play('$action')")
}
