package com.glass.launcher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CalendarContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowRight
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.GlassApp
import com.glass.launcher.data.AppEntry
import com.glass.launcher.data.WeatherRepo
import com.glass.launcher.data.WidgetKind
import com.glass.launcher.data.WidgetSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

val WidgetShape = Ios.rr(24f)

/** Ticks once a minute (or second) so time-based widgets stay fresh. */
@Composable
fun rememberNow(everyMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(everyMs) {
        while (true) {
            val n = System.currentTimeMillis()
            delay(everyMs - n % everyMs + 5); now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun WidgetView(kind: WidgetKind, size: WidgetSize, modifier: Modifier = Modifier, onRequestPermission: (String) -> Unit = {}) {
    val bd = wallpaperBackdrop()
    when (kind) {
        WidgetKind.Weather -> WeatherWidget(size, modifier, onRequestPermission)
        WidgetKind.Calendar -> GlassCard(modifier, bd) { CalendarWidget(size, onRequestPermission) }
        WidgetKind.Clock -> ClockWidget(size, modifier)
        WidgetKind.Battery -> GlassCard(modifier, bd) { BatteryWidget(size) }
        WidgetKind.Music -> GlassCard(modifier, bd) { MusicWidget(size) }
        WidgetKind.Suggestions -> GlassCard(modifier, bd) { SuggestionsWidget(size) }
    }
}

@Composable
fun GlassCard(modifier: Modifier, backdrop: com.kyant.backdrop.Backdrop?, tint: Color = Color.White.copy(alpha = 0.10f), content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glass(WidgetShape, backdrop, tint = tint, blurRadius = 18f, refraction = 0.8f).padding(14.dp), content = content)
}

// ------------------------------------------------------------------------------------------ Weather

fun weatherIcon(code: Int, day: Boolean): ImageVector = when (code) {
    0, 1 -> if (day) Icons.Rounded.WbSunny else Icons.Rounded.DarkMode
    2 -> Icons.Rounded.WbCloudy
    3, 45, 48 -> Icons.Rounded.Cloud
    in 51..67, in 80..82 -> Icons.Rounded.Grain
    in 71..77, 85, 86 -> Icons.Rounded.AcUnit
    in 95..99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Air
}

private fun skyBrush(code: Int?, day: Boolean): Brush = when {
    code == null -> Brush.verticalGradient(listOf(Color(0xFF2E6FD8), Color(0xFF5B9BEA)))
    !day -> Brush.verticalGradient(listOf(Color(0xFF0B1730), Color(0xFF2A3A63)))
    code >= 51 -> Brush.verticalGradient(listOf(Color(0xFF3D4D63), Color(0xFF6E7F96)))
    code >= 2 -> Brush.verticalGradient(listOf(Color(0xFF4A79B7), Color(0xFF8BAFD9)))
    else -> Brush.verticalGradient(listOf(Color(0xFF1E6BD6), Color(0xFF5DA9F2)))
}

@Composable
fun WeatherWidget(size: WidgetSize, modifier: Modifier, onRequestPermission: (String) -> Unit) {
    val repo = GlassApp.instance.weather
    val w = repo.weather
    LaunchedEffect(Unit) { repo.refreshIfStale() }
    Box(modifier.clip(WidgetShape).background(skyBrush(w?.code, w?.isDay ?: true)).padding(14.dp)) {
        if (w == null) {
            Column(Modifier.fillMaxSize().pressable({ if (repo.needsPermission) onRequestPermission(Manifest.permission.ACCESS_COARSE_LOCATION) else repo.refresh() })) {
                Text("Weather", style = Ios.headline)
                Spacer(Modifier.weight(1f))
                Text(if (repo.needsPermission) "Tap to allow location for local weather" else "Loading…", style = Ios.footnote)
            }
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.city, style = Ios.headline.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                Icon(Icons.Rounded.NearMe, null, tint = Color.White, modifier = Modifier.padding(start = 3.dp).size(11.dp))
            }
            Text("${w.temp}°", color = Color.White, fontSize = if (size == WidgetSize.Small) 42.sp else 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
            if (size == WidgetSize.Small) {
                Spacer(Modifier.weight(1f))
                Icon(weatherIcon(w.code, w.isDay), null, tint = if (w.code <= 1 && w.isDay) Color(0xFFFFD60A) else Color.White, modifier = Modifier.size(18.dp))
                Text(WeatherRepo.describe(w.code), style = Ios.footnote.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                Text("H:${w.high}° L:${w.low}°", style = Ios.footnote.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
            } else {
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    w.hourly.forEach { (label, code, t) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = Ios.caption.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                            Icon(weatherIcon(code, true), null, tint = Color.White, modifier = Modifier.padding(vertical = 5.dp).size(18.dp))
                            Text("$t°", style = Ios.footnote.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
        if (size != WidgetSize.Small) {
            Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
                Icon(weatherIcon(w.code, w.isDay), null, tint = if (w.code <= 1 && w.isDay) Color(0xFFFFD60A) else Color.White, modifier = Modifier.size(18.dp))
                Text(WeatherRepo.describe(w.code), style = Ios.footnote.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                Text("H:${w.high}° L:${w.low}°", style = Ios.footnote.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Calendar

data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int)

suspend fun loadEvents(ctx: Context, days: Int = 2): List<CalEvent> = withContext(Dispatchers.IO) {
    if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
    val now = System.currentTimeMillis()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        android.content.ContentUris.appendId(it, now - 3600_000)
        android.content.ContentUris.appendId(it, now + days * 86_400_000L)
    }.build()
    val out = ArrayList<CalEvent>()
    runCatching {
        ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.VISIBLE),
            null, null, CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue
                val end = c.getLong(2)
                if (end < now) continue
                out += CalEvent(c.getString(0) ?: "(No title)", c.getLong(1), end, c.getInt(3) == 1, c.getInt(4))
            }
        }
    }
    out
}

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

@Composable
fun CalendarWidget(size: WidgetSize, onRequestPermission: (String) -> Unit) {
    val ctx = LocalContext.current
    val now = rememberNow(60_000)
    var events by remember { mutableStateOf<List<CalEvent>>(emptyList()) }
    val granted = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(now, granted) { events = loadEvents(ctx) }
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val dow = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)).uppercase()
    @Composable fun DateBlock() = Column {
        Text(dow, color = Ios.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("${cal.get(Calendar.DAY_OF_MONTH)}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Normal, lineHeight = 42.sp)
    }

    @Composable fun EventsList(max: Int) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!granted) {
            Text("Tap to show your events", style = Ios.footnote, modifier = Modifier.pressable({ onRequestPermission(Manifest.permission.READ_CALENDAR) }))
        } else if (events.isEmpty()) {
            Text("No more events today", style = Ios.footnote)
        } else events.take(max).forEach { e ->
            Row(Modifier.height(IntrinsicHeightFix)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Color(e.color or 0xFF000000.toInt()), RoundedCornerShape(2.dp)))
                Column(Modifier.padding(start = 6.dp)) {
                    Text(e.title, style = Ios.footnote.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (e.allDay) "All-day" else "${timeFmt.format(Date(e.begin))} – ${timeFmt.format(Date(e.end))}", style = Ios.caption, maxLines = 1)
                }
            }
        }
    }

    if (size == WidgetSize.Small) {
        Column(Modifier.fillMaxSize()) { DateBlock(); Spacer(Modifier.height(4.dp)); EventsList(1); if (events.size > 1) Text("+${events.size - 1} more", style = Ios.caption, modifier = Modifier.padding(top = 4.dp)) }
    } else {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(96.dp)) { DateBlock() }
            Box(Modifier.weight(1f).padding(top = 2.dp)) { EventsList(if (size == WidgetSize.Large) 8 else 3) }
        }
    }
}

private val IntrinsicHeightFix = 32.dp

// ------------------------------------------------------------------------------------------ Clock

@Composable
fun AnalogClock(tz: TimeZone, modifier: Modifier, now: Long) {
    val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val day = hour in 6..17
    val face = if (day) Color.White else Color(0xFF1C1C1E)
    val ink = if (day) Color.Black else Color.White
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(face, r, c)
        for (i in 0 until 12) {
            rotate(i * 30f, c) {
                drawLine(ink.copy(alpha = if (i % 3 == 0) 0.9f else 0.45f), Offset(c.x, c.y - r * 0.92f), Offset(c.x, c.y - r * (if (i % 3 == 0) 0.78f else 0.84f)), strokeWidth = r * 0.035f, cap = StrokeCap.Round)
            }
        }
        val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val h = (hour % 12) + m / 60f
        rotate(h * 30f, c) { drawLine(ink, c, Offset(c.x, c.y - r * 0.5f), strokeWidth = r * 0.07f, cap = StrokeCap.Round) }
        rotate(m * 6f, c) { drawLine(ink, c, Offset(c.x, c.y - r * 0.78f), strokeWidth = r * 0.05f, cap = StrokeCap.Round) }
        rotate(cal.get(Calendar.SECOND) * 6f, c) {
            drawLine(Ios.Orange, Offset(c.x, c.y + r * 0.15f), Offset(c.x, c.y - r * 0.85f), strokeWidth = r * 0.022f, cap = StrokeCap.Round)
        }
        drawCircle(Ios.Orange, r * 0.05f, c)
    }
}

@Composable
fun ClockWidget(size: WidgetSize, modifier: Modifier) {
    val now = rememberNow(1000)
    val bd = wallpaperBackdrop()
    if (size == WidgetSize.Small) {
        Box(modifier.glass(WidgetShape, bd, tint = Color.Black.copy(alpha = 0.25f)).padding(10.dp)) {
            AnalogClock(TimeZone.getDefault(), Modifier.fillMaxSize(), now)
        }
    } else {
        val zones = listOf("Local" to TimeZone.getDefault(), "London" to TimeZone.getTimeZone("Europe/London"),
            "New York" to TimeZone.getTimeZone("America/New_York"), "Tokyo" to TimeZone.getTimeZone("Asia/Tokyo"))
        Box(modifier.glass(WidgetShape, bd, tint = Color.Black.copy(alpha = 0.25f)).padding(12.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                zones.forEach { (name, tz) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnalogClock(tz, Modifier.size(64.dp), now)
                        Text(name, style = Ios.caption.copy(color = Color.White, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp))
                        val diff = (tz.getOffset(now) - TimeZone.getDefault().getOffset(now)) / 3_600_000
                        Text(if (name == "Local") "Today" else (if (diff >= 0) "+$diff" else "$diff") + "HRS", style = Ios.caption.copy(fontSize = 10.sp))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Battery

@Composable
fun rememberBattery(): Pair<Int, Boolean> {
    val ctx = LocalContext.current
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                level = if (l >= 0) l * 100 / s.coerceAtLeast(1) else -1
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { r.onReceive(ctx, it) }
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return level to charging
}

@Composable
fun BatteryRing(level: Int, charging: Boolean, modifier: Modifier) {
    val color = when {
        charging -> Ios.Green
        level in 0..20 -> Ios.Red
        else -> Ios.Green
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = size.minDimension * 0.11f
            drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension / 2 - sw / 2, style = Stroke(sw))
            drawArc(color, -90f, 360f * level.coerceAtLeast(0) / 100f, false,
                topLeft = Offset(sw / 2, sw / 2), size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Icon(if (charging) Icons.Rounded.Bolt else Icons.Rounded.PhoneAndroid, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.38f))
    }
}

@Composable
fun BatteryWidget(size: WidgetSize) {
    val (level, charging) = rememberBattery()
    if (size == WidgetSize.Small) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            BatteryRing(level, charging, Modifier.size(78.dp))
            Text("$level%", style = Ios.title1.copy(fontSize = 24.sp), modifier = Modifier.padding(top = 8.dp))
        }
    } else {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            BatteryRing(level, charging, Modifier.size(84.dp))
            Column(Modifier.padding(start = 18.dp)) {
                Text("$level%", style = Ios.largeTitle)
                Text(if (charging) "Charging" else "Battery", style = Ios.footnote)
                Text("This phone", style = Ios.caption)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Music

@Composable
fun MusicWidget(size: WidgetSize) {
    val media = GlassApp.instance.media
    val m = media.info
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(if (size == WidgetSize.Small) 58.dp else 64.dp).clip(Ios.rr(10f)).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                if (m?.art != null) Image(m.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
            }
            if (size != WidgetSize.Small) {
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(m?.title ?: "Not Playing", style = Ios.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m?.artist ?: "", style = Ios.footnote, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (size == WidgetSize.Small) {
            Text(m?.title ?: "Not Playing", style = Ios.headline.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m?.artist ?: "Open a music app", style = Ios.caption, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(28.dp).pressable({ media.previous() }, haptic = true))
            Icon(if (m?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp).pressable({ media.playPause() }, haptic = true))
            Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(28.dp).pressable({ media.next() }, haptic = true))
        }
    }
}

// ------------------------------------------------------------------------------------------ Suggestions

fun suggestedApps(count: Int): List<AppEntry> {
    val app = GlassApp.instance
    val apps = app.repo.apps.value
    val scored = apps.map { it to app.usage.score(it.key.raw) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    val fallback = listOf("com.whatsapp", "com.instagram.android", "com.google.android.youtube", "com.android.chrome",
        "com.google.android.gm", "com.google.android.apps.maps", "com.miui.gallery", "com.android.settings", "com.google.android.apps.nbu.paisa.user")
        .mapNotNull { p -> apps.firstOrNull { it.key.pkg == p } }
    return (scored + fallback + apps).distinctBy { it.key }.take(count)
}

@Composable
fun SuggestionsWidget(size: WidgetSize) {
    val app = GlassApp.instance
    @Suppress("UNUSED_VARIABLE") val v = app.usage.version
    val n = when (size) { WidgetSize.Small -> 4; WidgetSize.Medium -> 8; WidgetSize.Large -> 16 }
    val list = remember(app.usage.version, app.repo.apps.value) { suggestedApps(n) }
    val cols = if (size == WidgetSize.Small) 2 else 4
    val view = androidx.compose.ui.platform.LocalView.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        list.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { e ->
                    AppIconImage(e, if (size == WidgetSize.Small) 50.dp else 54.dp, Modifier.pressable({ app.repo.launch(e, view, null) }))
                }
            }
        }
    }
}

@Composable
fun WidgetLabelArrow() = Icon(Icons.AutoMirrored.Rounded.ArrowRight, null, tint = Color.White)
