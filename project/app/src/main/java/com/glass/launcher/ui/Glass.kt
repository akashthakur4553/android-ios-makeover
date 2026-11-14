package com.glass.launcher.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.GlassApp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow as GlassShadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/** The wallpaper layer — what glass surfaces on the home screen refract. */
val LocalWallpaperBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Backdrops whose content is already blurred: glass on them skips the (expensive) live blur. */
object PreBlurred { val set: MutableSet<Backdrop> = java.util.Collections.newSetFromMap(java.util.WeakHashMap()) }

object Ios {
    val Blue = Color(0xFF0A84FF)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)
    val Purple = Color(0xFFBF5AF2)
    val Indigo = Color(0xFF5E5CE6)
    val Gray = Color(0xFF8E8E93)
    val Label2 = Color(0xFFEBEBF5).copy(alpha = 0.6f)
    val Separator = Color(0xFF545458).copy(alpha = 0.6f)
    val GroupedBg = Color(0xFF000000)
    val Cell = Color(0xFF1C1C1E)
    val Cell2 = Color(0xFF2C2C2E)

    val iconShadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 6f)
    val label = TextStyle(color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, shadow = iconShadow, letterSpacing = 0.1.sp)
    val body = TextStyle(color = Color.White, fontSize = 16.sp, letterSpacing = (-0.2).sp)
    val headline = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val footnote = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, letterSpacing = (-0.1).sp)
    val caption = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    val title1 = TextStyle(color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val largeTitle = TextStyle(color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)

    fun rr(radius: Float) = RoundedRectangle(radius.dp)
    val capsule = Capsule()

    /** iOS-feel spring: quick, slightly bouncy. */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.78f, stiffness = 380f)
    fun <T> snappy() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)
}

/**
 * Liquid Glass surface: refracts + blurs whatever is behind it (via the Kyant backdrop library),
 * with a specular rim highlight. Falls back to a frosted fill when no backdrop is available.
 */
fun Modifier.glass(
    shape: Shape,
    backdrop: Backdrop?,
    tint: Color = Color.White.copy(alpha = 0.10f),
    blurRadius: Float = 12f,
    refraction: Float = 1f,
    shadow: Boolean = true,
    hero: Boolean = false,
): Modifier {
    if (backdrop == null) return this.frosted(shape, tint)
    // Anything that moves (widgets, tiles, folders) uses the cheap path: same look, ~zero GPU cost.
    if (!hero && backdrop in PreBlurred.set) return this.fastGlass(shape, tint)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            if (backdrop !in PreBlurred.set) blur(blurRadius.dp.toPx())
            if (refraction > 0f && GlassApp.instance.prefs.liquidGlass) {
                lens(14.dp.toPx() * refraction, 26.dp.toPx() * refraction, depthEffect = true)
            }
        },
        highlight = { Highlight.Default.copy(alpha = 0.9f) },
        shadow = if (shadow) ({ GlassShadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.18f)) }) else null,
        onDrawSurface = { drawRect(tint) },
    )
}

private val rimBrush = androidx.compose.ui.graphics.Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.55f), 0.35f to Color.White.copy(alpha = 0.08f),
    0.65f to Color.White.copy(alpha = 0.04f), 1f to Color.White.copy(alpha = 0.30f),
)

/** Translucent panel + specular rim — for surfaces that already sit on a blurred background. */
fun Modifier.frosted(shape: Shape, tint: Color): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
    val fill = tint.copy(alpha = (tint.alpha + 0.10f).coerceAtMost(0.92f))
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.3f)),
        start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, size.height),
    )
    onDrawBehind {
        drawPath(path, fill)
        drawPath(path, brush, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

/**
 * Cheap glass: paints the matching patch of the pre-blurred wallpaper behind the element,
 * plus tint and a specular rim. One bitmap draw, no off-screen passes.
 */
fun Modifier.fastGlass(shape: Shape, tint: Color): Modifier = composed {
    val wp = GlassApp.instance.wallpaper.blurred
    val pos = remember { floatArrayOf(0f, 0f) }
    val tick = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val view = LocalView.current
    this
        .onGloballyPositioned { c ->
            val p = c.positionInRoot()
            if (p.x != pos[0] || p.y != pos[1]) { pos[0] = p.x; pos[1] = p.y; tick.intValue++ }
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
            val brush = androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.28f)),
                start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, size.height),
            )
            val sw = view.rootView.width.toFloat().coerceAtLeast(1f)
            val sh = view.rootView.height.toFloat().coerceAtLeast(1f)
            onDrawBehind {
                @Suppress("UNUSED_VARIABLE") val t = tick.intValue // re-draw when moved
                clipPath(path) {
                    if (wp != null) {
                        // wallpaper is drawn ContentScale.Crop on the full window
                        val scale = maxOf(sw / wp.width, sh / wp.height)
                        val dw = wp.width * scale
                        val dh = wp.height * scale
                        val ox = (sw - dw) / 2 - pos[0]
                        val oy = (sh - dh) / 2 - pos[1]
                        drawImage(wp, srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(wp.width, wp.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset(ox.toInt(), oy.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
                        drawRect(Color.White.copy(alpha = 0.06f)) // vibrancy lift
                    }
                    drawRect(tint)
                }
                drawPath(path, brush, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
        }
}

/** iOS-style "squish" on press. */
fun Modifier.pressable(onClick: () -> Unit, scaleTo: Float = 0.94f, haptic: Boolean = false): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleTo else 1f, Ios.bouncy(), label = "press")
    val view = LocalView.current
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(src, indication = null) {
            if (haptic) view.tick()
            onClick()
        }
}

fun View.tick() {
    if (GlassApp.instance.prefs.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

fun View.thud() {
    if (GlassApp.instance.prefs.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

fun View.confirm() {
    if (GlassApp.instance.prefs.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

@Composable
fun wallpaperBackdrop(): Backdrop? = LocalWallpaperBackdrop.current
