package com.glass.apps.contacts

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallMissed
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.glass.apps.common.Avatar
import com.glass.apps.common.BarButton
import com.glass.apps.common.CellRow
import com.glass.apps.common.Divider
import com.glass.apps.common.IosTheme
import com.glass.apps.common.LargeTitle
import com.glass.apps.common.NavBar
import com.glass.apps.common.NeedsPermissions
import com.glass.apps.common.Screen
import com.glass.apps.common.SearchField
import com.glass.apps.common.Section
import com.glass.apps.common.Segmented
import com.glass.apps.common.T
import com.glass.apps.common.TabBar
import com.glass.apps.common.Txt
import com.glass.apps.common.ios
import com.glass.apps.common.rr
import com.glass.apps.common.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Contact(val id: Long, val lookup: String, val name: String, val photo: String?, val starred: Boolean, val hasPhone: Boolean)
data class Detail(val phones: List<Pair<String, String>>, val emails: List<Pair<String, String>>, val addresses: List<String>, val birthday: String?, val org: String?)
data class CallItem(val number: String, val name: String?, val type: Int, val date: Long, val duration: Long, val count: Int = 1)

object ContactsRepo {
    suspend fun all(ctx: Context): List<Contact> = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, ContactsContract.Contacts.STARRED, ContactsContract.Contacts.HAS_PHONE_NUMBER),
                null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC")?.use { c ->
                buildList { while (c.moveToNext()) { val n = c.getString(2) ?: continue; add(Contact(c.getLong(0), c.getString(1) ?: "", n, c.getString(3), c.getInt(4) == 1, c.getInt(5) == 1)) } }
            }
        }.getOrNull() ?: emptyList()
    }

    suspend fun detail(ctx: Context, id: Long): Detail = withContext(Dispatchers.IO) {
        val phones = ArrayList<Pair<String, String>>(); val emails = ArrayList<Pair<String, String>>(); val addr = ArrayList<String>()
        var bday: String? = null; var org: String? = null
        runCatching {
            ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3),
                "${ContactsContract.Data.CONTACT_ID}=?", arrayOf("$id"), null)?.use { c ->
                while (c.moveToNext()) {
                    val v = c.getString(1) ?: continue
                    when (c.getString(0)) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString().lowercase()
                            if (phones.none { it.second.filter(Char::isDigit) == v.filter(Char::isDigit) }) phones += label to v
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails += "email" to v
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> addr += v
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> if (c.getInt(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) bday = v
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> org = v
                    }
                }
            }
        }
        Detail(phones, emails, addr, bday, org)
    }

    fun setStarred(ctx: Context, id: Long, star: Boolean) {
        runCatching {
            ctx.contentResolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
                ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (star) 1 else 0) }, null, null)
        }
    }

    suspend fun calls(ctx: Context): List<CallItem> = withContext(Dispatchers.IO) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
        val raw = runCatching {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                buildList { while (c.moveToNext() && size < 300) add(CallItem(c.getString(0) ?: "Unknown", c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4))) }
            }
        }.getOrNull() ?: emptyList()
        // collapse consecutive calls from the same number (iOS shows "(3)")
        val out = ArrayList<CallItem>()
        raw.forEach { x -> val last = out.lastOrNull(); if (last != null && last.number == x.number && last.type == x.type) out[out.lastIndex] = last.copy(count = last.count + 1) else out += x }
        out
    }

    fun call(ctx: Context, number: String) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val i = if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_DIAL, uri)
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun message(ctx: Context, number: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).setClassName(ctx, "com.glass.apps.messages.MessagesActivity")
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

// ------------------------------------------------------------------------------------------ shared UI

