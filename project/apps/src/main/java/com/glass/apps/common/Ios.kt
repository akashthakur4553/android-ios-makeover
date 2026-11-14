package com.glass.apps.common

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kyant.shapes.RoundedRectangle

/** iOS system colours (light / dark). */
@Immutable
data class IosColors(
    val dark: Boolean,
    val bg: Color, val groupedBg: Color, val cell: Color, val cell2: Color,
    val label: Color, val label2: Color, val label3: Color, val separator: Color,
    val fill: Color, val bar: Color,
    val blue: Color = Color(0xFF0A84FF), val green: Color = Color(0xFF30D158), val red: Color = Color(0xFFFF3B30),
    val orange: Color = Color(0xFFFF9500), val yellow: Color = Color(0xFFFFCC00), val pink: Color = Color(0xFFFF2D55),
    val purple: Color = Color(0xFFAF52DE), val gray: Color = Color(0xFF8E8E93),
)

val LightColors = IosColors(
    dark = false, bg = Color.White, groupedBg = Color(0xFFF2F2F7), cell = Color.White, cell2 = Color(0xFFF2F2F7),
    label = Color.Black, label2 = Color(0x993C3C43), label3 = Color(0x4D3C3C43), separator = Color(0x4A3C3C43),
    fill = Color(0x1F787880), bar = Color(0xF2F9F9F9), blue = Color(0xFF007AFF), green = Color(0xFF34C759),
)
val DarkColors = IosColors(
    dark = true, bg = Color.Black, groupedBg = Color.Black, cell = Color(0xFF1C1C1E), cell2 = Color(0xFF2C2C2E),
    label = Color.White, label2 = Color(0x99EBEBF5), label3 = Color(0x4DEBEBF5), separator = Color(0x99545458),
    fill = Color(0x3D767680), bar = Color(0xF0161616),
)

val LocalIos = staticCompositionLocalOf { LightColors }
val ios: IosColors @Composable get() = LocalIos.current

object T {
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val title1 = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val title2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val title3 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val body = TextStyle(fontSize = 17.sp, letterSpacing = (-0.4).sp)
    val callout = TextStyle(fontSize = 16.sp, letterSpacing = (-0.3).sp)
    val subhead = TextStyle(fontSize = 15.sp, letterSpacing = (-0.2).sp)
    val footnote = TextStyle(fontSize = 13.sp, letterSpacing = (-0.1).sp)
    val caption = TextStyle(fontSize = 12.sp)
    val caption2 = TextStyle(fontSize = 11.sp, letterSpacing = 0.07.sp)
}

fun rr(r: Float) = RoundedRectangle(r.dp)

fun <T> iosSpring() = spring<T>(dampingRatio = 0.82f, stiffness = 420f)

/** Root theme: picks light/dark like iOS and sets status-bar icon colour. */
@Composable
fun IosTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    val c = if (dark) DarkColors else LightColors
    val view = LocalView.current
    val act = LocalContext.current as? Activity
    SideEffect {
        act?.window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            WindowInsetsControllerCompat(it, view).isAppearanceLightStatusBars = !dark
            WindowInsetsControllerCompat(it, view).isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalIos provides c) { content() }
}

/** iOS press feedback: slight dim + squish. */
fun Modifier.tap(scale: Float = 0.97f, dim: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 600f), label = "tap")
    this.graphicsLayer { scaleX = s; scaleY = s; alpha = if (pressed && dim) 0.55f else 1f }
        .clickable(src, null, onClick = onClick)
}

@Composable
fun Txt(t: String, style: TextStyle = T.body, color: Color = ios.label, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null) {
    Text(t, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = align)
}

/** Large-title header with optional leading/trailing actions (like UINavigationBar). */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, back: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackButton(back ?: "Back", onBack)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        Txt(title, T.largeTitle, modifier = Modifier.padding(bottom = 6.dp))
    }
}

/** Small centred title bar (for pushed screens). */
@Composable
fun NavBar(title: String, back: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().background(ios.bar).statusBarsPadding().height(44.dp).padding(horizontal = 8.dp)) {
        if (onBack != null) Box(Modifier.align(Alignment.CenterStart)) { BackButton(back ?: "Back", onBack) }
        Txt(title, T.headline, modifier = Modifier.align(Alignment.Center).padding(horizontal = 90.dp), maxLines = 1)
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
fun BackButton(label: String, onClick: () -> Unit) {
    Row(Modifier.tap(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = ios.blue, modifier = Modifier.size(20.dp))
        Txt(label, T.body, ios.blue, maxLines = 1)
    }
}

@Composable
fun BarButton(icon: ImageVector? = null, text: String? = null, color: Color = ios.blue, bold: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.tap(onClick = onClick).padding(4.dp)) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        else Txt(text ?: "", if (bold) T.headline else T.body, color)
    }
}

