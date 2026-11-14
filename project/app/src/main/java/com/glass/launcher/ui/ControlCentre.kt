package com.glass.launcher.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VideoCameraFront
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.glass.launcher.GlassApp
import com.glass.launcher.util.Sys
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Drives the interactive pull-down overlays (Spotlight, Notification Centre, Control Centre). */
class OverlayCtl(private val scope: CoroutineScope) {
    val spotlight = Animatable(0f)
    val nc = Animatable(0f)
    val cc = Animatable(0f)

    val amount: Float get() = maxOf(spotlight.value, nc.value, cc.value)
    val anyOpen: Boolean get() = spotlight.targetValue > 0f || nc.targetValue > 0f || cc.targetValue > 0f

    fun drag(a: Animatable<Float, *>, v: Float) {
        scope.launch { a.snapTo(v.coerceIn(0f, 1.08f)) }
    }

    fun settle(a: Animatable<Float, *>, open: Boolean, velocity: Float = 0f) {
        scope.launch { a.animateTo(if (open) 1f else 0f, spring(dampingRatio = if (open) 0.82f else 1f, stiffness = 320f), initialVelocity = velocity) }
    }

    fun open(a: Animatable<Float, *>) {
        listOf(spotlight, nc, cc).filter { it !== a }.forEach { settle(it, false) }
        settle(a, true)
    }

    fun closeAll() = listOf(spotlight, nc, cc).forEach { settle(it, false) }
}

@Composable
fun ControlCentre(progress: Float, backdrop: Backdrop?, onClose: () -> Unit, onDragClose: (Float) -> Unit = {}, onDragEnd: (Float) -> Unit = {}) {
    if (progress <= 0.001f) return
    val ctx = LocalContext.current
    val view = LocalView.current
    var tick by remember { mutableIntStateOf(0) }
    var torch by remember { mutableStateOf(Sys.torchOn) }
    LaunchedEffect(Unit) {
        Sys.torchSetup(ctx) { torch = it; tick++ }
        while (true) { delay(1200); tick++ }
    }
    val wifi = remember(tick) { Sys.wifiOn(ctx) }
    val bt = remember(tick) { Sys.btOn() }
    val air = remember(tick) { Sys.airplaneOn(ctx) }
    val data = remember(tick) { Sys.mobileDataOn(ctx) }
    val rot = remember(tick) { Sys.rotationLocked(ctx) }
    val dnd = remember(tick) { Sys.dnd(ctx) }
    var bright by remember { mutableFloatStateOf(Sys.brightness(ctx)) }
    var vol by remember { mutableFloatStateOf(Sys.volume(ctx)) }
    val p = progress.coerceIn(0f, 1f)
    var dragAcc by remember { mutableFloatStateOf(0f) }
    var more by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f * p))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragAcc = 0f },
                    onVerticalDrag = { _, dy -> dragAcc += dy; if (dragAcc < 0) onDragClose((1f + dragAcc / (size.height * 0.5f)).coerceIn(0f, 1f)) },
                    onDragEnd = { onDragEnd((1f + dragAcc / (size.height * 0.5f)).coerceIn(0f, 1f)) },
                )
            },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 26.dp, vertical = 30.dp)
                .graphicsLayer {
                    val s = lerp(0.82f, 1f, progress.coerceAtMost(1.08f))
                    scaleX = s; scaleY = s
                    alpha = (p * 1.4f).coerceAtMost(1f)
                    transformOrigin = TransformOrigin(0.9f, 0f)
                    translationY = (1f - p) * -60.dp.toPx()
                },
        ) {
            val gap = 14.dp
            val cell = (maxWidth - gap * 3) / 4
            val bd = backdrop
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    // Connectivity
                    Box(Modifier.size(cell * 2 + gap).glass(Ios.rr(34f), bd, tint = Color.White.copy(alpha = 0.10f), blurRadius = 22f, refraction = 0.7f)) {
                        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                CircleToggle(Icons.Rounded.AirplanemodeActive, air, Ios.Orange, cell * 0.62f) { Sys.panel(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS); onClose() }
                                CircleToggle(Icons.Rounded.SettingsInputAntenna, data, Ios.Green, cell * 0.62f) { Sys.panel(ctx, Settings.Panel.ACTION_INTERNET_CONNECTIVITY); onClose() }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                CircleToggle(Icons.Rounded.Wifi, wifi, Ios.Blue, cell * 0.62f, onLong = { Sys.panel(ctx, Settings.Panel.ACTION_WIFI); onClose() }) { Sys.setWifi(ctx, !wifi); tick++ }
                                CircleToggle(Icons.Rounded.Bluetooth, bt, Ios.Blue, cell * 0.62f, onLong = { Sys.panel(ctx, Settings.ACTION_BLUETOOTH_SETTINGS); onClose() }) { Sys.setBt(ctx, !bt); tick++ }
                            }
                        }
                    }
                    NowPlayingModule(Modifier.size(cell * 2 + gap), bd)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            RoundButton(if (rot) Icons.Rounded.ScreenLockRotation else Icons.Rounded.ScreenRotation, rot, Ios.Red, cell, bd) {
                                Sys.setRotationLocked(ctx, !rot); tick++
                            }
                            RoundButton(Icons.Rounded.WifiTethering, false, Ios.Green, cell, bd) { Sys.hotspot(ctx); onClose() }
                        }
                        // Focus
                        Row(
                            Modifier.width(cell * 2 + gap).height(cell)
                                .glass(Ios.capsule, bd, tint = if (dnd) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.10f), blurRadius = 22f, refraction = 0.6f)
                                .pressable({ Sys.setDnd(ctx, !dnd); tick++ }, haptic = true)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(cell * 0.62f).background(if (dnd) Ios.Indigo else Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Bedtime, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Column(Modifier.padding(start = 8.dp)) {
                                Text("Focus", style = Ios.headline.copy(fontSize = 15.sp, color = if (dnd) Color.Black else Color.White))
                                Text(if (dnd) "Do Not Disturb" else "Off", style = Ios.caption.copy(color = if (dnd) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f)))
                            }
                        }
                    }
                    VerticalSlider(bright, Icons.Rounded.WbSunny, Modifier.width(cell).height(cell * 2 + gap), bd) {
                        bright = it
                        if (Sys.canWriteSettings(ctx)) Sys.setBrightness(ctx, it) else { Sys.requestWriteSettings(ctx); onClose() }
                    }
                    VerticalSlider(vol, Icons.Rounded.VolumeUp, Modifier.width(cell).height(cell * 2 + gap), bd) { vol = it; Sys.setVolume(ctx, it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    RoundButton(Icons.Rounded.FlashlightOn, torch, Color.White, cell, bd, activeIcon = Color(0xFF1C1C1E)) { Sys.setTorch(ctx, !torch) }
                    RoundButton(Icons.Rounded.Timer, false, Ios.Orange, cell, bd) { Sys.timer(ctx); onClose() }
                    RoundButton(Icons.Rounded.Calculate, false, Ios.Orange, cell, bd) { Sys.calculator(ctx); onClose() }
                    RoundButton(Icons.Rounded.CameraAlt, false, Ios.Orange, cell, bd) { Sys.camera(ctx); onClose() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    RoundButton(Icons.Rounded.Contrast, false, Ios.Orange, cell, bd) { Sys.display(ctx); onClose() }
                    RoundButton(Icons.Rounded.VideoCameraFront, false, Ios.Red, cell, bd) { Sys.screenRecorder(ctx); onClose() }
                    RoundButton(Icons.Rounded.QrCodeScanner, false, Ios.Orange, cell, bd) { Sys.qrScanner(ctx); onClose() }
                    RoundButton(Icons.Rounded.BatterySaver, false, Ios.Orange, cell, bd) { Sys.batterySaver(ctx); onClose() }
                }
                MoreControls(cell, gap, bd, more, onToggle = { more = !more }, onClose = onClose)
            }
        }
    }
}

