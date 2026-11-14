package com.glass.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.rotate
import com.glass.launcher.GlassApp
import com.glass.launcher.data.AppEntry
import com.glass.launcher.data.FolderItem
import com.glass.launcher.data.IconFactory
import com.kyant.backdrop.Backdrop

val SquircleShape = GenericShape { size, _ ->
    addPath(IconFactory.squirclePath(size.width, size.height).asComposePath())
}

private val clockPkgs = setOf("com.android.deskclock", "com.google.android.deskclock")
private val calendarPkgs = setOf("com.xiaomi.calendar", "com.google.android.calendar", "com.android.calendar")

@Composable
fun AppIconImage(entry: AppEntry?, size: Dp, modifier: Modifier = Modifier, pressed: Boolean = false) {
    Box(modifier.size(size)) {
        val pkg = entry?.key?.pkg
        val cls = entry?.component?.className ?: ""
        if (pkg == "com.glass.apps" && GlassAppIcon(cls, size)) { /* drawn */ }
        else if (cls.contains("TwelveKeyDialer") || cls.endsWith(".DialtactsActivity"))
            GlyphIcon(androidx.compose.material.icons.Icons.Rounded.Call, listOf(Color(0xFF5BF675), Color(0xFF1DBF3C)), size)
        else if (pkg == "com.android.contacts")
            GlyphIcon(androidx.compose.material.icons.Icons.Rounded.Person, listOf(Color(0xFFB8BCC6), Color(0xFF8A8F9B)), size)
        else if (pkg in clockPkgs) LiveClockIcon(Modifier.fillMaxSize())
        else if (pkg in calendarPkgs) LiveCalendarIcon(size)
        else if (entry != null) {
            Image(
                bitmap = entry.icon,
                contentDescription = entry.label,
                modifier = Modifier.fillMaxSize(),
                colorFilter = if (pressed) ColorFilter.tint(Color.Black.copy(alpha = 0.32f), BlendMode.SrcAtop) else null,
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.18f), SquircleShape))
        }
        if (pressed && (pkg in clockPkgs || pkg in calendarPkgs || pkg == "com.android.contacts")) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f), SquircleShape))
    }
}

/** Icons for the Glass companion apps (com.glass.apps). Returns false if not one of them. */
@Composable
fun GlassAppIcon(cls: String, size: Dp): Boolean {
    val I = androidx.compose.material.icons.Icons.Rounded
    when (cls.substringAfterLast('.')) {
        "ClockActivity" -> LiveClockIcon(Modifier.fillMaxSize())
        "CalendarActivity" -> LiveCalendarIcon(size)
        "PhoneActivity" -> GlyphIcon(I.Call, listOf(Color(0xFF5BF675), Color(0xFF1DBF3C)), size)
        "ContactsActivity" -> GlyphIcon(I.Person, listOf(Color(0xFFC9CCD3), Color(0xFF8A8F9B)), size)
        "MessagesActivity" -> GlyphIcon(androidx.compose.material.icons.Icons.Rounded.ChatBubble, listOf(Color(0xFF5BF675), Color(0xFF1DBF3C)), size)
        "MusicActivity" -> GlyphIcon(androidx.compose.material.icons.Icons.Rounded.MusicNote, listOf(Color(0xFFFF6A7A), Color(0xFFFA2D55)), size)
        "CameraActivity" -> Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFEDEDF0), Color(0xFF9A9AA2))), SquircleShape), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Rounded.CameraAlt, null, tint = Color(0xFF2C2C2E), modifier = Modifier.size(size * 0.56f))
        }
        "FilesActivity" -> Box(Modifier.fillMaxSize().background(Color.White, SquircleShape), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Rounded.Folder, null, tint = Color(0xFF1E88FF), modifier = Modifier.size(size * 0.62f))
        }
        "PhotosActivity" -> GlyphIcon(androidx.compose.material.icons.Icons.Rounded.PhotoLibrary, listOf(Color(0xFFFFB347), Color(0xFFFF4F8B)), size)
        else -> return false
    }
    return true
}

/** Original "Photos" mark: a white tile with a soft colour wheel. */
@Composable
fun PhotosIcon() {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().background(Color.White, SquircleShape)) {
        val r = size.minDimension * 0.30f
        val cols = listOf(0xFFFFB800, 0xFFFF7A00, 0xFFFF3B6B, 0xFFC04DFF, 0xFF4A6CFF, 0xFF00B3FF, 0xFF2ED16B, 0xFFB8E000).map { Color(it) }
        cols.forEachIndexed { i, c ->
            rotate(i * 45f, center) {
                drawOval(c.copy(alpha = 0.82f), topLeft = androidx.compose.ui.geometry.Offset(center.x - r * 0.42f, center.y - r * 1.25f),
                    size = androidx.compose.ui.geometry.Size(r * 0.84f, r * 1.25f), blendMode = androidx.compose.ui.graphics.BlendMode.Multiply)
            }
        }
    }
}

