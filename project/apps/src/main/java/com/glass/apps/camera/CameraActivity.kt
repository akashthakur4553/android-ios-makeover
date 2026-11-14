package com.glass.apps.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.glass.apps.common.IosTheme
import com.glass.apps.common.NeedsPermissions
import com.glass.apps.common.T
import com.glass.apps.common.Txt
import com.glass.apps.common.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Yellow = Color(0xFFFFD60A)

class CameraActivity : ComponentActivity() {
    var shutter: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IosTheme(forceDark = true) {
                NeedsPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), "Allow Camera access",
                    "Camera takes photos and videos and saves them to your gallery.") { CameraScreen(this) }
            }
        }
    }

    /** Volume buttons act as the shutter, like iPhone. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && event?.repeatCount == 0) { shutter?.invoke(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

enum class Mode(val label: String) { TimeLapse("TIME-LAPSE"), Video("VIDEO"), Photo("PHOTO"), Portrait("PORTRAIT"), Square("SQUARE") }

private class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("camera", Context.MODE_PRIVATE)
    var grid by mutableStateOf(sp.getBoolean("grid", false)); private set
    var mirror by mutableStateOf(sp.getBoolean("mirror", true)); private set
    var wide by mutableStateOf(sp.getBoolean("wide", false)); private set // 16:9 vs 4:3
    var quality by mutableIntStateOf(sp.getInt("q", 1)); private set     // 0 HD, 1 FHD, 2 UHD
    fun updateGrid(v: Boolean) { grid = v; sp.edit().putBoolean("grid", v).apply() }
    fun updateMirror(v: Boolean) { mirror = v; sp.edit().putBoolean("mirror", v).apply() }
    fun updateWide(v: Boolean) { wide = v; sp.edit().putBoolean("wide", v).apply() }
    fun updateQuality(v: Int) { quality = v; sp.edit().putInt("q", v).apply() }
}

@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(act: CameraActivity) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(ctx) }
    val controller = remember { LifecycleCameraController(ctx) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var mode by remember { mutableStateOf(Mode.Photo) }
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var timer by remember { mutableIntStateOf(0) }
    var night by remember { mutableStateOf(false) }
    var hdr by remember { mutableStateOf(false) }
    var tray by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(4f) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recStart by remember { mutableLongStateOf(0L) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    val flashAnim = remember { Animatable(0f) }
    var ext by remember { mutableStateOf<ExtensionsManager?>(null) }
    var extNote by remember { mutableStateOf<String?>(null) }

    // Bind once
    LaunchedEffect(Unit) {
        controller.isTapToFocusEnabled = false
        controller.isPinchToZoomEnabled = false
        controller.bindToLifecycle(owner)
        previewView.controller = controller
        controller.zoomState.observe(owner) { z -> zoom = z.zoomRatio; maxZoom = z.maxZoomRatio.coerceAtMost(10f) }
        val pf = ProcessCameraProvider.getInstance(ctx)
        pf.addListener({ runCatching { val e = ExtensionsManager.getInstanceAsync(ctx, pf.get()); e.addListener({ ext = runCatching { e.get() }.getOrNull() }, ContextCompat.getMainExecutor(ctx)) } },
            ContextCompat.getMainExecutor(ctx))
    }
    // React to settings: lens, use case, aspect, quality, extensions
    LaunchedEffect(front, mode, settings.wide, settings.quality, night, hdr, ext) {
        val base = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val e = ext
        val wanted = when { mode == Mode.Portrait -> ExtensionMode.BOKEH; night && mode == Mode.Photo -> ExtensionMode.NIGHT; hdr && mode == Mode.Photo -> ExtensionMode.HDR; else -> null }
        extNote = null
        controller.cameraSelector = if (wanted != null && e != null && e.isExtensionAvailable(base, wanted)) e.getExtensionEnabledCameraSelector(base, wanted)
        else { if (wanted == ExtensionMode.BOKEH) extNote = "Portrait depth effect isn't available on this camera"; base }
        controller.setEnabledUseCases(if (mode == Mode.Video || mode == Mode.TimeLapse) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE)
        val ratio = if (settings.wide && mode != Mode.Square) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        controller.previewResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
        controller.imageCaptureResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
        controller.imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        controller.videoCaptureQualitySelector = QualitySelector.from(listOf(Quality.HD, Quality.FHD, Quality.UHD)[settings.quality],
            androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
    }
    LaunchedEffect(flash, mode) {
        controller.imageCaptureFlashMode = flash
        controller.enableTorch(mode == Mode.Video && flash == ImageCapture.FLASH_MODE_ON)
    }
    LaunchedEffect(focus) { if (focus != null) { delay(2500); focus = null } }

    fun saveBitmap(b: Bitmap): Uri? {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return uri
    }

    fun takePhoto() {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        scope.launch { flashAnim.snapTo(1f); flashAnim.animateTo(0f, tween(220)) }
        controller.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val raw = image.toBitmap(); image.close()
                Thread {
                    val m = Matrix().apply { postRotate(rot.toFloat()); if (front && settings.mirror) postScale(-1f, 1f) }
                    var b = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    if (mode == Mode.Square) { val s = minOf(b.width, b.height); b = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s) }
                    val u = runCatching { saveBitmap(b) }.getOrNull()
                    view.post { lastUri = u }
                }.start()
            }
            override fun onError(exception: ImageCaptureException) {}
        })
    }

    fun toggleVideo() {
        val r = recording
        if (r != null) { r.stop(); recording = null; return }
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val out = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera") }).build()
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        recStart = System.currentTimeMillis()
        recording = controller.startRecording(out, AudioConfig.create(mode != Mode.TimeLapse), ContextCompat.getMainExecutor(ctx)) { ev ->
            if (ev is VideoRecordEvent.Finalize && !ev.hasError()) lastUri = ev.outputResults.outputUri
        }
    }

    fun shoot() {
        when (mode) {
            Mode.Video, Mode.TimeLapse -> toggleVideo()
            else -> if (timer == 0) takePhoto() else scope.launch { for (i in timer downTo 1) { countdown = i; delay(1000) }; countdown = 0; takePhoto() }
        }
    }
    DisposableEffect(Unit) { act.shutter = { shoot() }; onDispose { act.shutter = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // ---------------- top bar
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 18.dp)) {
                if (recording != null) {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
                    val s = (now - recStart) / 1000
                    Txt("%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60), T.headline.copy(fontFeatureSettings = "tnum"), Color.White,
                        Modifier.align(Alignment.Center).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFF3B30)).padding(horizontal = 8.dp, vertical = 2.dp))
                } else {
                    Icon(when (flash) { ImageCapture.FLASH_MODE_ON -> Icons.Rounded.FlashOn; ImageCapture.FLASH_MODE_OFF -> Icons.Rounded.FlashOff; else -> Icons.Rounded.FlashAuto }, null,
                        tint = if (flash == ImageCapture.FLASH_MODE_ON) Yellow else Color.White, modifier = Modifier.align(Alignment.CenterStart).size(26.dp).tap {
                            flash = when (flash) { ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF; else -> ImageCapture.FLASH_MODE_AUTO }
                        })
                    Icon(if (tray) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp, null, tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).tap { tray = !tray })
                    Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (ext?.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.NIGHT) == true && mode == Mode.Photo)
                            Icon(Icons.Rounded.Bedtime, null, tint = if (night) Yellow else Color.White, modifier = Modifier.size(24.dp).tap { night = !night; if (night) hdr = false })
                        if (ext?.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.HDR) == true && mode == Mode.Photo)
                            Icon(Icons.Rounded.HdrOn, null, tint = if (hdr) Yellow else Color.White, modifier = Modifier.size(26.dp).tap { hdr = !hdr; if (hdr) night = false })
                    }
                }
            }
            // ---------------- settings tray (iOS chevron menu)
            AnimatedVisibility(tray, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TrayItem(if (settings.wide) "16:9" else "4:3", active = settings.wide) { settings.updateWide(!settings.wide) }
                    TrayIcon(Icons.Rounded.Timer, if (timer == 0) "Off" else "${timer}s", timer != 0) { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
                    TrayIcon(if (settings.grid) Icons.Rounded.GridOn else Icons.Rounded.GridOff, "Grid", settings.grid) { settings.updateGrid(!settings.grid) }
                    TrayItem("Mirror", settings.mirror) { settings.updateMirror(!settings.mirror) }
                    TrayItem(listOf("HD", "1080p", "4K")[settings.quality], settings.quality > 0) { settings.updateQuality((settings.quality + 1) % 3) }
                }
            }
            // ---------------- viewfinder
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val ratio = when { mode == Mode.Square -> 1f; settings.wide || mode == Mode.Video || mode == Mode.TimeLapse -> 9f / 16f; else -> 3f / 4f }
                Box(Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = maxHeight < maxWidth / ratio)) {
                    AndroidView({ previewView }, Modifier.fillMaxSize())
                    if (settings.grid) Canvas(Modifier.fillMaxSize()) {
                        val c = Color.White.copy(alpha = 0.35f)
                        for (i in 1..2) { drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx()); drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx()) }
                    }
                    // gestures: tap to focus, drag sun for exposure, pinch to zoom
                    Box(Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { p ->
                                focus = p; exposure = 0f
                                val pt = previewView.meteringPointFactory.createPoint(p.x, p.y)
                                runCatching { controller.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build()) }
                                runCatching { controller.cameraControl?.setExposureCompensationIndex(0) }
                            }, onDoubleTap = { front = !front })
                        }
                        .pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> controller.setZoomRatio((zoom * z).coerceIn(1f, maxZoom)) } }
                        .pointerInput(focus != null) {
                            if (focus != null) detectVerticalDragGestures { ch, d ->
                                ch.consume()
                                exposure = (exposure - d / 300f).coerceIn(-1f, 1f)
                                val range = controller.cameraInfo?.exposureState?.exposureCompensationRange
                                if (range != null) runCatching { controller.cameraControl?.setExposureCompensationIndex((exposure * if (exposure > 0) range.upper else -range.lower).roundToInt()) }
                            }
                        })
                    focus?.let { f ->
                        val sz = 76.dp
                        val px = with(density) { sz.toPx() }
                        val anim by animateFloatAsState(1f, tween(250), label = "f")
                        Box(Modifier.offset { IntOffset((f.x - px / 2).roundToInt(), (f.y - px / 2).roundToInt()) }.size(sz).graphicsLayer { scaleX = 1.3f - 0.3f * anim; scaleY = 1.3f - 0.3f * anim }
                            .border(1.5.dp, Yellow))
                        Icon(Icons.Rounded.WbSunny, null, tint = Yellow, modifier = Modifier.offset { IntOffset((f.x + px / 2 + 10).roundToInt(), (f.y - 12 - exposure * 80).roundToInt()) }.size(22.dp))
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = flashAnim.value }.background(Color.Black))
                    if (countdown > 0) Txt("$countdown", T.largeTitle.copy(fontSize = 120.sp, fontWeight = FontWeight.Thin), Color.White, Modifier.align(Alignment.Center))
                    extNote?.let { Txt(it, T.footnote, Color.White, Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(8.dp)) }
                    if (mode == Mode.Portrait) Txt("DEPTH EFFECT", T.caption.copy(fontWeight = FontWeight.SemiBold), Color.Black,
                        Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(4.dp)).background(Yellow).padding(horizontal = 6.dp, vertical = 2.dp))
                    // zoom pills
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = if (mode == Mode.Portrait) 40.dp else 14.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1f, 2f, 3f).filter { it <= maxZoom }.forEach { z ->
                            val sel = Math.abs(zoom - z) < 0.5f || (z == 1f && zoom < 1.5f)
                            Box(Modifier.size(if (sel) 38.dp else 30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).tap { controller.setZoomRatio(z) }, contentAlignment = Alignment.Center) {
                                Txt(if (sel) (if (zoom % 1f < 0.05f) "${zoom.toInt()}×" else "%.1f×".format(zoom)) else "${z.toInt()}", T.caption.copy(fontWeight = FontWeight.Bold), if (sel) Yellow else Color.White)
                            }
                        }
                    }
                }
            }
            // ---------------- mode carousel
            var dragX by remember { mutableFloatStateOf(0f) }
            val modes = Mode.entries
            val idx = modes.indexOf(mode)
            BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).pointerInput(idx) {
                detectHorizontalDragGestures(onDragEnd = {
                    if (dragX < -60 && idx < modes.lastIndex && recording == null) mode = modes[idx + 1]
                    if (dragX > 60 && idx > 0 && recording == null) mode = modes[idx - 1]
                    dragX = 0f
                }) { _, d -> dragX += d }
            }, contentAlignment = Alignment.CenterStart) {
                // each label gets 1/5 of the screen so every mode (incl. SQUARE) is fully visible
                val itemW = maxWidth / 5
                val offset by animateDpAsState(itemW * (2 - idx), label = "modes")
                Row(Modifier.offset(x = offset).width(itemW * modes.size)) {
                    modes.forEach { m ->
                        Txt(m.label, T.caption2.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp), if (m == mode) Yellow else Color.White,
                            Modifier.width(itemW).tap(1f, dim = false) { if (recording == null) mode = m }, maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            // ---------------- bottom controls
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 30.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f)).tap {
                    lastUri?.let { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, if (u.toString().contains("video")) "video/*" else "image/*")
                        .setClassName(ctx, "com.glass.apps.photos.PhotosActivity").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
                }) { lastUri?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
                Spacer(Modifier.weight(1f))
                val isVideo = mode == Mode.Video || mode == Mode.TimeLapse
                Box(Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).tap(0.9f, dim = false) { shoot() }, contentAlignment = Alignment.Center) {
                    val inner by animateDpAsState(if (recording != null) 30.dp else 64.dp, label = "rec")
                    val corner by animateDpAsState(if (recording != null) 7.dp else 32.dp, label = "rc")
                    Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(if (isVideo) Color(0xFFFF3B30) else Color.White))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).tap { if (recording == null) front = !front }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Cameraswitch, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun TrayItem(text: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Txt(text, T.footnote.copy(fontWeight = FontWeight.SemiBold), if (active) Yellow else Color.White)
    }
}

@Composable
private fun TrayIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).tap(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (active) Yellow else Color.White, modifier = Modifier.size(18.dp))
        Txt(text, T.footnote.copy(fontWeight = FontWeight.SemiBold), if (active) Yellow else Color.White, Modifier.padding(start = 4.dp))
    }
}
