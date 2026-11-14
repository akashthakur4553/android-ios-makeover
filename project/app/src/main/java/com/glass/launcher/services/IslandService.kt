package com.glass.launcher.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.glass.launcher.GlassApp
import com.glass.launcher.OverlayActivity
import com.glass.launcher.ui.Ios
import com.glass.launcher.ui.timeAgo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Dynamic Island + iOS-style banners + top-corner gestures, drawn as accessibility overlays
 * (the only overlay type that can sit above the status bar). It never reads screen content.
 */
class IslandService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val ssrc = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var islandView: ComposeView? = null
    private var islandLp: WindowManager.LayoutParams? = null
    private val corners = ArrayList<View>()

    // ----- island state
    private enum class Mode { Idle, Compact, Expanded, Banner, Hidden }
    private var islandMode by mutableStateOf(Mode.Idle)
    private var charging by mutableStateOf<Int?>(null) // battery % while showing the charging activity
    private var banner by mutableStateOf<NotifItem?>(null)
    private var statusBarVisible by mutableStateOf(true)
    private var bannerJob: Job? = null
    private var chargeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ssrc.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        registry.currentState = Lifecycle.State.RESUMED
        instance = this
        val prefs = GlassApp.instance.prefs
        RecentApps.seedFromUsage(this)
        addCornerZones()
        if (prefs.islandEnabled) addIsland()
        // Our banners replace the system pop-ups (restored automatically if this service stops).
        setSystemHeadsUp(!prefs.islandBanners)
        registerReceiver(power, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) })
        scope.launch {
            NotificationService.posted.collect { n ->
                if (!prefs.islandBanners || !statusBarVisible) return@collect
                banner = n
                setMode(Mode.Banner)
                bannerJob?.cancel()
                bannerJob = scope.launch { delay(4200); if (islandMode == Mode.Banner) setMode(baseMode()) }
            }
        }
        // keep compact media state in sync
        scope.launch {
            var last: Boolean? = null
            while (true) {
                val playing = GlassApp.instance.media.info?.playing == true
                if (playing != last && (islandMode == Mode.Idle || islandMode == Mode.Compact)) setMode(baseMode())
                last = playing
                delay(700)
            }
        }
    }

    private fun baseMode(): Mode = when {
        !statusBarVisible -> Mode.Hidden
        charging != null -> Mode.Compact
        GlassApp.instance.media.info?.playing == true -> Mode.Compact
        else -> Mode.Idle
    }

    /** Screen off -> get the Glass lock screen ready so it's there the moment the screen wakes. */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { com.glass.launcher.LockActivity.prepare(this@IslandService) }
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Most phones already show their own charging animation; only show ours if the user enables it.
            if (i.action == Intent.ACTION_POWER_CONNECTED && GlassApp.instance.prefs.islandCharging) {
                val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val l = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val s = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                charging = if (l >= 0) l * 100 / s else 0
                setMode(Mode.Compact)
                chargeJob?.cancel()
                chargeJob = scope.launch { delay(3200); charging = null; setMode(baseMode()) }
            }
        }
    }

    // ------------------------------------------------------------------ windows

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun islandSize(m: Mode): Pair<Int, Int> {
        val w = resources.displayMetrics.widthPixels
        return when (m) {
            Mode.Idle, Mode.Hidden -> dp(128f) to dp(38f)
            Mode.Compact -> dp(250f) to dp(38f)
            Mode.Expanded -> (w - dp(20f)) to dp(196f)
            Mode.Banner -> (w - dp(16f)) to dp(104f)
        }
    }

    private fun setMode(m: Mode) {
        val grow = islandSize(m).let { (w, h) -> w * h } > islandSize(islandMode).let { (w, h) -> w * h }
        islandMode = m
        val lp = islandLp ?: return
        val (w, h) = islandSize(m)
        // Grow the window first; shrink it only after the spring has settled (see onSettled).
        if (grow) applySize(lp, w, h)
    }

    /** Called when the island shape animation finishes — now it's safe to shrink the window. */
    private fun onSettled() {
        val lp = islandLp ?: return
        val (w, h) = islandSize(islandMode)
        applySize(lp, w, h)
    }

    private fun applySize(lp: WindowManager.LayoutParams, w: Int, h: Int) {
        if (lp.width == w && lp.height == h) return
        lp.width = w; lp.height = h
        islandView?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun overlayParams(w: Int, h: Int, gravity: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** Horizontal offset from screen centre to the camera cutout, so the island sits on the camera. */
    private fun cutoutOffsetX(): Int = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 30) return 0
        val m = wm.currentWindowMetrics
        val cut = m.windowInsets.displayCutout ?: return 0
        val r = cut.boundingRectTop.takeIf { !it.isEmpty } ?: return 0
        r.centerX() - m.bounds.width() / 2
    }.getOrDefault(0)

    private fun addIsland() {
        val (w, h) = islandSize(Mode.Idle)
        val lp = overlayParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { y = 0; x = cutoutOffsetX() }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandService)
            setViewTreeSavedStateRegistryOwner(this@IslandService)
            setContent { Island() }
            setOnApplyWindowInsetsListener { _, insets ->
                val vis = insets.isVisible(WindowInsets.Type.statusBars())
                if (vis != statusBarVisible) { statusBarVisible = vis; setMode(if (vis) baseMode() else Mode.Hidden) }
                insets
            }
        }
        runCatching { wm.addView(v, lp) }.onSuccess { islandView = v; islandLp = lp }
    }

    /** Invisible strips over the status bar: top-right = Control Centre, top-left = Notification Centre. */
    @SuppressLint("ClickableViewAccessibility")
    private fun addCornerZones() {
        val prefs = GlassApp.instance.prefs
        val sbH = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else dp(36f) }
        val w = resources.displayMetrics.widthPixels
        fun zone(gravity: Int, width: Int, mode: String) {
            val v = View(this)
            var startY = 0f
            var fired = false
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = e.rawY; fired = false }
                    MotionEvent.ACTION_MOVE -> if (!fired && e.rawY - startY > dp(14f)) {
                        fired = true
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        OverlayActivity.show(this, mode)
                    }
                }
                true
            }
            val lp = overlayParams(width, sbH, Gravity.TOP or gravity)
            runCatching { wm.addView(v, lp); corners += v }
        }
        if (prefs.cornerControlCentre) {
            // Whole status bar, like iPhone: left half = Notification Centre, right half = Control Centre.
            // The middle stays free for the Dynamic Island (which itself opens Notification Centre on swipe).
            val side = (w - dp(136f)) / 2
            zone(Gravity.START, side, "nc")
            zone(Gravity.END, side, "cc")
        }
    }

    private var lastSwitcher = 0L
    /** The phone's own launcher (it hosts the system Recents screen on most Android phones). */
    private val stockLauncherPkg: String? by lazy { com.glass.launcher.StockLauncher.intent(this)?.component?.packageName }
    @Volatile private var windowChanges = 0
    private var snapJob: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        windowChanges++
        // Stock Recents (hosted by the stock launcher on most phones) -> cover it with the Glass app switcher.
        if (pkg == stockLauncherPkg && (cls.contains("Recents", true) || cls.contains("recents", true))) {
            RecentApps.foreground = null; snapJob?.cancel()
            val now = System.currentTimeMillis()
            if (now - lastSwitcher > 700) {
                lastSwitcher = now
                showCover()
                runCatching {
                    startActivity(Intent(this, com.glass.launcher.SwitcherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
            }
            return
        }
        if (RecentApps.isTrackable(this, pkg)) {
            if (RecentApps.foreground != pkg) {
                RecentApps.onForeground(pkg)
                snapJob?.cancel()
                snapJob = scope.launch {
                    delay(900)
                    while (RecentApps.foreground == pkg) { snapshot(pkg); delay(3000) }
                }
            }
        } else if (pkg == packageName || pkg == stockLauncherPkg || pkg == "com.android.systemui") {
            // leaving the app: stop snapshotting (keep the last one)
            if (pkg != "com.android.systemui") { RecentApps.foreground = null; snapJob?.cancel() }
        }
    }

    // Instant dark cover so the stock Recents screen is hidden while the Glass switcher starts.
    private var cover: View? = null
    private fun showCover() {
        if (cover != null) return
        val v = View(this).apply { setBackgroundColor(0xF0101012.toInt()) }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.addView(v, lp); cover = v }
        scope.launch { delay(900); hideCover() } // safety
    }

    fun hideCover() {
        cover?.let { runCatching { wm.removeViewImmediate(it) } }; cover = null
    }

    /** Floating Buddy companion (lives above every app). */
    var buddy: com.glass.launcher.buddy.BuddyOverlay? = null
    fun showBuddy(greet: Boolean = true) {
        if (buddy == null) buddy = com.glass.launcher.buddy.BuddyOverlay(this, wm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) { cb -> captureScreen(cb) }
        buddy?.show(greet)
    }

    /** One-off full-screen capture for Buddy ("see my screen"). Returns a software bitmap (or null). */
    fun captureScreen(cb: (android.graphics.Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 30) { cb(null); return }
        runCatching {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val bmp = runCatching {
                        android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    r.hardwareBuffer.close(); cb(bmp)
                }
                override fun onFailure(errorCode: Int) = cb(null)
            })
        }.onFailure { cb(null) }
    }

    /** Small, in-memory snapshot of the app in front, for the switcher card. */
    private fun snapshot(pkg: String) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isInteractive) return
        val stamp = windowChanges
        runCatching {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    runCatching {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        if (hw != null && RecentApps.foreground == pkg && windowChanges == stamp) {
                            val w = hw.width / 3; val h = hw.height / 3
                            val sw = android.graphics.Bitmap.createScaledBitmap(hw, w, h, true) // stays on the GPU
                            RecentApps.putThumb(pkg, sw)
                        }
                        r.hardwareBuffer.close()
                    }
                }
                override fun onFailure(errorCode: Int) {}
            })
        }
    }
    override fun onInterrupt() {}

    /** Catch the Recents button before the system reacts (works where the nav bar sends KEYCODE_APP_SWITCH). */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode != android.view.KeyEvent.KEYCODE_APP_SWITCH) return false
        android.util.Log.i("GlassIsland", "APP_SWITCH key action=${event.action}")
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            lastSwitcher = System.currentTimeMillis()
            RecentApps.foreground = null; snapJob?.cancel()
            runCatching {
                startActivity(Intent(this, com.glass.launcher.SwitcherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
        return true // swallow: the stock Recents never opens
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown(); return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        registry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        super.onDestroy()
    }

    private fun setSystemHeadsUp(on: Boolean) {
        runCatching { android.provider.Settings.Global.putInt(contentResolver, "heads_up_notifications_enabled", if (on) 1 else 0) }
    }

    private fun teardown() {
        runCatching { buddy?.hide() }; buddy = null
        instance = null
        hideCover()
        setSystemHeadsUp(true)
        runCatching { unregisterReceiver(power) }
        runCatching { unregisterReceiver(screen) }
        islandView?.let { runCatching { wm.removeViewImmediate(it) } }; islandView = null
        corners.forEach { runCatching { wm.removeViewImmediate(it) } }; corners.clear()
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun Island() {
        val media = GlassApp.instance.media.info
        val m = islandMode
        val (tw, th) = when (m) {
            Mode.Hidden -> 0.dp to 0.dp
            Mode.Idle -> 96.dp to 30.dp
            Mode.Compact -> 236.dp to 32.dp
            Mode.Expanded -> 372.dp to 186.dp
            Mode.Banner -> 376.dp to 92.dp
        }
        val springSpec = spring<Dp>(dampingRatio = 0.68f, stiffness = 260f)
        val w by animateDpAsState(tw, springSpec, label = "w", finishedListener = { onSettled() })
        val h by animateDpAsState(th, springSpec, label = "h", finishedListener = { onSettled() })
        val radius = if (m == Mode.Expanded || m == Mode.Banner) 40f else h.value / 2f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (m == Mode.Hidden && w < 2.dp) return@Box
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .width(w).height(h)
                    .clip(Ios.rr(radius))
                    .background(Color.Black)
                    .pointerInput(m) {
                        detectTapGestures(
                            onTap = {
                                when (islandMode) {
                                    Mode.Compact -> if (media != null) setMode(Mode.Expanded)
                                    Mode.Expanded -> setMode(baseMode())
                                    Mode.Banner -> { banner?.let { NotificationService.open(this@IslandService, it) }; setMode(baseMode()) }
                                    else -> {}
                                }
                            },
                            onLongPress = { OverlayActivity.show(this@IslandService, "cc") },
                        )
                    }
                    .pointerInput(m) {
                        var acc = 0f
                        detectVerticalDragGestures(onDragStart = { acc = 0f }, onVerticalDrag = { _, dy -> acc += dy }, onDragEnd = {
                            if (acc > 40) OverlayActivity.show(this@IslandService, "nc")
                            else if (acc < -30) setMode(baseMode())
                        })
                    },
            ) {
                AnimatedContent(m, transitionSpec = { fadeIn(androidx.compose.animation.core.tween(180, 60)) togetherWith fadeOut(androidx.compose.animation.core.tween(120)) }, label = "island") { mm ->
                    when (mm) {
                        Mode.Compact -> CompactContent(media)
                        Mode.Expanded -> ExpandedMedia()
                        Mode.Banner -> BannerContent(banner)
                        else -> Spacer(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    @Composable
    private fun CompactContent(media: MediaInfo?) {
        val c = charging
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c != null) {
                Text("Charging", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("$c%", color = Ios.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.Bolt, null, tint = Ios.Green, modifier = Modifier.size(16.dp))
            } else if (media != null) {
                Box(Modifier.size(22.dp).clip(Ios.rr(6f)).background(Color.DarkGray)) {
                    if (media.art != null) Image(media.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.fillMaxSize().padding(3.dp))
                }
                Spacer(Modifier.weight(1f))
                Equalizer(media.playing)
            }
        }
    }

    @Composable
    private fun Equalizer(playing: Boolean) {
        var t by remember { mutableLongStateOf(0L) }
        LaunchedEffect(playing) { while (playing) { withFrameMillis { t = it } } }
        Canvas(Modifier.size(22.dp, 16.dp)) {
            val bars = 4
            val bw = size.width / (bars * 2f)
            for (i in 0 until bars) {
                val ph = sin(t / 140.0 + i * 1.3).toFloat()
                val hh = if (playing) size.height * (0.35f + 0.65f * (0.5f + 0.5f * ph)) else size.height * 0.25f
                drawRoundRect(Ios.Orange, topLeft = androidx.compose.ui.geometry.Offset(i * bw * 2 + bw / 2, (size.height - hh) / 2),
                    size = androidx.compose.ui.geometry.Size(bw, hh), cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2))
            }
        }
    }

    @Composable
    private fun ExpandedMedia() {
        val hub = GlassApp.instance.media
        val m = hub.info
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(Ios.rr(12f)).background(Color.DarkGray).pointerInput(Unit) { detectTapGestures { hub.openApp() } }) {
                    if (m?.art != null) Image(m.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(m?.title ?: "Not Playing", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m?.artist ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1)
                }
                Equalizer(m?.playing == true)
            }
            Spacer(Modifier.weight(1f))
            var now by remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
            val pos = (m?.currentPosition() ?: 0L).also { now }
            val dur = (m?.duration ?: 0L).coerceAtLeast(1L)
            Box(Modifier.fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.2f), CircleShape)) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).height(5.dp).background(Color.White, CircleShape))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(32.dp).pointerInput(Unit) { detectTapGestures { hub.previous() } })
                Icon(if (m?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White,
                    modifier = Modifier.size(40.dp).pointerInput(Unit) { detectTapGestures { hub.playPause() } })
                Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(32.dp).pointerInput(Unit) { detectTapGestures { hub.next() } })
            }
        }
    }

    @Composable
    private fun BannerContent(n: NotifItem?) {
        if (n == null) return
        val icon = GlassApp.instance.repo.find(n.pkg)?.icon
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val big = n.largeIcon
            if (big != null && n.isMessage) Image(big, null, contentScale = ContentScale.Crop, modifier = Modifier.size(46.dp).clip(CircleShape))
            else if (icon != null) Image(icon, null, modifier = Modifier.size(44.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.title.ifBlank { n.app }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(timeAgo(n.time), color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
                }
                Text(n.text, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    companion object {
        @Volatile var instance: IslandService? = null
        fun isEnabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return flat.split(':').any { it.equals("${ctx.packageName}/${IslandService::class.java.name}", true) || it.equals("${ctx.packageName}/.services.IslandService", true) }
        }
    }
}