@Composable
fun ContactPhoto(c: Contact?, name: String, size: androidx.compose.ui.unit.Dp) {
    if (c?.photo != null) AsyncImage(c.photo, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else Avatar(name, size)
}

@Composable
fun ContactsList(title: String = "Contacts", showTitle: Boolean = true, onOpen: (Contact) -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { all = ContactsRepo.all(ctx) }
    val list = remember(q, all) { if (q.isBlank()) all else all.filter { it.name.contains(q, true) } }
    val sections = remember(list) { list.groupBy { it.name.first().uppercaseChar().let { ch -> if (ch.isLetter()) ch else '#' } }.toSortedMap(compareBy { if (it == '#') '[' else it }) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (showTitle) item {
                LargeTitle(title, trailing = {
                    BarButton(Icons.Rounded.Add) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)) }
                    }
                })
            }
            item { SearchField(q, { q = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            item { Txt("${all.size} contacts", T.footnote, ios.label2, Modifier.padding(16.dp, 6.dp)) }
            sections.forEach { (letter, cs) ->
                item(key = "h$letter") {
                    Txt("$letter", T.footnote.copy(fontWeight = FontWeight.SemiBold), ios.label2,
                        Modifier.fillMaxWidth().background(ios.groupedBg).padding(horizontal = 16.dp, vertical = 3.dp))
                }
                items(cs, key = { it.id }) { c ->
                    Column(Modifier.tap(1f) { onOpen(c) }) {
                        Txt(c.name, T.body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp), maxLines = 1)
                        Divider()
                    }
                }
            }
        }
        // A–Z index
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).pointerInput(sections) {
            detectVerticalDragGestures { ch, _ ->
                val keys = sections.keys.toList(); if (keys.isEmpty()) return@detectVerticalDragGestures
                val k = keys[((ch.position.y / size.height) * keys.size).toInt().coerceIn(0, keys.lastIndex)]
                var idx = 3; for ((kk, v) in sections) { if (kk == k) break; idx += v.size + 1 }
                scope.launch { state.scrollToItem(idx) }
            }
        }) {
            sections.keys.forEach { k -> Txt("$k", T.caption2.copy(fontWeight = FontWeight.SemiBold), ios.blue, Modifier.width(14.dp), align = TextAlign.Center) }
        }
    }
}

