package com.glass.launcher.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.GlassApp
import com.glass.launcher.services.NotifItem
import com.glass.launcher.services.NotificationService
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

fun timeAgo(t: Long): String {
    val d = System.currentTimeMillis() - t
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m ago"
        DateUtils.isToday(t) -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
    }
}

/**
 * iOS lock-screen style Notification Centre: big clock, Now Playing, and stacked glass
 * notification groups that expand on tap and dismiss with a swipe.
 */
@Composable
fun NotificationCentre(progress: Float, backdrop: Backdrop?, onClose: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: (Float) -> Unit) {
    if (progress <= 0.001f) return
    val ctx = LocalContext.current
    val items by NotificationService.items.collectAsState()
    val connected by NotificationService.connected.collectAsState()
    val enabled = connected || NotificationService.isEnabled(ctx)
    val now = rememberNow(1000)
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val list = rememberLazyListState()
    var pull by remember { mutableStateOf(0f) }
    val p = progress.coerceIn(0f, 1f)

    // Swiping up when the list is already at the bottom (or empty) closes the centre.
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && source == NestedScrollSource.UserInput) {
                    pull += available.y; onDrag((1f + pull / 900f).coerceIn(0f, 1f)); return available
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (pull != 0f) { onDragEnd(if (pull < -140f || available.y < -1500f) 0f else 1f); pull = 0f }
                return available
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = (p - 1f) * size.height }
            .background(Color.Black.copy(alpha = 0.22f)),
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().nestedScroll(nested),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 28.dp, bottom = 24.dp).pointerInput(Unit) { detectTapGestures { onClose() } },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)), color = Color.White.copy(alpha = 0.9f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now)), color = Color.White.copy(alpha = 0.92f), fontSize = 96.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3).sp, lineHeight = 100.sp)
                }
            }
            if (GlassApp.instance.media.info != null) item {
                NowPlayingModule(Modifier.fillMaxWidth().height(150.dp), backdrop)
            }
            if (!enabled) item {
                Column(Modifier.fillMaxWidth().glass(Ios.rr(24f), backdrop, tint = Color.White.copy(alpha = 0.12f)).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Notifications, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("Show notifications here", style = Ios.headline, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text("Allow Glass notification access to see and clear your notifications like on iPhone.", style = Ios.footnote, modifier = Modifier.padding(vertical = 8.dp))
                    Text("Turn On", style = Ios.headline.copy(color = Ios.Blue), modifier = Modifier.pressable({
                        Sys_openListenerSettings(ctx); onClose()
                    }))
                }
            }
            if (items.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Notification Centre", style = Ios.title1.copy(fontSize = 22.sp), modifier = Modifier.weight(1f))
                    var confirm by remember { mutableStateOf(false) }
                    Box(
                        Modifier.height(30.dp).glass(Ios.capsule, backdrop, tint = Color.White.copy(alpha = 0.16f), refraction = 0.4f)
                            .pressable({ if (confirm) { NotificationService.dismissAll(); confirm = false } else confirm = true })
                            .padding(horizontal = if (confirm) 12.dp else 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (confirm) Text("Clear", style = Ios.footnote.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                        else Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            val groups = items.groupBy { it.pkg }.values.sortedByDescending { g -> g.maxOf { it.time } }
            groups.forEach { g ->
                val pkg = g.first().pkg
                val open = expanded[pkg] == true || g.size == 1
                if (open) {
                    if (g.size > 1) item(key = "h$pkg") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.first().app, style = Ios.headline.copy(fontSize = 20.sp), modifier = Modifier.weight(1f))
                            Text("Show less", style = Ios.footnote.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.glass(Ios.capsule, backdrop, tint = Color.White.copy(alpha = 0.16f), refraction = 0.3f)
                                    .pressable({ expanded[pkg] = false }).padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    items(g, key = { it.key }) { n -> NotificationCard(n, backdrop, Modifier.animateItem(), onOpen = { onClose() }) }
                } else {
                    item(key = "s$pkg") {
                        Box(Modifier.animateItem().fillMaxWidth().pointerInput(pkg) { detectTapGestures { expanded[pkg] = true } }) {
                            // the "stack" peeking out underneath, like iOS
                            Box(Modifier.align(Alignment.BottomCenter).offset(y = 16.dp).padding(horizontal = 22.dp).fillMaxWidth().height(30.dp)
                                .glass(Ios.rr(18f), backdrop, tint = Color.White.copy(alpha = 0.06f), refraction = 0f, shadow = false))
                            Box(Modifier.align(Alignment.BottomCenter).offset(y = 8.dp).padding(horizontal = 11.dp).fillMaxWidth().height(30.dp)
                                .glass(Ios.rr(20f), backdrop, tint = Color.White.copy(alpha = 0.09f), refraction = 0f, shadow = false))
                            NotificationCard(g.first(), backdrop, Modifier, onOpen = { onClose() }, extra = "${g.size - 1} more notification" + if (g.size > 2) "s" else "")
                        }
                    }
                    item(key = "sp$pkg") { Spacer(Modifier.height(10.dp)) }
                }
            }
            if (enabled && items.isEmpty()) item {
                Text("No Older Notifications", style = Ios.footnote.copy(color = Color.White.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        // Home-indicator style grabber: swipe it up to close.
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp).width(134.dp).height(5.dp)
            .background(Color.White.copy(alpha = 0.7f), CircleShape))
    }
}

private fun Sys_openListenerSettings(ctx: android.content.Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun NotificationCard(n: NotifItem, backdrop: Backdrop?, modifier: Modifier, onOpen: () -> Unit, extra: String? = null) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    var gone by remember { mutableStateOf(false) }
    val appIcon = GlassApp.instance.repo.find(n.pkg)?.icon
    AnimatedVisibility(!gone, modifier = modifier, exit = shrinkVertically() + fadeOut()) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(dx.value.roundToInt(), 0) }
                    .glass(Ios.rr(22f), backdrop, tint = Color.White.copy(alpha = 0.14f), blurRadius = 20f, refraction = 0.5f)
                    .pointerInput(n.key) {
                        detectTapGestures(onTap = { view.confirm(); NotificationService.open(ctx, n); onOpen() })
                    }
                    .pointerInput(n.key) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { c, d -> c.consume(); scope.launch { dx.snapTo((dx.value + d).coerceAtMost(40f)) } },
                            onDragEnd = {
                                scope.launch {
                                    if (n.clearable && dx.value < -size.width * 0.35f) {
                                        view.tick()
                                        dx.animateTo(-size.width * 1.2f, Ios.snappy())
                                        gone = true
                                        NotificationService.dismiss(n.key)
                                    } else dx.animateTo(0f, Ios.bouncy())
                                }
                            },
                        )
                    }
                    .animateContentSize()
                    .padding(14.dp),
            ) {
                Box {
                    val big = n.largeIcon != null && n.isMessage
                    if (big) {
                        Image(n.largeIcon!!, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(CircleShape))
                        if (appIcon != null) Image(appIcon, null, modifier = Modifier.size(18.dp).align(Alignment.BottomEnd).offset(4.dp, 4.dp))
                    } else if (appIcon != null) Image(appIcon, null, modifier = Modifier.size(38.dp))
                    else Box(Modifier.size(38.dp).background(Color.White.copy(alpha = 0.2f), SquircleShape))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.title.ifBlank { n.app }, style = Ios.headline.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(timeAgo(n.time), style = Ios.caption.copy(fontSize = 13.sp), modifier = Modifier.padding(start = 6.dp))
                    }
                    if (n.text.isNotBlank()) Text(n.text, style = Ios.body.copy(fontSize = 15.sp, lineHeight = 19.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (extra != null) Text(extra, style = Ios.caption.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
