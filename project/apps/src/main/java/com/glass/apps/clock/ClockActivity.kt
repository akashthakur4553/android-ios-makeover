package com.glass.apps.clock

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.apps.common.BarButton
import com.glass.apps.common.Divider
import com.glass.apps.common.IosTheme
import com.glass.apps.common.LargeTitle
import com.glass.apps.common.Screen
import com.glass.apps.common.SearchField
import com.glass.apps.common.Section
import com.glass.apps.common.CellRow
import com.glass.apps.common.T
import com.glass.apps.common.TabBar
import com.glass.apps.common.Toggle
import com.glass.apps.common.Txt
import com.glass.apps.common.ios
import com.glass.apps.common.rr
import com.glass.apps.common.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val tnum = TextStyle(fontFeatureSettings = "tnum")

class ClockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { IosTheme { ClockApp() } }
    }
}

@Composable
fun rememberTick(ms: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ms) { while (true) { delay(ms - System.currentTimeMillis() % ms); now = System.currentTimeMillis() } }
    return now
}

@Composable
fun ClockApp() {
    var tab by remember { mutableIntStateOf(1) }
    Screen {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) { 0 -> WorldClock(); 1 -> Alarms(); 2 -> Stopwatch(); else -> Timers() }
            }
            TabBar(listOf("World Clock" to Icons.Rounded.Language, "Alarms" to Icons.Rounded.Alarm, "Stopwatch" to Icons.Rounded.Timer, "Timers" to Icons.Rounded.HourglassBottom), tab) { tab = it }
        }
    }
}

// ------------------------------------------------------------------------------------------ World clock

private fun cityName(id: String) = id.substringAfterLast('/').replace('_', ' ')

@Composable
fun WorldClock() {
    val ctx = LocalContext.current
    val sp = ctx.getSharedPreferences("clock", Context.MODE_PRIVATE)
    val cities = remember {
        mutableStateListOf<String>().apply {
            val j = JSONArray(sp.getString("cities", JSONArray(listOf("Europe/London", "America/New_York", "Asia/Dubai", "Asia/Tokyo")).toString()))
            for (i in 0 until j.length()) add(j.getString(i))
        }
    }
    fun save() = sp.edit().putString("cities", JSONArray(cities.toList()).toString()).apply()
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val now = rememberTick(1000)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                LargeTitle("World Clock", trailing = {
                    BarButton(text = if (editing) "Done" else "Edit", bold = editing) { editing = !editing }
                    BarButton(Icons.Rounded.Add) { adding = true }
                })
            }
            itemsIndexed(cities, key = { _, c -> c }) { i, id ->
                val tz = TimeZone.getTimeZone(id)
                val diff = (tz.getOffset(now) - TimeZone.getDefault().getOffset(now)) / 60_000
                val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
                val local = Calendar.getInstance()
                val day = when (cal.get(Calendar.DAY_OF_YEAR) - local.get(Calendar.DAY_OF_YEAR)) { 0 -> "Today"; 1, -364, -365 -> "Tomorrow"; else -> "Yesterday" }
                val h = diff / 60; val m = Math.abs(diff % 60)
                val off = (if (diff >= 0) "+" else "-") + Math.abs(h) + (if (m != 0) ":%02d".format(m) else "") + "HRS"
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (editing) Icon(Icons.Rounded.RemoveCircle, null, tint = ios.red, modifier = Modifier.padding(end = 12.dp).size(24.dp).tap { cities.remove(id); save() })
                        Column(Modifier.weight(1f)) {
                            Txt("$day, $off", T.footnote, ios.label2)
                            Txt(cityName(id), T.title2.copy(fontWeight = FontWeight.Normal))
                        }
                        val f = SimpleDateFormat("h:mm", Locale.getDefault()).apply { timeZone = tz }
                        val ap = SimpleDateFormat("a", Locale.getDefault()).apply { timeZone = tz }
                        Txt(f.format(Date(now)), TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp).merge(tnum))
                        Txt(ap.format(Date(now)), T.title3.copy(fontWeight = FontWeight.Light), modifier = Modifier.padding(start = 2.dp, top = 14.dp))
                    }
                    if (i < cities.lastIndex) Divider()
                }
            }
        }
        AnimatedVisibility(adding, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            CityPicker(onPick = { if (it !in cities) { cities.add(it); save() }; adding = false }, onCancel = { adding = false })
        }
    }
}