@Composable
fun ContactCard(c: Contact, backLabel: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var d by remember { mutableStateOf<Detail?>(null) }
    var star by remember { mutableStateOf(c.starred) }
    LaunchedEffect(c.id) { d = ContactsRepo.detail(ctx, c.id) }
    val primary = d?.phones?.firstOrNull()?.second
    Screen(grouped = true) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.glass.apps.common.BackButton(backLabel, onBack)
                    Spacer(Modifier.weight(1f))
                    BarButton(text = "Edit") {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_EDIT, ContactsContract.Contacts.getLookupUri(c.id, c.lookup))) }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ContactPhoto(c, c.name, 104.dp)
                    Txt(c.name, T.title1.copy(fontWeight = FontWeight.Normal), modifier = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp), align = TextAlign.Center)
                    d?.org?.let { Txt(it, T.subhead, ios.label2) }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        @Composable fun Act(icon: ImageVector, label: String, enabled: Boolean, on: () -> Unit) {
                            Column(Modifier.weight(1f).clip(rr(12f)).background(ios.cell).then(if (enabled) Modifier.tap(0.95f, onClick = on) else Modifier).padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(icon, null, tint = if (enabled) ios.blue else ios.label3, modifier = Modifier.size(24.dp))
                                Txt(label, T.caption, if (enabled) ios.blue else ios.label3)
                            }
                        }
                        Act(Icons.Rounded.Message, "message", primary != null) { ContactsRepo.message(ctx, primary!!) }
                        Act(Icons.Rounded.Call, "call", primary != null) { ContactsRepo.call(ctx, primary!!) }
                        Act(Icons.Rounded.Videocam, "video", primary != null) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + primary!!.filter { it.isDigit() }))) }
                        }
                        Act(Icons.Rounded.Email, "mail", d?.emails?.isNotEmpty() == true) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + d!!.emails.first().second))) }
                        }
                    }
                }
            }
            d?.let { det ->
                if (det.phones.isNotEmpty()) item {
                    Section {
                        det.phones.forEachIndexed { i, (label, num) ->
                            if (i > 0) Divider()
                            Column(Modifier.fillMaxWidth().tap(1f) { ContactsRepo.call(ctx, num) }.padding(16.dp, 10.dp)) {
                                Txt(label, T.footnote); Txt(num, T.body, ios.blue)
                            }
                        }
                    }
                }
                if (det.emails.isNotEmpty()) item {
                    Section(modifier = Modifier.padding(top = 16.dp)) {
                        det.emails.forEachIndexed { i, (l, e) -> if (i > 0) Divider(); Column(Modifier.padding(16.dp, 10.dp)) { Txt(l, T.footnote); Txt(e, T.body, ios.blue) } }
                    }
                }
                if (det.addresses.isNotEmpty()) item {
                    Section(modifier = Modifier.padding(top = 16.dp)) { det.addresses.forEach { a -> Column(Modifier.padding(16.dp, 10.dp)) { Txt("address", T.footnote); Txt(a, T.body) } } }
                }
                det.birthday?.let { b -> item { Section(modifier = Modifier.padding(top = 16.dp)) { Column(Modifier.padding(16.dp, 10.dp)) { Txt("birthday", T.footnote); Txt(b, T.body) } } } }
            }
            item {
                Section(modifier = Modifier.padding(top = 16.dp, bottom = 40.dp)) {
                    if (primary != null) { CellRow("Send Message", color = ios.blue, chevron = false) { ContactsRepo.message(ctx, primary) }; Divider() }
                    CellRow("Share Contact", color = ios.blue, chevron = false) {
                        runCatching {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
                                .putExtra(Intent.EXTRA_STREAM, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, c.lookup)), null))
                        }
                    }
                    Divider()
                    CellRow(if (star) "Remove from Favourites" else "Add to Favourites", color = ios.blue, chevron = false) {
                        star = !star; ContactsRepo.setStarred(ctx, c.id, star)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Contacts app

class ContactsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IosTheme {
                NeedsPermissions(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), "Allow Contacts access", "Contacts shows the people saved on this phone.") {
                    var open by remember { mutableStateOf<Contact?>(null) }
                    BackHandler(open != null) { open = null }
                    Screen {
                        AnimatedContent(open, label = "c") { o ->
                            if (o == null) ContactsList { open = it } else ContactCard(o, "Contacts") { open = null }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Phone app

class PhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preset = intent?.data?.schemeSpecificPart ?: ""
        setContent {
            IosTheme {
                NeedsPermissions(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE),
                    "Allow Phone access", "Phone shows your recent calls and contacts and places calls through your SIM.") {
                    PhoneApp(preset)
                }
            }
        }
    }
}

@Composable
fun PhoneApp(preset: String) {
    var tab by remember { mutableIntStateOf(if (preset.isNotEmpty()) 3 else 1) }
    var open by remember { mutableStateOf<Contact?>(null) }
    BackHandler(open != null) { open = null }
    Screen {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val o = open
                if (o != null) ContactCard(o, listOf("Favourites", "Recents", "Contacts", "Keypad")[tab]) { open = null }
                else when (tab) {
                    0 -> Favourites { open = it }
                    1 -> Recents { open = it }
                    2 -> ContactsList { open = it }
                    else -> Keypad(preset)
                }
            }
            TabBar(listOf("Favourites" to Icons.Rounded.Star, "Recents" to Icons.Rounded.Schedule, "Contacts" to Icons.Rounded.Person, "Keypad" to Icons.Rounded.Dialpad), tab) { tab = it; open = null }
        }
    }
}

@Composable
private fun Favourites(onOpen: (Contact) -> Unit) {
    val ctx = LocalContext.current
    var favs by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(Unit) { favs = ContactsRepo.all(ctx).filter { it.starred } }
    LazyColumn {
        item { LargeTitle("Favourites") }
        if (favs.isEmpty()) item { Txt("No Favourites", T.title3, ios.label3, Modifier.fillMaxWidth().padding(top = 100.dp), align = TextAlign.Center) }
        items(favs, key = { it.id }) { c ->
            Row(Modifier.fillMaxWidth().tap(1f) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) { ContactsRepo.detail(ctx, c.id).phones.firstOrNull()?.let { ContactsRepo.call(ctx, it.second) } }
            }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ContactPhoto(c, c.name, 44.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Txt(c.name, T.headline); Txt("mobile", T.footnote, ios.label2) }
                Icon(Icons.Outlined.Info, null, tint = ios.blue, modifier = Modifier.size(24.dp).tap { onOpen(c) })
            }
            Divider(72.dp)
        }
    }
}