@Composable
fun GlyphIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, colors: List<Color>, size: Dp) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(colors), SquircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.56f))
    }
}

/** Live analog clock icon, like the iPhone Clock app. */
@Composable
fun LiveClockIcon(modifier: Modifier) {
    val now = rememberNow(1000)
    androidx.compose.foundation.Canvas(modifier.background(Color.Black, SquircleShape)) {
        val c = center
        val r = size.minDimension * 0.43f
        drawCircle(Color.White, r, c)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        for (i in 0 until 60) {
            val big = i % 5 == 0
            rotate(i * 6f, c) {
                drawLine(Color.Black.copy(alpha = if (big) 0.85f else 0.35f),
                    androidx.compose.ui.geometry.Offset(c.x, c.y - r * 0.95f),
                    androidx.compose.ui.geometry.Offset(c.x, c.y - r * (if (big) 0.82f else 0.89f)),
                    strokeWidth = r * (if (big) 0.035f else 0.018f))
            }
        }
        val sec = cal.get(java.util.Calendar.SECOND)
        val min = cal.get(java.util.Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(java.util.Calendar.HOUR_OF_DAY) % 12) + min / 60f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        rotate(hr * 30f, c) { drawLine(Color.Black, c, androidx.compose.ui.geometry.Offset(c.x, c.y - r * 0.5f), r * 0.075f, cap) }
        rotate(min * 6f, c) { drawLine(Color.Black, c, androidx.compose.ui.geometry.Offset(c.x, c.y - r * 0.78f), r * 0.055f, cap) }
        rotate(sec * 6f, c) {
            drawLine(Ios.Orange, androidx.compose.ui.geometry.Offset(c.x, c.y + r * 0.18f), androidx.compose.ui.geometry.Offset(c.x, c.y - r * 0.86f), r * 0.025f, cap)
        }
        drawCircle(Color.Black, r * 0.07f, c)
        drawCircle(Ios.Orange, r * 0.04f, c)
    }
}

/** Live date icon, like the iPhone Calendar app. */
@Composable
fun LiveCalendarIcon(size: Dp) {
    val now = rememberNow(60_000)
    val d = java.util.Date(now)
    Column(Modifier.fillMaxSize().background(Color.White, SquircleShape), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Text(java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(d).uppercase(),
            color = Ios.Red, fontSize = (size.value * 0.17f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(top = size * 0.08f), lineHeight = (size.value * 0.2f).sp)
        androidx.compose.material3.Text(java.text.SimpleDateFormat("d", java.util.Locale.getDefault()).format(d),
            color = Color.Black, fontSize = (size.value * 0.5f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
            lineHeight = (size.value * 0.56f).sp, letterSpacing = (-1).sp)
    }
}

/** iOS folder: a glass squircle holding a 3x3 preview of its apps. */
@Composable
fun FolderIconView(folder: FolderItem, size: Dp, backdrop: Backdrop?, highlighted: Boolean = false, pressed: Boolean = false) {
    val repo = GlassApp.instance.repo
    val scale by androidx.compose.animation.core.animateFloatAsState(if (highlighted) 1.18f else 1f, Ios.bouncy(), label = "fh")
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(Ios.rr(size.value * 0.235f), backdrop, tint = Color.White.copy(alpha = if (pressed) 0.04f else 0.16f), blurRadius = 16f, refraction = 0.6f),
        contentAlignment = Alignment.Center,
    ) {
        val mini = size * 0.215f
        val gap = size * 0.06f
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (r in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (c in 0 until 3) {
                    val k = folder.apps.getOrNull(r * 3 + c)
                    if (k != null) AppIconImage(repo.byKey[k], mini) else Spacer(Modifier.size(mini))
                }
            }
        }
    }
}

/** Jiggle mode wobble — each icon gets its own phase so the grid feels alive. */
fun Modifier.jiggle(enabled: Boolean, seed: Int): Modifier = if (!enabled) this else composed {
    val t = rememberInfiniteTransition(label = "jiggle")
    val period = 128 + (seed.mod(5)) * 9
    val r by t.animateFloat(
        initialValue = -1.7f, targetValue = 1.7f,
        animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Reverse, StartOffset(seed.mod(period))),
        label = "rot",
    )
    val y by t.animateFloat(
        initialValue = -0.6f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(period + 37, easing = LinearEasing), RepeatMode.Reverse, StartOffset(seed.mod(97))),
        label = "y",
    )
    graphicsLayer { rotationZ = r; translationY = y * density }
}

@Composable
fun MinusBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(22.dp)
            .pressable(onClick, scaleTo = 0.85f, haptic = true)
            .background(Color(0xFF6E6E73).copy(alpha = 0.92f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Remove, null, tint = Color.Black, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun Dots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier.background(Color.Black.copy(alpha = 0.12f), CircleShape).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(7.dp).background(Color.White.copy(alpha = if (i == current) 1f else 0.35f), CircleShape))
        }
    }
}