@Composable
private fun CityPicker(onPick: (String) -> Unit, onCancel: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { TimeZone.getAvailableIDs().filter { it.contains('/') && !it.startsWith("Etc") && !it.startsWith("SystemV") }.sortedBy { cityName(it) } }
    val list = remember(q) { all.filter { q.isBlank() || cityName(it).contains(q, true) || it.contains(q, true) } }
    Column(Modifier.fillMaxSize().background(ios.groupedBg).statusBarsPadding()) {
        Txt("Choose a City", T.footnote, ios.label2, Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(q, { q = it }, Modifier.weight(1f))
            Txt("Cancel", T.body, ios.blue, Modifier.padding(start = 12.dp).tap(onClick = onCancel))
        }
        LazyColumn {
            items(list) { id -> Column(Modifier.tap(1f) { onPick(id) }) { Txt("${cityName(id)}, ${id.substringBefore('/')}", T.body, modifier = Modifier.padding(16.dp, 12.dp)); Divider() } }
        }
    }
}

// ------------------------------------------------------------------------------------------ Alarms

fun repeatText(days: Set<Int>): String = when {
    days.isEmpty() -> ""
    days.size == 7 -> "every day"
    days == setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "weekends"
    days == setOf(2, 3, 4, 5, 6) -> "weekdays"
    else -> days.sortedBy { (it + 5) % 7 }.joinToString(" ") { arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[it] }
}

@Composable
fun Alarms() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val alarms = remember(version) { AlarmStore.all(ctx) }
    var editing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Alarm?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                LargeTitle("Alarms", trailing = {
                    BarButton(text = if (editing) "Done" else "Edit", bold = editing) { editing = !editing }
                    BarButton(Icons.Rounded.Add) { val c = Calendar.getInstance(); sheet = Alarm(-1, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
                })
            }
            if (alarms.isEmpty()) item { Txt("No Alarms", T.title3, ios.label2, Modifier.fillMaxWidth().padding(top = 120.dp), align = androidx.compose.ui.text.style.TextAlign.Center) }
            items(alarms, key = { it.id }) { a ->
                val col = if (a.enabled) ios.label else ios.label2
                Column(Modifier.tap(1f) { sheet = a }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (editing) Icon(Icons.Rounded.RemoveCircle, null, tint = ios.red, modifier = Modifier.padding(end = 12.dp).size(24.dp).tap { AlarmStore.delete(ctx, a.id); version++ })
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                val h12 = if (a.hour % 12 == 0) 12 else a.hour % 12
                                Txt("%d:%02d".format(h12, a.minute), TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp).merge(tnum), col)
                                Txt(if (a.hour < 12) "AM" else "PM", T.title2.copy(fontWeight = FontWeight.Light), col, Modifier.padding(start = 3.dp, bottom = 8.dp))
                            }
                            Txt(a.label + (repeatText(a.days).takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""), T.subhead, col)
                        }
                        if (!editing) Toggle(a.enabled) { AlarmStore.upsert(ctx, a.copy(enabled = it)); version++ }
                    }
                    Divider()
                }
            }
        }
        AnimatedVisibility(sheet != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            sheet?.let { s -> AlarmEditor(s, onDone = { sheet = null; version++ }) }
        }
    }
}

