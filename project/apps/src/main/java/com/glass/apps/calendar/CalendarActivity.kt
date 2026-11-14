package com.glass.apps.calendar

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.apps.common.CellRow
import com.glass.apps.common.Divider
import com.glass.apps.common.IosTheme
import com.glass.apps.common.NeedsPermissions
import com.glass.apps.common.Screen
import com.glass.apps.common.Section
import com.glass.apps.common.T
import com.glass.apps.common.Toggle
import com.glass.apps.common.Txt
import com.glass.apps.common.ios
import com.glass.apps.common.rr
import com.glass.apps.common.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Cal(val id: Long, val name: String, val color: Int, val writable: Boolean)
data class Ev(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val location: String, val calId: Long)

object CalRepo {
    fun calendars(ctx: Context): List<Cal> = runCatching {
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL), "${CalendarContract.Calendars.VISIBLE}=1", null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Cal(c.getLong(0), c.getString(1) ?: "Calendar", c.getInt(2), c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)) }
        }
    }.getOrNull() ?: emptyList()

    suspend fun events(ctx: Context, from: Long, to: Long): List<Ev> = withContext(Dispatchers.IO) {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.VISIBLE),
                null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) if (c.getInt(8) == 1) {
                        var b = c.getLong(2); var e = c.getLong(3)
                        val all = c.getInt(4) == 1
                        if (all) { // all-day events are stored in UTC midnight
                            val off = TimeZone.getDefault().getOffset(b); b -= off; e -= off
                        }
                        add(Ev(c.getLong(0), c.getString(1) ?: "(No title)", b, e, all, c.getInt(5), c.getString(6) ?: "", c.getLong(7)))
                    }
                }
            }
        }.getOrNull() ?: emptyList()
    }

    fun insert(ctx: Context, calId: Long, title: String, loc: String, start: Long, end: Long, allDay: Boolean) {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, title); put(CalendarContract.Events.EVENT_LOCATION, loc)
            if (allDay) {
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val l = Calendar.getInstance().apply { timeInMillis = start }
                utc.clear(); utc.set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
                put(CalendarContract.Events.DTSTART, utc.timeInMillis); put(CalendarContract.Events.DTEND, utc.timeInMillis + 86_400_000)
                put(CalendarContract.Events.ALL_DAY, 1); put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) }
    }

    fun delete(ctx: Context, id: Long) {
        runCatching { ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) }
    }
}

private const val BASE = 1200 // pager index of the current month

private fun monthStart(offset: Int): Calendar = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.MONTH, offset)
}

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }; val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun dayStart(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

class CalendarActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IosTheme {
                NeedsPermissions(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), "Allow Calendar access",
                    "Calendar shows and adds events from the accounts on this phone (Google, Xiaomi, etc.).") { CalendarApp() }
            }
        }
    }
}

@Composable
fun CalendarApp() {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = BASE) { BASE * 2 }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableLongStateOf(dayStart(System.currentTimeMillis())) }
    var version by remember { mutableIntStateOf(0) }
    var events by remember { mutableStateOf<List<Ev>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Ev?>(null) }
    val month = monthStart(pager.currentPage - BASE)
    LaunchedEffect(pager.currentPage, version) {
        val from = monthStart(pager.currentPage - BASE - 1).timeInMillis
        val to = monthStart(pager.currentPage - BASE + 2).timeInMillis
        events = CalRepo.events(ctx, from, to)
    }
    BackHandler(adding || detail != null) { adding = false; detail = null }
    Screen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = ios.red, modifier = Modifier.size(20.dp))
                Txt("${month.get(Calendar.YEAR)}", T.body, ios.red)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.Add, null, tint = ios.red, modifier = Modifier.size(28.dp).tap { adding = true })
            }
            Txt(SimpleDateFormat("MMMM", Locale.getDefault()).format(month.time), T.largeTitle, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { i, d ->
                    Txt(d, T.caption.copy(fontWeight = FontWeight.SemiBold), if (i == 0 || i == 6) ios.label2 else ios.label, Modifier.weight(1f), align = TextAlign.Center)
                }
            }
            Divider(0.dp)
            HorizontalPager(pager, Modifier.fillMaxWidth().height(300.dp)) { page ->
                MonthGrid(monthStart(page - BASE), selected, events) { selected = it }
            }
            Divider(0.dp)
            val dayEvents = events.filter { it.begin < selected + 86_400_000 && it.end > selected }
            Txt(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(selected)), T.subhead.copy(fontWeight = FontWeight.SemiBold), ios.label2,
                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (dayEvents.isEmpty()) item { Txt("No Events", T.title3, ios.label3, Modifier.fillMaxWidth().padding(top = 30.dp), align = TextAlign.Center) }
                items(dayEvents, key = { "${it.id}-${it.begin}" }) { e ->
                    Row(Modifier.fillMaxWidth().tap(1f) { detail = e }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.width(70.dp)) {
                            if (e.allDay) Txt("all-day", T.footnote, ios.label2)
                            else { Txt(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(e.begin)), T.footnote); Txt(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(e.end)), T.footnote, ios.label2) }
                        }
                        Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(Color(e.color or 0xFF000000.toInt())))
                        Column(Modifier.padding(start = 10.dp)) {
                            Txt(e.title, T.headline.copy(fontSize = 16.sp), maxLines = 1)
                            if (e.location.isNotBlank()) Txt(e.location, T.footnote, ios.label2, maxLines = 1)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(ios.bar).navigationBarsPadding().height(48.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Today", T.body, ios.red, Modifier.tap { selected = dayStart(System.currentTimeMillis()); scope.launch { pager.animateScrollToPage(BASE) } })
                Spacer(Modifier.weight(1f))
                Txt("Calendars", T.body, ios.red)
                Spacer(Modifier.weight(1f))
                Txt("Inbox", T.body, ios.label3)
            }
        }
        AnimatedVisibility(adding, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            NewEvent(selected, onDone = { adding = false; version++ })
        }
        AnimatedVisibility(detail != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            detail?.let { e -> EventDetail(e, onClose = { detail = null }, onDeleted = { detail = null; version++ }) }
        }
    }
}

