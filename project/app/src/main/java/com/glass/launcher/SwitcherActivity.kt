package com.glass.launcher

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import com.glass.launcher.services.RecentApps
import com.glass.launcher.ui.AppIconImage
import com.glass.launcher.ui.Ios
import com.glass.launcher.ui.tick
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** iPhone-style app switcher: overlapping cards, tap to open, flick up to close. */
class SwitcherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        var blur = false
        if (Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled) {
            blur = true
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = (60 * resources.displayMetrics.density).toInt() }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(if (blur) 0.35f else 0.75f)
        RecentApps.seedFromUsage(this)
        setContent { Switcher() }
        // lift the instant cover once our first frame is on screen
        window.decorView.post { window.decorView.postOnAnimation { com.glass.launcher.services.IslandService.instance?.hideCover() } }
    }

    private fun goHome() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish(); overridePendingTransition(0, 0)
    }

    private fun open(pkg: String, bounds: android.graphics.Rect?) {
        val i = packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (i == null) { goHome(); return }
        val opts = bounds?.let { ActivityOptions.makeScaleUpAnimation(window.decorView, it.left, it.top, it.width(), it.height()).toBundle() }
        runCatching { startActivity(i, opts) }
        finish(); overridePendingTransition(0, 0)
    }

    private fun kill(pkg: String) {
        RecentApps.remove(pkg)
        runCatching { getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg) }
    }

    @Composable
    private fun Switcher() {
        val app = GlassApp.instance
        val list = RecentApps.mru.toList()
        val view = LocalView.current
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val enter by animateFloatAsState(if (shown) 1f else 0f, Ios.bouncy(), label = "in")
        BackHandler { list.firstOrNull()?.let { open(it, null) } ?: goHome() }

        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { goHome() } }) {
            val cardW = maxWidth * 0.66f
            val cardH = maxHeight * 0.66f
            if (list.isEmpty()) {
                Text("No Recent Apps", style = Ios.headline.copy(color = Color.White.copy(alpha = 0.7f)), modifier = Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }
            val state = rememberLazyListState()
            LazyRow(
                state = state,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().graphicsLayer { val s = 0.9f + 0.1f * enter; scaleX = s; scaleY = s; alpha = enter.coerceIn(0f, 1f) },
                contentPadding = PaddingValues(horizontal = (maxWidth - cardW) / 2),
                horizontalArrangement = Arrangement.spacedBy(-cardW * 0.38f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(list, key = { _, p -> p }) { idx, pkg ->
                    val entry = app.repo.find(pkg)
                    val dy = remember { Animatable(0f) }
                    val scope = rememberCoroutineScope()
                    var rect by remember { mutableStateOf<android.graphics.Rect?>(null) }
                    Column(
                        Modifier
                            .zIndex(-idx.toFloat())
                            .animateItem()
                            .graphicsLayer { translationY = dy.value; alpha = (1f + dy.value / 1400f).coerceIn(0f, 1f) }
                            .pointerInput(pkg) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { c, d -> c.consume(); scope.launch { dy.snapTo((dy.value + d).coerceAtMost(60f)) } },
                                    onDragEnd = {
                                        scope.launch {
                                            if (dy.value < -size.height * 0.25f) {
                                                view.tick()
                                                dy.animateTo(-size.height * 1.6f, Ios.snappy())
                                                kill(pkg)
                                                if (RecentApps.mru.isEmpty()) goHome()
                                            } else dy.animateTo(0f, Ios.bouncy())
                                        }
                                    },
                                )
                            }
                            .pointerInput(pkg) { detectTapGestures { open(pkg, rect) } },
                    ) {
                        Row(Modifier.padding(start = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIconImage(entry, 30.dp)
                            Text(entry?.label ?: pkg, style = Ios.headline.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                        Box(
                            Modifier.size(cardW, cardH).clip(Ios.rr(30f)).background(Color(0xFF1C1C1E))
                                .onGloballyPositioned { c ->
                                    val b = c.boundsInRoot()
                                    rect = android.graphics.Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val t = RecentApps.thumb(pkg)
                            if (t != null) Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else {
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2C2C2E), Color(0xFF111113)))))
                                AppIconImage(entry, 84.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