@Composable
private fun Recents(onOpen: (Contact) -> Unit) {
    val ctx = LocalContext.current
    var calls by remember { mutableStateOf<List<CallItem>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var filter by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { calls = ContactsRepo.calls(ctx); contacts = ContactsRepo.all(ctx) }
    val list = if (filter == 1) calls.filter { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE } else calls
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp), contentAlignment = Alignment.Center) {
            Segmented(listOf("All", "Missed"), filter, Modifier.width(200.dp)) { filter = it }
        }
        Txt("Recents", T.largeTitle, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        LazyColumn {
            items(list) { c ->
                val missed = c.type == CallLog.Calls.MISSED_TYPE || c.type == CallLog.Calls.REJECTED_TYPE
                Row(Modifier.fillMaxWidth().tap(1f) { ContactsRepo.call(ctx, c.number) }.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (c.type) { CallLog.Calls.OUTGOING_TYPE -> Icons.Rounded.CallMade; CallLog.Calls.INCOMING_TYPE -> Icons.Rounded.CallReceived; else -> Icons.Rounded.CallMissed },
                        null, tint = ios.label3, modifier = Modifier.size(16.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Txt((c.name ?: c.number) + if (c.count > 1) " (${c.count})" else "", T.headline.copy(fontWeight = FontWeight.SemiBold), if (missed) ios.red else ios.label, maxLines = 1)
                        Txt(if (c.name != null) "mobile" else "unknown", T.subhead, ios.label2)
                    }
                    Txt(if (DateUtils.isToday(c.date)) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(c.date))
                        else DateUtils.getRelativeTimeSpanString(c.date, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString(), T.subhead, ios.label2)
                    Icon(Icons.Outlined.Info, null, tint = ios.blue, modifier = Modifier.padding(start = 10.dp).size(24.dp).tap {
                        val digits = c.number.filter(Char::isDigit).takeLast(10)
                        val match = contacts.firstOrNull { it.name == c.name }
                        if (match != null) onOpen(match) else runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                .putExtra(ContactsContract.Intents.Insert.PHONE, c.number.ifBlank { digits }))
                        }
                    })
                }
                Divider(32.dp)
            }
        }
    }
}

private val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")

@Composable
private fun Keypad(preset: String) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var num by remember { mutableStateOf(preset) }
    Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(num, TextStyle(fontSize = if (num.length > 12) 30.sp else 38.sp, fontWeight = FontWeight.Normal), maxLines = 1)
                if (num.isNotEmpty()) Txt("Add Number", T.callout, ios.blue, Modifier.padding(top = 6.dp).tap {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, num)) }
                })
            }
        }
        keys.chunked(3).forEach { row ->
            Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                row.forEach { (k, sub) ->
                    Column(Modifier.size(80.dp).clip(CircleShape).background(ios.fill).tap(0.92f) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); num += k
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Txt(k, TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Normal), lineHeightFix = true)
                        if (sub.isNotEmpty()) Txt(sub, T.caption2.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
                    }
                }
            }
        }
        Row(Modifier.padding(top = 10.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(80.dp))
            Box(Modifier.size(80.dp).clip(CircleShape).background(ios.green).tap(0.92f) { if (num.isNotBlank()) ContactsRepo.call(ctx, num) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Call, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                if (num.isNotEmpty()) Icon(Icons.Rounded.Backspace, null, tint = ios.label2, modifier = Modifier.size(28.dp).tap { num = num.dropLast(1) })
            }
        }
    }
}

@Composable
private fun Txt(t: String, style: TextStyle, lineHeightFix: Boolean) = Txt(t, style.copy(lineHeight = style.fontSize), ios.label)