@Composable
private fun MonthGrid(month: Calendar, selected: Long, events: List<Ev>, onPick: (Long) -> Unit) {
    val first = month.clone() as Calendar
    val lead = first.get(Calendar.DAY_OF_WEEK) - 1
    val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val today = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        for (r in 0 until 6) Row(Modifier.weight(1f)) {
            for (c in 0 until 7) {
                val n = r * 7 + c - lead + 1
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    if (n in 1..days) {
                        val t = (first.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, n) }.timeInMillis
                        val isToday = sameDay(t, today)
                        val isSel = sameDay(t, selected)
                        val has = events.any { it.begin < t + 86_400_000 && it.end > t }
                        Column(Modifier.tap(0.9f, dim = false) { onPick(t) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(
                                when { isSel && isToday -> ios.red; isSel -> ios.label; else -> Color.Transparent }), contentAlignment = Alignment.Center) {
                                Txt("$n", T.title3.copy(fontWeight = if (isToday || isSel) FontWeight.SemiBold else FontWeight.Normal),
                                    when { isSel -> ios.bg; isToday -> ios.red; c == 0 || c == 6 -> ios.label2; else -> ios.label })
                            }
                            Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(if (has) ios.gray else Color.Transparent))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewEvent(day: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cals = remember { CalRepo.calendars(ctx).filter { it.writable } }
    var calIdx by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var loc by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    val now = Calendar.getInstance()
    var start by remember { mutableLongStateOf(Calendar.getInstance().apply { timeInMillis = day; set(Calendar.HOUR_OF_DAY, (now.get(Calendar.HOUR_OF_DAY) + 1) % 24); set(Calendar.MINUTE, 0) }.timeInMillis) }
    var end by remember { mutableLongStateOf(start + 3_600_000) }
    fun pick(t: Long, set: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        DatePickerDialog(ctx, { _, y, m, d ->
            c.set(y, m, d)
            if (allDay) set(c.timeInMillis) else TimePickerDialog(ctx, { _, h, mi -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); set(c.timeInMillis) },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    val f = SimpleDateFormat(if (allDay) "d MMM yyyy" else "d MMM yyyy   h:mm a", Locale.getDefault())
    Column(Modifier.fillMaxSize().background(ios.groupedBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Cancel", T.body, ios.red, Modifier.tap(onClick = onDone))
            Txt("New Event", T.headline, modifier = Modifier.weight(1f), align = TextAlign.Center)
            Txt("Add", T.headline, if (title.isNotBlank() && cals.isNotEmpty()) ios.red else ios.label3, Modifier.tap {
                if (title.isNotBlank() && cals.isNotEmpty()) { CalRepo.insert(ctx, cals[calIdx].id, title, loc, start, maxOf(end, start + 60_000), allDay); onDone() }
            })
        }
        Section {
            Field("Title", title) { title = it }; Divider(); Field("Location", loc) { loc = it }
        }
        Section(modifier = Modifier.padding(top = 26.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("All-day", T.body, modifier = Modifier.weight(1f)); Toggle(allDay) { allDay = it }
            }
            Divider(); CellRow("Starts", f.format(Date(start)), chevron = false) { pick(start) { start = it; if (end < it) end = it + 3_600_000 } }
            if (!allDay) { Divider(); CellRow("Ends", f.format(Date(end)), chevron = false) { pick(end) { end = it } } }
        }
        Section(modifier = Modifier.padding(top = 26.dp), footer = if (cals.isEmpty()) "No writable calendar on this phone. Add a Google account in Settings." else null) {
            CellRow("Calendar", cals.getOrNull(calIdx)?.name ?: "None", chevron = false) { if (cals.isNotEmpty()) calIdx = (calIdx + 1) % cals.size }
        }
    }
}

@Composable
private fun Field(hint: String, v: String, on: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (v.isEmpty()) Txt(hint, T.body, ios.label3)
        BasicTextField(v, on, singleLine = true, textStyle = T.body.copy(color = ios.label), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun EventDetail(e: Ev, onClose: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val cal = remember { CalRepo.calendars(ctx).firstOrNull { it.id == e.calId } }
    Column(Modifier.fillMaxSize().background(ios.groupedBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            com.glass.apps.common.BackButton("Back") { onClose() }
        }
        Column(Modifier.padding(16.dp)) {
            Txt(e.title, T.title2)
            if (e.location.isNotBlank()) Txt(e.location, T.body, ios.label2, Modifier.padding(top = 4.dp))
            Txt(SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault()).format(Date(e.begin)), T.body, modifier = Modifier.padding(top = 14.dp))
            Txt(if (e.allDay) "all-day" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(e.begin)) + " to " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(e.end)), T.body, ios.label2)
        }
        Section { CellRow("Calendar", cal?.name ?: "", chevron = false, leading = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(e.color or 0xFF000000.toInt()))) }) }
        if (cal?.writable == true) Section(modifier = Modifier.padding(top = 30.dp)) {
            CellRow("Delete Event", color = ios.red, chevron = false) { CalRepo.delete(ctx, e.id); onDeleted() }
        }
    }
}
