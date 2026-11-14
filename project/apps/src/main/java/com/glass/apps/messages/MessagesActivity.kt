package com.glass.apps.messages

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glass.apps.common.Avatar
import com.glass.apps.common.BarButton
import com.glass.apps.common.Divider
import com.glass.apps.common.IosTheme
import com.glass.apps.common.LargeTitle
import com.glass.apps.common.NavBar
import com.glass.apps.common.NeedsPermissions
import com.glass.apps.common.Screen
import com.glass.apps.common.SearchField
import com.glass.apps.common.T
import com.glass.apps.common.Txt
import com.glass.apps.common.ios
import com.glass.apps.common.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Sms(val id: Long, val thread: Long, val address: String, val body: String, val date: Long, val sent: Boolean, val read: Boolean)
data class Thread(val id: Long, val address: String, val name: String?, val snippet: String, val date: Long, val unread: Int)

object SmsRepo {
    private val names = HashMap<String, String?>()

    fun name(ctx: Context, address: String): String? = names.getOrPut(address) {
        runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    suspend fun threads(ctx: Context): List<Thread> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<Long, Thread>()
        runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n++ < 8000) {
                    val t = c.getLong(0)
                    val unread = if (c.getInt(4) == 0 && c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX) 1 else 0
                    val existing = map[t]
                    if (existing == null) {
                        val addr = c.getString(1) ?: "Unknown"
                        map[t] = Thread(t, addr, name(ctx, addr), c.getString(2) ?: "", c.getLong(3), unread)
                    } else if (unread > 0) map[t] = existing.copy(unread = existing.unread + 1)
                }
            }
        }
        map.values.toList()
    }

    suspend fun messages(ctx: Context, thread: Long?, address: String): List<Sms> = withContext(Dispatchers.IO) {
        val (sel, args) = if (thread != null && thread > 0) "${Telephony.Sms.THREAD_ID}=?" to arrayOf("$thread")
        else "${Telephony.Sms.ADDRESS}=?" to arrayOf(address)
        runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ), sel, args, "${Telephony.Sms.DATE} ASC")?.use { c ->
                buildList { while (c.moveToNext()) add(Sms(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4),
                    c.getInt(5) != Telephony.Sms.MESSAGE_TYPE_INBOX, c.getInt(6) == 1)) }
            }
        }.getOrNull() ?: emptyList()
    }

    /** Sends via the SIM. As a non-default SMS app, Android itself saves the message to "Sent". */
    fun send(ctx: Context, to: String, text: String) {
        val sm = ctx.getSystemService(SmsManager::class.java) ?: return
        val parts = sm.divideMessage(text)
        val sent = ArrayList<PendingIntent>().apply {
            repeat(parts.size) { add(PendingIntent.getBroadcast(ctx, it, Intent("com.glass.apps.SMS_SENT").setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)) }
        }
        runCatching { sm.sendMultipartTextMessage(to, null, parts, sent, null) }
    }
}

private fun shortDate(t: Long): String = when {
    DateUtils.isToday(t) -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    DateUtils.isToday(t + 86_400_000) -> "Yesterday"
    System.currentTimeMillis() - t < 6 * 86_400_000L -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(t))
    else -> SimpleDateFormat("d/M/yy", Locale.getDefault()).format(Date(t))
}

class MessagesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preset = intent?.data?.schemeSpecificPart?.substringBefore('?')
        setContent {
            IosTheme {
                NeedsPermissions(listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS),
                    "Allow Messages access", "Messages shows your SMS conversations and sends texts through your SIM. Your current SMS app keeps working as before.") {
                    MessagesApp(preset)
                }
            }
        }
    }
}

/** Re-runs [onChange] whenever the SMS database changes. */
@Composable
private fun SmsObserver(onChange: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) = onChange() }
        ctx.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, o)
        onDispose { ctx.contentResolver.unregisterContentObserver(o) }
    }
}

@Composable
fun MessagesApp(preset: String?) {
    var open by remember { mutableStateOf<Pair<Long?, String>?>(preset?.takeIf { it.isNotBlank() }?.let { null to it }) }
    var composing by remember { mutableStateOf(false) }
    BackHandler(open != null || composing) { open = null; composing = false }
    Screen {
        val o = open
        when {
            o != null -> Conversation(o.first, o.second) { open = null }
            composing -> NewMessage(onCancel = { composing = false }) { open = null to it; composing = false }
            else -> Inbox(onOpen = { open = it.id to it.address }, onCompose = { composing = true })
        }
    }
}

