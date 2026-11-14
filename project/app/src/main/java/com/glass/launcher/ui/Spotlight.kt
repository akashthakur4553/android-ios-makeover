package com.glass.launcher.ui

import android.Manifest
import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.GlassApp
import com.glass.launcher.data.AppEntry
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class ContactHit(val id: Long, val lookup: String, val name: String, val number: String?)

private val settingsTargets = listOf(
    "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
    "Mobile Network" to Settings.ACTION_DATA_ROAMING_SETTINGS, "Display & Brightness" to Settings.ACTION_DISPLAY_SETTINGS,
    "Sounds & Haptics" to Settings.ACTION_SOUND_SETTINGS, "Notifications" to "android.settings.NOTIFICATION_SETTINGS",
    "Battery" to Intent.ACTION_POWER_USAGE_SUMMARY, "Storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
    "Apps" to Settings.ACTION_APPLICATION_SETTINGS, "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
    "Privacy & Security" to Settings.ACTION_SECURITY_SETTINGS, "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    "Date & Time" to Settings.ACTION_DATE_SETTINGS, "Language" to Settings.ACTION_LOCALE_SETTINGS,
    "About Phone" to Settings.ACTION_DEVICE_INFO_SETTINGS, "Developer Options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
    "Wallpaper" to Intent.ACTION_SET_WALLPAPER, "Personal Hotspot" to Settings.ACTION_WIRELESS_SETTINGS,
    "Default Apps" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "Passwords" to "android.settings.SECURITY_SETTINGS",
    "VPN" to Settings.ACTION_VPN_SETTINGS, "NFC" to Settings.ACTION_NFC_SETTINGS, "Airplane Mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
)

/** Tiny calculator for Spotlight: + - * / ^ % and parentheses. */
object Calc {
    fun eval(s: String): Double? {
        val t = s.replace(" ", "").replace("×", "*").replace("÷", "/").replace("x", "*")
        if (t.isEmpty() || t.none { it in "+-*/^%" } || t.any { !(it.isDigit() || it in "+-*/^%().") }) return null
        return runCatching {
            var i = 0
            fun peek() = t.getOrNull(i)
            lateinit var expr: () -> Double
            fun factor(): Double {
                val c = peek()
                if (c == '-') { i++; return -factor() }
                if (c == '+') { i++; return factor() }
                if (c == '(') { i++; val v = expr(); if (peek() == ')') i++; return v }
                val st = i
                while (peek()?.let { it.isDigit() || it == '.' } == true) i++
                var v = t.substring(st, i).toDouble()
                if (peek() == '%') { i++; v /= 100 }
                return v
            }
            fun power(): Double { val b = factor(); return if (peek() == '^') { i++; Math.pow(b, power()) } else b }
            fun term(): Double {
                var v = power()
                while (peek() == '*' || peek() == '/') { val op = t[i++]; val r = power(); v = if (op == '*') v * r else v / r }
                return v
            }
            expr = {
                var v = term()
                while (peek() == '+' || peek() == '-') { val op = t[i++]; val r = term(); v = if (op == '+') v + r else v - r }
                v
            }
            val v = expr()
            if (i != t.length || v.isNaN() || v.isInfinite()) null else v
        }.getOrNull()
    }

    fun fmt(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else "%.8f".format(v).trimEnd('0').trimEnd('.')
}

suspend fun searchContacts(ctx: Context, q: String): List<ContactHit> = withContext(Dispatchers.IO) {
    if (q.isBlank() || ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
    val out = LinkedHashMap<Long, ContactHit>()
    runCatching {
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q)),
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c -> while (c.moveToNext() && out.size < 6) { val id = c.getLong(0); if (id !in out) out[id] = ContactHit(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3)) } }
    }
    out.values.toList()
}