/** Translucent iOS tab bar. */
@Composable
fun TabBar(items: List<Pair<String, ImageVector>>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Column(modifier.fillMaxWidth().background(ios.bar)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, (label, icon) ->
                val col by animateColorAsState(if (i == selected) ios.blue else ios.gray, label = "tab")
                Column(Modifier.weight(1f).tap(0.9f, dim = false) { onSelect(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, tint = col, modifier = Modifier.size(26.dp))
                    Txt(label, T.caption2.copy(fontWeight = FontWeight.Medium), col, maxLines = 1)
                }
            }
        }
    }
}

/** UISegmentedControl with sliding thumb. */
@Composable
fun Segmented(items: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    BoxWithConstraints(modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(ios.fill).padding(2.dp)) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, iosSpring(), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxSize().clip(RoundedCornerShape(7.dp)).background(if (ios.dark) Color(0xFF636366) else Color.White))
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, s ->
                Box(Modifier.weight(1f).fillMaxSize().tap(1f, dim = false) { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Txt(s, T.footnote.copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    Row(modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(ios.fill).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = ios.gray, modifier = Modifier.size(19.dp))
        Box(Modifier.weight(1f).padding(start = 6.dp)) {
            if (value.isEmpty()) Txt(placeholder, T.body, ios.gray)
            BasicTextField(value, onChange, singleLine = true, textStyle = T.body.copy(color = ios.label), cursorBrush = SolidColor(ios.blue), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Icons.Rounded.Cancel, null, tint = ios.gray, modifier = Modifier.size(18.dp).tap { onChange("") })
    }
}

/** Inset grouped section (Settings-style). */
@Composable
fun Section(header: String? = null, footer: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp)) {
        if (header != null) Txt(header.uppercase(), T.footnote, ios.label2, Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(rr(12f)).background(ios.cell), content = content)
        if (footer != null) Txt(footer, T.footnote, ios.label2, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

@Composable
fun Divider(start: Dp = 16.dp) = Box(Modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(ios.separator))

@Composable
fun CellRow(title: String, value: String? = null, color: Color = ios.label, chevron: Boolean = true, leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.tap(1f, onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Txt(title, T.body, color, Modifier.weight(1f), maxLines = 1)
        if (value != null) Txt(value, T.body, ios.label2, maxLines = 1)
        if (chevron && onClick != null) Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = ios.label3, modifier = Modifier.padding(start = 6.dp).size(14.dp))
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (on) 20.dp else 0.dp, iosSpring(), label = "tg")
    val bg by animateColorAsState(if (on) ios.green else ios.fill, label = "tgc")
    Box(Modifier.size(51.dp, 31.dp).clip(CircleShape).background(bg).tap(1f, dim = false) { onChange(!on) }.padding(2.dp)) {
        Box(Modifier.offset(x = x).size(27.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val initials = name.split(' ', '.').filter { it.isNotBlank() && it.first().isLetter() }.take(2).joinToString("") { it.first().uppercase() }
    Box(modifier.size(size).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFA5A9B4), Color(0xFF858A96)))),
        contentAlignment = Alignment.Center) {
        if (initials.isNotEmpty()) Txt(initials, TextStyle(fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold), Color.White)
        else Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** Screen container with the right background. */
@Composable
fun Screen(grouped: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(if (grouped) ios.groupedBg else ios.bg), content = content)
}

// ------------------------------------------------------------------------------------------ permissions

/**
 * Shows [content] once all [perms] are granted; otherwise an iOS-style explainer with a button.
 */
@Composable
fun NeedsPermissions(perms: List<String>, title: String, why: String, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val missing = perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isEmpty()) { content(); return }
    Screen {
        Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(title, T.title2, align = TextAlign.Center)
            Txt(why, T.callout, ios.label2, Modifier.padding(top = 10.dp, bottom = 26.dp), align = TextAlign.Center)
            Box(Modifier.clip(rr(14f)).background(ios.blue).tap { launcher.launch(missing.toTypedArray()) }.padding(horizontal = 28.dp, vertical = 14.dp)) {
                Txt("Continue", T.headline, Color.White)
            }
            Txt("Open Settings", T.callout, ios.blue, Modifier.padding(top = 16.dp).tap {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            })
        }
    }
}

fun ComponentActivity.hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