@Composable
private fun Inbox(onOpen: (Thread) -> Unit, onCompose: () -> Unit) {
    val ctx = LocalContext.current
    var threads by remember { mutableStateOf<List<Thread>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    SmsObserver { version++ }
    LaunchedEffect(version) { threads = SmsRepo.threads(ctx) }
    val list = remember(q, threads) { if (q.isBlank()) threads else threads.filter { (it.name ?: it.address).contains(q, true) || it.snippet.contains(q, true) } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Messages", trailing = { BarButton(Icons.Rounded.EditNote, onClick = onCompose) }) }
        item { SearchField(q, { q = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        items(list, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth().tap(1f) { onOpen(t) }.padding(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 18.dp).size(10.dp).clip(CircleShape).background(if (t.unread > 0) ios.blue else Color.Transparent))
                Avatar(t.name ?: t.address, 46.dp, Modifier.padding(start = 4.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(t.name ?: t.address, T.headline, modifier = Modifier.weight(1f), maxLines = 1)
                        Txt(shortDate(t.date), T.subhead, ios.label2)
                        Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = ios.label3, modifier = Modifier.padding(start = 4.dp).size(12.dp))
                    }
                    Txt(t.snippet, T.subhead, ios.label2, maxLines = 2)
                }
            }
            Divider(78.dp)
        }
        if (list.isEmpty()) item { Txt("No Messages", T.title3, ios.label3, Modifier.fillMaxWidth().padding(top = 120.dp), align = TextAlign.Center) }
    }
}

@Composable
private fun Conversation(thread: Long?, address: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var msgs by remember { mutableStateOf<List<Sms>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    val name = remember(address) { SmsRepo.name(ctx, address) }
    SmsObserver { version++ }
    LaunchedEffect(version) { msgs = SmsRepo.messages(ctx, thread, address); if (msgs.isNotEmpty()) state.scrollToItem(msgs.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().background(ios.bar).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) { com.glass.apps.common.BackButton("") { onBack() } }
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(name ?: address, 52.dp)
                Txt((name ?: address) + " ›", T.caption, modifier = Modifier.padding(top = 4.dp))
            }
            Divider(0.dp)
        }
        LazyColumn(state = state, modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                val prev = msgs.getOrNull(i - 1)
                if (prev == null || m.date - prev.date > 3_600_000) {
                    Txt((if (DateUtils.isToday(m.date)) "Today " else SimpleDateFormat("EEE d MMM ", Locale.getDefault()).format(Date(m.date))) +
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(m.date)), T.caption, ios.label2,
                        Modifier.fillMaxWidth().padding(vertical = 10.dp), align = TextAlign.Center)
                }
                val next = msgs.getOrNull(i + 1)
                val tail = next == null || next.sent != m.sent || next.date - m.date > 60_000
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.sent) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp, 18.dp, if (m.sent && tail) 4.dp else 18.dp, if (!m.sent && tail) 4.dp else 18.dp))
                        .background(if (m.sent) ios.green else ios.cell2).padding(horizontal = 13.dp, vertical = 8.dp)) {
                        Txt(m.body, T.body, if (m.sent) Color.White else ios.label)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(ios.bar).navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(ios.bg).padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (text.isEmpty()) Txt("Text Message • SMS", T.body, ios.label3)
                BasicTextField(text, { text = it }, textStyle = T.body.copy(color = ios.label), cursorBrush = SolidColor(ios.green), maxLines = 5, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.padding(start = 8.dp).size(32.dp).clip(CircleShape).background(if (text.isNotBlank()) ios.green else ios.fill)
                .tap { if (text.isNotBlank()) { SmsRepo.send(ctx, address, text.trim()); text = "" } }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun NewMessage(onCancel: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var to by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(to) {
        hits = if (to.isBlank()) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(to)),
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                    buildList { while (c.moveToNext() && size < 20) add((c.getString(0) ?: "") to (c.getString(1) ?: "")) }
                }
            }.getOrNull() ?: emptyList()
        }
    }
    Column(Modifier.fillMaxSize()) {
        NavBar("New Message", trailing = { BarButton(text = "Cancel", onClick = onCancel) })
        Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("To:", T.body, ios.label2)
            BasicTextField(to, { to = it }, singleLine = true, textStyle = T.body.copy(color = ios.label), cursorBrush = SolidColor(ios.blue),
                modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (to.any(Char::isDigit)) Txt("Go", T.headline, ios.blue, Modifier.tap { onPick(to.trim()) })
        }
        Divider(0.dp)
        LazyColumn {
            items(hits) { (n, num) ->
                Column(Modifier.fillMaxWidth().tap(1f) { onPick(num) }.padding(16.dp, 10.dp)) { Txt(n, T.headline); Txt(num, T.subhead, ios.label2) }
                Divider()
            }
        }
    }
}