@Composable
fun Spotlight(progress: Float, open: Boolean, backdrop: Backdrop?, onClose: () -> Unit, onRequestPermission: (String) -> Unit) {
    if (progress <= 0.001f) return
    val ctx = LocalContext.current
    val view = LocalView.current
    val app = GlassApp.instance
    val apps by app.repo.apps.collectAsState()
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val fm = LocalFocusManager.current
    var contacts by remember { mutableStateOf<List<ContactHit>>(emptyList()) }

    LaunchedEffect(open) {
        if (open) { delay(60); runCatching { focus.requestFocus() }; kb?.show() }
        else { kb?.hide(); fm.clearFocus(); q = "" }
    }
    LaunchedEffect(q) { delay(120); contacts = searchContacts(ctx, q.trim()) }

    val query = q.trim()
    val matches: List<AppEntry> = remember(query, apps) {
        if (query.isEmpty()) emptyList() else {
            val ql = query.lowercase()
            apps.mapNotNull { e ->
                val l = e.label.lowercase()
                val score = when {
                    l.startsWith(ql) -> 3
                    l.split(' ', '-', '.').any { it.startsWith(ql) } -> 2
                    l.contains(ql) -> 1
                    else -> 0
                }
                if (score > 0) e to (score * 1000 + app.usage.score(e.key.raw)) else null
            }.sortedByDescending { it.second }.map { it.first }
        }
    }
    val calc = remember(query) { Calc.eval(query) }
    val settingHits = remember(query) { if (query.length < 2) emptyList() else settingsTargets.filter { it.first.contains(query, true) }.take(4) }
    fun launch(e: AppEntry) { app.repo.launch(e, view, null); onClose() }
    fun web(engine: String = "") {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!com.glass.launcher.util.Sys.open(ctx, i)) com.glass.launcher.util.Sys.open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        onClose()
    }

    val p = progress.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f * p))
            .graphicsLayer { alpha = (p * 1.6f).coerceAtMost(1f) }
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            // Search field
            Row(
                Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .graphicsLayer { translationY = (1f - p) * -40.dp.toPx() }
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).height(44.dp).glass(Ios.capsule, backdrop, tint = Color.White.copy(alpha = 0.14f), refraction = 0.5f)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    Box(Modifier.weight(1f).padding(start = 8.dp)) {
                        if (q.isEmpty()) Text("Search", style = Ios.body.copy(fontSize = 17.sp, color = Color.White.copy(alpha = 0.55f)))
                        BasicTextField(
                            value = q, onValueChange = { q = it }, singleLine = true,
                            textStyle = Ios.body.copy(fontSize = 17.sp),
                            cursorBrush = SolidColor(Ios.Blue),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { matches.firstOrNull()?.let { launch(it) } ?: if (query.isNotEmpty()) web() else Unit }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (q.isNotEmpty()) Icon(Icons.Rounded.Cancel, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp).pressable({ q = "" }))
                }
                Text("Cancel", style = Ios.body.copy(fontSize = 17.sp), modifier = Modifier.padding(start = 12.dp).pressable(onClose))
            }

            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (query.isEmpty()) {
                    item { SectionTitle("Siri Suggestions") }
                    item {
                        AppGridCard(suggestedApps(8), backdrop) { launch(it) }
                    }
                    if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) item {
                        Text("Allow contacts to find people in Search", style = Ios.footnote.copy(color = Ios.Blue),
                            modifier = Modifier.padding(horizontal = 6.dp).pressable({ onRequestPermission(Manifest.permission.READ_CONTACTS) }))
                    }
                } else {
                    if (calc != null) item {
                        Column(Modifier.fillMaxWidth().glass(Ios.rr(18f), backdrop, tint = Color.White.copy(alpha = 0.12f), refraction = 0.4f).padding(16.dp)) {
                            Text(query, style = Ios.footnote)
                            Text("= " + Calc.fmt(calc), style = Ios.largeTitle)
                        }
                    }
                    if (matches.isNotEmpty()) {
                        item { SectionTitle("Top Hit") }
                        item {
                            val top = matches.first()
                            Row(Modifier.fillMaxWidth().glass(Ios.rr(18f), backdrop, tint = Color.White.copy(alpha = 0.12f), refraction = 0.4f)
                                .pressable({ launch(top) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIconImage(top, 52.dp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(top.label, style = Ios.headline)
                                    Text("Application", style = Ios.footnote)
                                }
                            }
                        }
                        if (matches.size > 1) {
                            item { SectionTitle("Apps") }
                            item { AppGridCard(matches.drop(1).take(12), backdrop) { launch(it) } }
                        }
                    }
                    if (contacts.isNotEmpty()) {
                        item { SectionTitle("Contacts") }
                        item {
                            Column(Modifier.fillMaxWidth().glass(Ios.rr(18f), backdrop, tint = Color.White.copy(alpha = 0.12f), refraction = 0.4f)) {
                                contacts.forEachIndexed { i, c ->
                                    if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(Ios.Separator))
                                    Row(Modifier.fillMaxWidth().pressable({
                                        com.glass.launcher.util.Sys.open(ctx, Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(c.id, c.lookup))); onClose()
                                    }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(40.dp).background(Brushes.avatar, CircleShape), contentAlignment = Alignment.Center) {
                                            Text(c.name.take(1).uppercase(), style = Ios.headline)
                                        }
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(c.name, style = Ios.headline.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (c.number != null) Text(c.number, style = Ios.footnote)
                                        }
                                        if (c.number != null) Box(Modifier.size(34.dp).background(Color.White.copy(alpha = 0.16f), CircleShape)
                                            .pressable({ com.glass.launcher.util.Sys.open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.number))); onClose() }),
                                            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Call, null, tint = Ios.Green, modifier = Modifier.size(18.dp)) }
                                    }
                                }
                            }
                        }
                    }
                    if (settingHits.isNotEmpty()) {
                        item { SectionTitle("Settings") }
                        item {
                            ActionList(backdrop, settingHits.map { (name, action) ->
                                Triple(Icons.Rounded.Settings, name) { com.glass.launcher.util.Sys.open(ctx, Intent(action)); onClose() }
                            })
                        }
                    }
                    item { SectionTitle("Search") }
                    item {
                        ActionList(backdrop, listOf(
                            Triple(Icons.Rounded.Language, "Search Web for “$query”") { web() },
                            Triple(Icons.Rounded.Shop, "Search Play Store") {
                                com.glass.launcher.util.Sys.open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query)))); onClose()
                            },
                            Triple(Icons.Rounded.Map, "Search Maps") {
                                com.glass.launcher.util.Sys.open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))); onClose()
                            },
                        ))
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