@Composable
private fun AlarmEditor(initial: Alarm, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var h12 by remember { mutableIntStateOf(if (initial.hour % 12 == 0) 12 else initial.hour % 12) }
    var min by remember { mutableIntStateOf(initial.minute) }
    var pm by remember { mutableStateOf(initial.hour >= 12) }
    var label by remember { mutableStateOf(initial.label) }
    var days by remember { mutableStateOf(initial.days) }
    var snooze by remember { mutableStateOf(initial.snooze) }
    var showDays by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(ios.groupedBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Cancel", T.body, ios.orange, Modifier.tap(onClick = onDone))
            Txt(if (initial.id < 0) "Add Alarm" else "Edit Alarm", T.headline, modifier = Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
            Txt("Save", T.headline, ios.orange, Modifier.tap {
                val hour = (h12 % 12) + if (pm) 12 else 0
                AlarmStore.upsert(ctx, initial.copy(id = if (initial.id < 0) AlarmStore.newId(ctx) else initial.id, hour = hour, minute = min,
                    label = label.ifBlank { "Alarm" }, days = days, snooze = snooze, enabled = true))
                onDone()
            })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
            Wheel((1..12).map { "$it" }, h12 - 1, Modifier.width(70.dp), loop = true) { h12 = it + 1 }
            Wheel((0..59).map { "%02d".format(it) }, min, Modifier.width(70.dp), loop = true) { min = it }
            Wheel(listOf("AM", "PM"), if (pm) 1 else 0, Modifier.width(70.dp)) { pm = it == 1 }
        }
        Section {
            CellRow("Repeat", repeatText(days).ifEmpty { "Never" }) { showDays = !showDays }
            if (showDays) {
                listOf(Calendar.SUNDAY to "Every Sunday", 2 to "Every Monday", 3 to "Every Tuesday", 4 to "Every Wednesday", 5 to "Every Thursday", 6 to "Every Friday", 7 to "Every Saturday").forEach { (d, n) ->
                    Divider(); CellRow(n, if (d in days) "✓" else "", chevron = false) { days = if (d in days) days - d else days + d }
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Label", T.body, modifier = Modifier.weight(1f))
                BasicTextField(label, { label = it }, singleLine = true, textStyle = T.body.copy(color = ios.label2, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    modifier = Modifier.width(180.dp))
            }
            Divider()
            CellRow("Sound", "Default", chevron = false)
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Snooze", T.body, modifier = Modifier.weight(1f)); Toggle(snooze) { snooze = it }
            }
        }
        if (initial.id >= 0) Section(modifier = Modifier.padding(top = 26.dp)) {
            CellRow("Delete Alarm", color = ios.red, chevron = false) { AlarmStore.delete(ctx, initial.id); onDone() }
        }
    }
}

/** iOS wheel picker: snapping list with a highlight band. */
@Composable
fun Wheel(values: List<String>, selected: Int, modifier: Modifier = Modifier, loop: Boolean = false, rowH: Dp = 36.dp, onSelect: (Int) -> Unit) {
    val n = values.size
    val total = if (loop) n * 400 else n
    val startIndex = if (loop) n * 200 + selected else selected
    val state = rememberLazyListState(startIndex)
    val fling = rememberSnapFlingBehavior(state)
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > 0) 1 else 0 }
            .collect { if (!state.isScrollInProgress) onSelect(it % n) }
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) onSelect((state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowH.value) 1 else 0) % n)
    }
    Box(modifier.height(rowH * 5), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(rowH).clip(rr(8f)).background(ios.fill))
        LazyColumn(state = state, flingBehavior = fling, contentPadding = PaddingValues(vertical = rowH * 2), modifier = Modifier.fillMaxSize()) {
            items(total) { i ->
                val dist = Math.abs(i - (state.firstVisibleItemIndex))
                Box(Modifier.fillMaxWidth().height(rowH), contentAlignment = Alignment.Center) {
                    Txt(values[i % n], T.title3.copy(fontWeight = FontWeight.Normal).merge(tnum), if (dist == 0) ios.label else ios.label3)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Stopwatch

private fun fmtWatch(ms: Long) = "%02d:%02d,%02d".format(ms / 60000, (ms / 1000) % 60, (ms / 10) % 100)

@Composable
fun Stopwatch() {
    val ctx = LocalContext.current
    val sp = ctx.getSharedPreferences("stopwatch", Context.MODE_PRIVATE)
    var startedAt by remember { mutableLongStateOf(sp.getLong("start", 0)) }
    var acc by remember { mutableLongStateOf(sp.getLong("acc", 0)) }
    val laps = remember { mutableStateListOf<Long>().apply { sp.getString("laps", "")!!.split(',').mapNotNull { it.toLongOrNull() }.let { addAll(it) } } }
    fun persist() = sp.edit().putLong("start", startedAt).putLong("acc", acc).putString("laps", laps.joinToString(",")).apply()
    val running = startedAt > 0
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(16) } }
    val elapsed = acc + if (running) now - startedAt else 0
    val lapStart = laps.sum()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Spacer(Modifier.height(70.dp))
        Txt(fmtWatch(elapsed), TextStyle(fontSize = 84.sp, fontWeight = FontWeight.Thin, letterSpacing = (-2).sp).merge(tnum),
            modifier = Modifier.fillMaxWidth(), align = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(50.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(if (running || elapsed == 0L) "Lap" else "Reset", ios.fill, ios.label, enabled = running || elapsed > 0) {
                if (running) laps.add(0, elapsed - lapStart) else { acc = 0; laps.clear() }
                persist()
            }
            RoundButton(if (running) "Stop" else "Start", if (running) ios.red.copy(alpha = 0.25f) else ios.green.copy(alpha = 0.25f), if (running) ios.red else ios.green) {
                if (running) { acc += System.currentTimeMillis() - startedAt; startedAt = 0 } else startedAt = System.currentTimeMillis()
                persist()
            }
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            val shown = (if (running || elapsed > 0) listOf(elapsed - lapStart) else emptyList()) + laps
            val best = laps.minOrNull(); val worst = laps.maxOrNull()
            itemsIndexed(shown) { i, l ->
                val num = shown.size - i
                val c = when { laps.size < 2 || i == 0 -> ios.label; l == best -> ios.green; l == worst -> ios.red; else -> ios.label }
                Divider(0.dp)
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
                    Txt("Lap $num", T.body, c, Modifier.weight(1f)); Txt(fmtWatch(l), T.body.merge(tnum), c)
                }
            }
        }
    }
}

@Composable
fun RoundButton(text: String, bg: Color, fg: Color, size: Dp = 84.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).border(2.dp, ios.bg, CircleShape).padding(3.dp).border(1.5.dp, bg, CircleShape)
        .then(if (enabled) Modifier.tap(0.94f, onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        Txt(text, T.callout.copy(fontWeight = FontWeight.Medium), if (enabled) fg else fg.copy(alpha = 0.4f))
    }
}