@Composable
fun CircleToggle(icon: ImageVector, on: Boolean, color: Color, size: Dp, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (on) color else Color.White.copy(alpha = 0.16f))
            .pointerInput(on) {
                detectTapGestures(
                    onTap = { view.tick(); onClick() },
                    onLongPress = { view.thud(); onLong?.invoke() },
                )
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.48f)) }
}

@Composable
fun RoundButton(icon: ImageVector, on: Boolean, color: Color, size: Dp, backdrop: Backdrop?, activeIcon: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .glass(CircleShapeRR, backdrop, tint = if (on) color.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.10f), blurRadius = 22f, refraction = 0.5f)
            .pressable(onClick, scaleTo = 0.9f, haptic = true),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (on) activeIcon else Color.White, modifier = Modifier.size(size * 0.4f))
    }
}

val CircleShapeRR = com.kyant.shapes.Capsule()

@Composable
fun VerticalSlider(value: Float, icon: ImageVector, modifier: Modifier, backdrop: Backdrop?, onChange: (Float) -> Unit) {
    val view = LocalView.current
    var v by remember { mutableFloatStateOf(value) }
    var h by remember { mutableFloatStateOf(1f) }
    Box(
        modifier
            .glass(Ios.rr(30f), backdrop, tint = Color.White.copy(alpha = 0.10f), blurRadius = 22f, refraction = 0.6f)
            .clip(Ios.rr(30f))
            .pointerInput(Unit) {
                h = size.height.toFloat()
                detectVerticalDragGestures(onVerticalDrag = { c, dy ->
                    c.consume()
                    val nv = (v - dy / h).coerceIn(0f, 1f)
                    if ((nv == 0f || nv == 1f) && nv != v) view.tick()
                    v = nv; onChange(nv)
                })
            }
            .pointerInput(Unit) {
                detectTapGestures { o -> val nv = (1f - o.y / size.height).coerceIn(0f, 1f); v = nv; onChange(nv) }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(v).background(Color.White))
        Icon(icon, null, tint = if (v > 0.12f) Color(0xFF8E8E93) else Color.White, modifier = Modifier.padding(bottom = 16.dp).size(26.dp))
    }
}