object Brushes {
    val avatar = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFA5A9B4), Color(0xFF7D828E)))
}

@Composable
fun SectionTitle(t: String) {
    Text(t, style = Ios.headline.copy(fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f)), modifier = Modifier.padding(start = 6.dp))
}

@Composable
fun AppGridCard(list: List<AppEntry>, backdrop: Backdrop?, onClick: (AppEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().glass(Ios.rr(22f), backdrop, tint = Color.White.copy(alpha = 0.12f), refraction = 0.4f).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                row.forEach { e ->
                    Column(Modifier.weight(1f).pressable({ onClick(e) }), horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIconImage(e, 56.dp)
                        Text(e.label, style = Ios.label.copy(shadow = null), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun ActionList(backdrop: Backdrop?, rows: List<Triple<ImageVector, String, () -> Unit>>) {
    Column(Modifier.fillMaxWidth().glass(Ios.rr(18f), backdrop, tint = Color.White.copy(alpha = 0.12f), refraction = 0.4f)) {
        rows.forEachIndexed { i, (icon, label, action) ->
            if (i > 0) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(0.5.dp).background(Ios.Separator))
            Row(Modifier.fillMaxWidth().pressable(action).padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Ios.Blue, modifier = Modifier.size(22.dp))
                Text(label, style = Ios.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp).weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(14.dp))
            }
        }
    }
}