// ------------------------------------------------------------------------------------------ Timers

@Composable
fun Timers() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val end = remember(version) { AlarmStore.timerEnd(ctx) }
    val paused = remember(version) { AlarmStore.timerPausedLeft(ctx) }
    val total = remember(version) { AlarmStore.timerTotal(ctx) }
    var hh by remember { mutableIntStateOf(0) }
    var mm by remember { mutableIntStateOf(15) }
    var ss by remember { mutableIntStateOf(0) }
    val now = rememberTick(100)
    val active = end > now || paused > 0
    LaunchedEffect(end, now) { if (end in 1..now) version++ }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LargeTitle("Timers")
        if (!active) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel((0..23).map { "$it" }, hh, Modifier.width(60.dp)) { hh = it }; Txt("hours", T.callout.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(end = 10.dp))
                Wheel((0..59).map { "$it" }, mm, Modifier.width(60.dp), loop = true) { mm = it }; Txt("min", T.callout.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(end = 10.dp))
                Wheel((0..59).map { "$it" }, ss, Modifier.width(60.dp), loop = true) { ss = it }; Txt("sec", T.callout.copy(fontWeight = FontWeight.SemiBold))
            }
        } else {
            val left = if (paused > 0) paused else (end - now).coerceAtLeast(0)
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                val frac = if (total > 0) left / total.toFloat() else 0f
                val track = ios.fill; val prog = ios.orange
                Canvas(Modifier.size(290.dp)) {
                    drawCircle(track, style = Stroke(9.dp.toPx()))
                    drawArc(prog, -90f, 360f * frac, false, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset(0f, 0f), size = size)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val s = (left + 999) / 1000
                    Txt(if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60),
                        TextStyle(fontSize = 66.sp, fontWeight = FontWeight.Thin).merge(tnum))
                    if (paused == 0L) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.NotificationsActive, null, tint = ios.label2, modifier = Modifier.size(16.dp))
                        Txt(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(end)), T.headline, ios.label2, Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton("Cancel", ios.fill, ios.label, enabled = active) { AlarmStore.cancelTimer(ctx); version++ }
            when {
                !active -> RoundButton("Start", ios.green.copy(alpha = 0.25f), ios.green, enabled = hh + mm + ss > 0) {
                    val ms = ((hh * 3600L) + mm * 60L + ss) * 1000
                    AlarmStore.startTimer(ctx, System.currentTimeMillis() + ms, ms); version++
                }
                paused > 0 -> RoundButton("Resume", ios.green.copy(alpha = 0.25f), ios.green) { AlarmStore.startTimer(ctx, System.currentTimeMillis() + paused); version++ }
                else -> RoundButton("Pause", ios.orange.copy(alpha = 0.25f), ios.orange) { AlarmStore.pauseTimer(ctx); version++ }
            }
        }
        Section {
            CellRow("When Timer Ends", "Radar", chevron = false)
        }
    }
}

// ------------------------------------------------------------------------------------------ Ringing

class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val id = intent.getIntExtra("id", 0)
        val label = intent.getStringExtra("label") ?: "Alarm"
        val timer = intent.getBooleanExtra("timer", false)
        val snoozeOk = !timer && (AlarmStore.all(this).firstOrNull { it.id == id % 500_000 }?.snooze ?: true)
        setContent {
            IosTheme(forceDark = true) {
                val now = rememberTick(1000)
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(60.dp))
                        Txt(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)), T.title3, Color.White.copy(alpha = 0.8f))
                        Txt(SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now)), TextStyle(fontSize = 100.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3).sp), Color.White)
                        Txt(if (timer) "Timer" else label, T.title2, Color.White, Modifier.padding(top = 12.dp))
                        Spacer(Modifier.weight(1f))
                        if (snoozeOk) Box(Modifier.fillMaxWidth().height(64.dp).clip(CircleShape).background(ios.orange).tap { act(AlarmReceiver.SNOOZE, id, label) },
                            contentAlignment = Alignment.Center) { Txt("Snooze", T.title3, Color.Black) }
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.fillMaxWidth().height(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).tap { act(AlarmReceiver.STOP, id, label) },
                            contentAlignment = Alignment.Center) { Txt("Stop", T.title3, Color.White) }
                        Spacer(Modifier.height(30.dp))
                    }
                }
            }
        }
    }

    private fun act(action: String, id: Int, label: String) {
        sendBroadcast(android.content.Intent(this, AlarmReceiver::class.java).setAction(action).putExtra("id", id).putExtra("label", label))
        getSystemService(NotificationManager::class.java).cancel(AlarmReceiver.NOTIF_BASE + id)
        finish()
    }

    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

    companion object { var current: Activity? = null }
}
