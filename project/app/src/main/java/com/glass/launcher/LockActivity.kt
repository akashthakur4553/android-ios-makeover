package com.glass.launcher

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glass.launcher.services.NotifItem
import com.glass.launcher.services.NotificationService
import com.glass.launcher.ui.Ios
import com.glass.launcher.ui.NowPlayingModule
import com.glass.launcher.ui.SquircleShape
import com.glass.launcher.ui.frosted
import com.glass.launcher.ui.rememberNow
import com.glass.launcher.ui.thud
import com.glass.launcher.ui.tick
import com.glass.launcher.ui.timeAgo
import com.glass.launcher.util.Sys
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * iPhone-style lock screen shown ON TOP of the real Android keyguard (no root needed).
 * Security is unchanged: swiping up asks the system keyguard to dismiss, which still requires
 * the user's PIN / fingerprint / face. A fingerprint unlock closes this screen automatically.
 */
class LockActivity : ComponentActivity() {
    private val km by lazy { getSystemService(KeyguardManager::class.java) }

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { close() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setShowWhenLocked(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
        }
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
        setContent { LockScreen() }
    }

    override fun onResume() {
        super.onResume()
        // Phone awake but not actually locked (e.g. within the lock delay) -> don't get in the way.
        // (When we're started at screen-off the keyguard isn't locked *yet*, so only check while awake.)
        window.decorView.postDelayed({
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm.isInteractive && !km.isKeyguardLocked) close()
        }, 600)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        if (current === this) current = null
        super.onDestroy()
    }

    fun close() {
        if (!isFinishing) { finish(); overridePendingTransition(0, android.R.anim.fade_out) }
    }

    /** Ask the system to unlock (shows PIN/fingerprint if the phone is secure), then run [after]. */
    fun unlock(after: (() -> Unit)? = null, onCancel: () -> Unit = {}) {
        if (!km.isKeyguardLocked) { after?.invoke(); close(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { after?.invoke(); close() }
            override fun onDismissCancelled() = onCancel()
            override fun onDismissError() = onCancel()
        })
    }

    @Composable
    private fun LockScreen() {
        val app = GlassApp.instance
        val view = LocalView.current
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val now = rememberNow(1000)
        val notifs by NotificationService.items.collectAsState()
        val lift = remember { Animatable(0f) }
        var torch by remember { mutableStateOf(Sys.torchOn) }
        LaunchedEffect(Unit) { Sys.torchSetup(ctx) { torch = it } }
        BackHandler { }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationY = lift.value
                alpha = 1f - (-lift.value / size.height * 1.6f).coerceIn(0f, 1f)
            }) {
                app.wallpaper.image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))

                Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (km.isKeyguardSecure) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = Color.White,
                        modifier = Modifier.padding(top = 38.dp).size(20.dp))
                    Text(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)), color = Color.White.copy(alpha = 0.92f),
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now)), color = Color.White.copy(alpha = 0.95f),
                        fontSize = 104.sp, fontWeight = FontWeight.Bold, letterSpacing = (-4).sp, lineHeight = 108.sp)
                    Spacer(Modifier.weight(1f))
                    if (app.media.info != null) {
                        NowPlayingModule(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(150.dp), null)
                        Spacer(Modifier.height(10.dp))
                    }
                    LazyColumn(Modifier.fillMaxWidth().height(if (notifs.isEmpty()) 0.dp else 300.dp).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), reverseLayout = false) {
                        items(notifs.groupBy { it.pkg }.values.map { it.first() to it.size }.take(6), key = { it.first.key }) { (n, count) ->
                            LockNotification(n, count) { unlock(after = { NotificationService.open(ctx, n) }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 46.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        LockButton(Icons.Rounded.FlashlightOn, torch) { view.thud(); Sys.setTorch(ctx, !torch) }
                        Spacer(Modifier.weight(1f))
                        LockButton(Icons.Rounded.CameraAlt, false) {
                            view.thud()
                            unlock(after = { runCatching { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                        }
                    }
                    Text(if (km.isKeyguardSecure) "Swipe up to unlock" else "Swipe up to open", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    Box(Modifier.navigationBarsPadding().padding(top = 10.dp, bottom = 10.dp).width(134.dp).height(5.dp).background(Color.White, CircleShape))
                }
            }
            // swipe up anywhere to unlock
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onVerticalDrag = { c, dy -> c.consume(); acc += dy; scope.launch { lift.snapTo(acc.coerceAtMost(0f)) } },
                    onDragEnd = {
                        scope.launch {
                            if (acc < -size.height * 0.18f) {
                                view.tick()
                                lift.animateTo(-size.height.toFloat(), Ios.snappy())
                                unlock(onCancel = { scope.launch { lift.animateTo(0f, Ios.bouncy()) } })
                            } else lift.animateTo(0f, Ios.bouncy())
                        }
                    },
                )
            }.pointerInput(Unit) { detectTapGestures { } })
        }
    }

    @Composable
    private fun LockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onLongPress: () -> Unit) {
        // iPhone: torch/camera need a firm press -> long-press here (prevents pocket taps)
        Box(Modifier.size(52.dp).frosted(CircleShape, if (on) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.25f))
            .pointerInput(on) { detectTapGestures(onLongPress = { onLongPress() }) }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        }
    }

    @Composable
    private fun LockNotification(n: NotifItem, count: Int, onOpen: () -> Unit) {
        val icon = GlassApp.instance.repo.find(n.pkg)?.icon
        // While locked, show who it's from but not the message text (like iPhone "Show Previews: When Unlocked").
        Row(Modifier.fillMaxWidth().frosted(Ios.rr(22f), Color.White.copy(alpha = 0.22f))
            .pointerInput(n.key) { detectTapGestures(onTap = { onOpen() }) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, null, modifier = Modifier.size(36.dp)) else Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.3f), SquircleShape))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row {
                    Text(n.app, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(timeAgo(n.time), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                Text(if (count > 1) "$count Notifications" else (n.title.ifBlank { "Notification" }), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }

    companion object {
        @Volatile var current: LockActivity? = null

        /** Called by the island service when the screen turns off: have the lock screen ready for wake-up. */
        fun prepare(ctx: Context) {
            android.util.Log.i("GlassLock", "prepare: enabled=${GlassApp.instance.prefs.lockScreen}")
            if (!GlassApp.instance.prefs.lockScreen) return
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val am = ctx.getSystemService(AudioManager::class.java)
            @Suppress("DEPRECATION")
            val inCall = runCatching { tm.callState != TelephonyManager.CALL_STATE_IDLE }.getOrDefault(false) ||
                am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
            if (inCall) return // never cover a phone / WhatsApp call
            runCatching {
                android.util.Log.i("GlassLock", "starting lock activity")
                ctx.startActivity(Intent(ctx, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }.onFailure { android.util.Log.e("GlassLock", "start failed", it) }
        }
    }
}