@Composable
fun NowPlayingModule(modifier: Modifier, backdrop: Backdrop?) {
    val media = GlassApp.instance.media
    val m = media.info
    Box(modifier.glass(Ios.rr(34f), backdrop, tint = Color.White.copy(alpha = 0.10f), blurRadius = 22f, refraction = 0.7f).padding(16.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(Ios.rr(9f)).background(Color.White.copy(alpha = 0.16f)).pressable({ media.openApp() }), contentAlignment = Alignment.Center) {
                    if (m?.art != null) Image(m.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(m?.title ?: "Not Playing", style = Ios.headline.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m?.artist ?: "", style = Ios.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FastRewind, null, tint = Color.White.copy(alpha = if (m == null) 0.4f else 1f), modifier = Modifier.size(28.dp).pressable({ media.previous() }, haptic = true))
                Icon(if (m?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = if (m == null) 0.4f else 1f), modifier = Modifier.size(34.dp).pressable({ media.playPause() }, haptic = true))
                Icon(Icons.Rounded.FastForward, null, tint = Color.White.copy(alpha = if (m == null) 0.4f else 1f), modifier = Modifier.size(28.dp).pressable({ media.next() }, haptic = true))
            }
        }
    }
}


/** Second Control Centre page (replaces jumping to the stock quick-settings panel). */
@Composable
private fun MoreControls(cell: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp, bd: Backdrop?, open: Boolean, onToggle: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val autoB = remember(tick) { Sys.autoBrightness(ctx) }
    val silent = remember(tick) { Sys.silent(ctx) }
    androidx.compose.animation.AnimatedVisibility(open) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                RoundButton(Icons.Rounded.BrightnessAuto, autoB, Ios.Blue, cell, bd) { Sys.setAutoBrightness(ctx, !autoB); tick++ }
                RoundButton(Icons.Rounded.NotificationsOff, silent, Ios.Red, cell, bd) { Sys.setSilent(ctx, !silent); tick++ }
                RoundButton(Icons.Rounded.Screenshot, false, Ios.Blue, cell, bd) {
                    onClose(); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Sys.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 450)
                }
                RoundButton(Icons.Rounded.Lock, false, Ios.Blue, cell, bd) { onClose(); Sys.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                RoundButton(Icons.Rounded.LocationOn, false, Ios.Blue, cell, bd) { Sys.panel(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS); onClose() }
                RoundButton(Icons.Rounded.Nfc, false, Ios.Blue, cell, bd) { Sys.panel(ctx, Settings.Panel.ACTION_NFC); onClose() }
                RoundButton(Icons.Rounded.Cast, false, Ios.Blue, cell, bd) { Sys.panel(ctx, Settings.ACTION_CAST_SETTINGS); onClose() }
                RoundButton(Icons.Rounded.VolumeUp, false, Ios.Blue, cell, bd) { Sys.panel(ctx, Settings.Panel.ACTION_VOLUME); onClose() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                RoundButton(Icons.Rounded.Language, false, Ios.Green, cell, bd) { Sys.panel(ctx, Settings.Panel.ACTION_INTERNET_CONNECTIVITY); onClose() }
                RoundButton(Icons.Rounded.DataSaverOn, false, Ios.Orange, cell, bd) { Sys.panel(ctx, Settings.ACTION_DATA_USAGE_SETTINGS); onClose() }
                RoundButton(Icons.Rounded.DarkMode, false, Ios.Indigo, cell, bd) { Sys.display(ctx); onClose() }
                RoundButton(Icons.Rounded.PowerSettingsNew, false, Ios.Red, cell, bd) { onClose(); Sys.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.glass(Ios.capsule, bd, tint = Color.White.copy(alpha = 0.08f), refraction = 0.4f)
                .pressable(onToggle, haptic = true)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
            Text(if (open) "Fewer Controls" else "More Controls", style = Ios.caption.copy(color = Color.White.copy(alpha = 0.8f)), modifier = Modifier.padding(start = 4.dp))
        }
    }
}
