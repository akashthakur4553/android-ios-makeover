package com.glass.launcher.data

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DragState(val item: HomeItem, touchOffset: Offset, val iconSizePx: Float) {
    var pos by mutableStateOf(Offset.Zero)
    val touchOffset = touchOffset
}

class PendingDrag(val item: HomeItem, val start: Offset, val touchOffset: Offset, val iconSizePx: Float, val fromFolder: String?)

class MenuState(val item: HomeItem, val anchor: Rect, val inDock: Boolean)

class OpenFolder(val id: String, val anchor: Rect)

sealed interface Container {
    data class Page(val index: Int) : Container
    data object Dock : Container
}

class HomeStore(private val ctx: Context, private val repo: AppRepository, private val scope: CoroutineScope) {
    private val file = File(ctx.filesDir, "layout.json")

    val pages: SnapshotStateList<SnapshotStateList<HomeItem>> = mutableStateListOf()
    val dock: SnapshotStateList<HomeItem> = mutableStateListOf()
    private val known = HashSet<String>()

    var ready by mutableStateOf(false); private set
    var editMode by mutableStateOf(false)
    var drag by mutableStateOf<DragState?>(null)
    var pending by mutableStateOf<PendingDrag?>(null)
    var menu by mutableStateOf<MenuState?>(null)
    var openFolder by mutableStateOf<OpenFolder?>(null)
    var folderHover by mutableStateOf<String?>(null)
    var renameFolder by mutableStateOf<String?>(null)

    /** Item bounds in root (window) coordinates, updated by the UI; used for menus/animations. */
    val bounds = HashMap<String, Rect>()

    private var saveJob: Job? = null

    init {
        scope.launch {
            repo.apps.collect { apps -> if (repo.loaded.value) sync(apps) }
        }
    }

    // ---------------------------------------------------------------- sync / defaults

    private suspend fun sync(apps: List<AppEntry>) {
        if (!ready) {
            val loaded = withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            if (loaded == null) buildDefault(apps) else apply(loaded)
            ready = true
        }
        val installed = apps.map { it.key.raw }.toHashSet()
        // Drop uninstalled apps
        fun clean(list: SnapshotStateList<HomeItem>) {
            val it = list.listIterator()
            while (it.hasNext()) {
                when (val x = it.next()) {
                    is AppItem -> if (x.key.raw !in installed) it.remove()
                    is FolderItem -> {
                        val keep = x.apps.filter { k -> k.raw in installed }
                        when {
                            keep.isEmpty() -> it.remove()
                            keep.size == 1 -> it.set(AppItem(x.id, keep[0]))
                            keep.size != x.apps.size -> it.set(x.copy(apps = keep))
                        }
                    }
                    is WidgetItem -> {}
                }
            }
        }
        pages.forEach { clean(it) }
        clean(dock)
        // Migration: early builds put the Contacts entry in the dock instead of the Phone dialer.
        repo.forIntent(Intent(Intent.ACTION_DIAL))?.let { dialer ->
            val i = dock.indexOfFirst { it is AppItem && it.key.pkg == dialer.key.pkg && it.key != dialer.key }
            if (i >= 0 && !isOnHome(dialer.key)) dock[i] = AppItem(dock[i].id, dialer.key)
        }
        migrateToGlassApps(apps)
        // Newly installed apps go to the end of the home screen, just like iOS.
        val fresh = apps.filter { it.key.raw !in known && it.key.pkg != ctx.packageName }
        fresh.forEach { e ->
            known += e.key.raw
            if (!isOnHome(e.key)) addToLastPage(AppItem(newId(), e.key))
        }
        known.retainAll(installed)
        normalize()
        save()
    }

    /**
     * When the Glass companion apps are installed, put them where the stock equivalents were
     * (stock apps stay in the App Library as a backup). Stock apps are found by what they do
     * (intent categories), so this works on any Android phone, not just one brand.
     */
    private fun migrateToGlassApps(apps: List<AppEntry>) {
        val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
        if (sp.getBoolean("glassAppsV1", false)) return
        val glass = apps.filter { it.key.pkg == "com.glass.apps" && !it.isWork }
        if (glass.isEmpty()) return
        fun g(cls: String) = glass.firstOrNull { it.component.className.endsWith(cls) }?.key
        val pm = ctx.packageManager
        fun stockFor(i: Intent): Set<String> = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.filter { it != "com.glass.apps" && it != "android" }.toSet()
        fun cat(c: String) = Intent(Intent.ACTION_MAIN).addCategory(c)
        val rules: List<Pair<Set<String>, AppKey?>> = listOf(
            stockFor(Intent(Intent.ACTION_DIAL)) to g(".PhoneActivity"),
            stockFor(cat(Intent.CATEGORY_APP_MESSAGING)) + stockFor(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:1"))) to g(".MessagesActivity"),
            stockFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) to g(".CameraActivity"),
            stockFor(cat(Intent.CATEGORY_APP_GALLERY)) + setOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.coloros.gallery3d", "com.oneplus.gallery") to g(".PhotosActivity"),
            stockFor(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) to g(".ClockActivity"),
            stockFor(cat(Intent.CATEGORY_APP_CALENDAR)) to g(".CalendarActivity"),
            stockFor(cat(Intent.CATEGORY_APP_MUSIC)) to g(".MusicActivity"),
            stockFor(cat(Intent.CATEGORY_APP_CONTACTS)).filter { p -> pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0).none { it.activityInfo.packageName == p } }.toSet() to g(".ContactsActivity"),
        )
        val used = HashSet<AppKey>()
        fun swap(list: SnapshotStateList<HomeItem>) {
            for (i in list.indices) {
                val it = list[i] as? AppItem ?: continue
                val target = rules.firstOrNull { (pk, k) -> k != null && it.key.pkg in pk && k !in used }?.second ?: continue
                // the dialer and contacts often share a package: only swap the dialer entry for Phone
                if (target == g(".PhoneActivity") && !(it.key.cls.contains("Dial", true) || it.key.cls.contains("TwelveKey", true) || it.key.cls.contains("Phone", true))) continue
                list[i] = AppItem(it.id, target); used += target
            }
        }
        swap(dock); pages.forEach { swap(it) }
        // the rest of the Glass apps go to the last page (Photos/Music/Files etc. if they weren't swapped in)
        glass.forEach { e -> known += e.key.raw; if (!isOnHome(e.key)) addToLastPage(AppItem(newId(), e.key)) }
        sp.edit().putBoolean("glassAppsV1", true).apply()
    }

    private fun buildDefault(apps: List<AppEntry>) {
        pages.clear(); dock.clear(); known.clear()
        val used = HashSet<String>()
        fun take(vararg pkgs: String): AppEntry? = pkgs.firstNotNullOfOrNull { p ->
            apps.firstOrNull { it.key.pkg == p && !it.isWork && it.key.raw !in used }
        }?.also { used += it.key.raw }

        listOfNotNull(
            repo.forIntent(Intent(Intent.ACTION_DIAL))?.takeIf { it.key.raw !in used }?.also { used += it.key.raw }
                ?: take("com.android.contacts", "com.google.android.dialer"),
            take("com.google.android.apps.messaging", "com.android.mms"),
            take("com.android.chrome"),
            repo.forIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))?.takeIf { it.key.raw !in used }?.also { used += it.key.raw }
                ?: take("com.android.camera"),
        ).forEach { dock += AppItem(newId(), it.key) }

        val page1 = mutableStateListOf<HomeItem>(
            WidgetItem(newId(), WidgetKind.Weather, WidgetSize.Small),
            WidgetItem(newId(), WidgetKind.Calendar, WidgetSize.Small),
        )
        val pref = listOf(
            "com.whatsapp", "com.instagram.android", "com.google.android.gm", "com.google.android.apps.maps",
            "com.miui.gallery", "com.google.android.apps.photos", "com.google.android.youtube", "com.android.settings",
            "com.miui.notes", "com.android.deskclock", "com.miui.calculator", "com.google.android.apps.nbu.paisa.user",
            "com.android.vending", "com.miui.weather2", "com.xiaomi.calendar", "com.google.android.apps.bard",
            "ai.perplexity.app.android", "com.linkedin.android", "com.twitter.android", "com.reddit.frontpage",
            "org.telegram.messenger", "com.Slack", "in.amazon.mShop.android.shopping", "com.application.zomato",
            "in.swiggy.android", "com.zeptoconsumerapp", "com.ubercab", "com.olacabs.customer", "com.rapido.passenger",
            "in.startv.hotstar", "com.nextbillion.groww", "com.makemytrip", "cris.org.in.prs.ima", "com.truecaller",
            "com.duolingo", "com.chess", "com.miui.securitycenter", "com.mi.android.globalFileexplorer",
            "com.google.android.apps.nbu.files", "com.miui.screenrecorder", "com.android.soundrecorder", "com.xiaomi.scanner",
        )
        val picks = pref.mapNotNull { take(it) }.toMutableList()
        // Put Google apps & settings-type stuff into iOS-style folders on page 2.
        val googleFolder = listOf(
            "com.google.android.googlequicksearchbox", "com.google.android.apps.docs", "com.google.android.apps.tachyon",
            "com.google.android.apps.chromecast.app", "com.google.android.apps.adm", "com.google.android.apps.wellbeing",
            "com.google.android.contactkeys", "com.google.android.apps.safetyhub",
        ).mapNotNull { take(it)?.key }
        val utilFolder = listOf(
            "com.miui.compass", "com.android.stk", "com.miui.backup", "com.android.thememanager", "com.xiaomi.midrop",
            "com.android.fmradio", "com.miui.mediaeditor", "com.xiaomi.account",
        ).mapNotNull { take(it)?.key }

        repeat(16) { if (picks.isNotEmpty()) page1 += AppItem(newId(), picks.removeAt(0).key) }
        pages.add(page1)
        val page2 = mutableStateListOf<HomeItem>()
        page2 += WidgetItem(newId(), WidgetKind.Suggestions, WidgetSize.Medium)
        if (googleFolder.size >= 2) page2 += FolderItem(newId(), "Google", googleFolder)
        if (utilFolder.size >= 2) page2 += FolderItem(newId(), "Utilities", utilFolder)
        while (picks.isNotEmpty() && page2.sumOf { it.spanX * it.spanY } < 24) page2 += AppItem(newId(), picks.removeAt(0).key)
        pages.add(page2)
        apps.forEach { known += it.key.raw }
    }

    // ---------------------------------------------------------------- queries

    fun isOnHome(k: AppKey): Boolean {
        fun has(list: List<HomeItem>) = list.any { (it is AppItem && it.key == k) || (it is FolderItem && k in it.apps) }
        return has(dock) || pages.any { has(it) }
    }

    fun locate(id: String): Pair<Container, Int>? {
        dock.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return Container.Dock to it }
        pages.forEachIndexed { p, list -> list.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return Container.Page(p) to it } }
        return null
    }

    fun find(id: String): HomeItem? = dock.firstOrNull { it.id == id } ?: pages.firstNotNullOfOrNull { l -> l.firstOrNull { it.id == id } }

    private fun listFor(c: Container): SnapshotStateList<HomeItem> = when (c) {
        Container.Dock -> dock
        is Container.Page -> { while (pages.size <= c.index) pages.add(mutableStateListOf()); pages[c.index] }
    }

    // ---------------------------------------------------------------- mutations

    fun ensureTrailingEmptyPage(): Int {
        if (pages.isEmpty() || pages.last().isNotEmpty()) pages.add(mutableStateListOf())
        return pages.lastIndex
    }

    private fun addToLastPage(item: HomeItem) {
        if (pages.isEmpty()) pages.add(mutableStateListOf())
        val last = pages.last()
        val (_, overflow) = packPage(last + item)
        if (overflow.isEmpty()) last += item else pages.add(mutableStateListOf(item))
    }

    /** Live move while dragging; [index] is a position in the target list (clamped). */
    fun move(id: String, target: Container, index: Int) {
        val (src, from) = locate(id) ?: return
        val item = listFor(src)[from]
        if (target == Container.Dock) {
            if (item is WidgetItem) return
            if (src != Container.Dock && dock.size >= 4) return
        }
        if (src == target) {
            val l = listFor(src)
            val to = index.coerceIn(0, l.lastIndex)
            if (to == from) return
            l.removeAt(from); l.add(to, item)
        } else {
            listFor(src).removeAt(from)
            val l = listFor(target)
            l.add(index.coerceIn(0, l.size), item)
        }
    }

    fun beginDrag(p: PendingDrag, currentPage: Int) {
        var item = p.item
        if (p.fromFolder != null && item is AppItem) {
            removeFromFolder(p.fromFolder, item.key)
            item = AppItem(newId(), item.key)
            listFor(Container.Page(currentPage)).add(item)
            openFolder = null
        }
        menu = null
        pending = null
        editMode = true
        drag = DragState(item, p.touchOffset, p.iconSizePx).also { it.pos = p.start }
    }

    fun drop() {
        val d = drag ?: return
        val target = folderHover
        if (target != null && target != d.item.id && d.item is AppItem) {
            val loc = locate(target)
            val srcLoc = locate(d.item.id)
            if (loc != null && srcLoc != null) {
                val tl = listFor(loc.first)
                val t = tl[loc.second]
                val merged: HomeItem? = when (t) {
                    is AppItem -> FolderItem(t.id, folderName(t.key, d.item.key), kotlin.collections.listOf(t.key, d.item.key))
                    is FolderItem -> t.copy(apps = t.apps + d.item.key)
                    else -> null
                }
                if (merged != null) {
                    tl[loc.second] = merged
                    listFor(srcLoc.first).removeAll { it.id == d.item.id }
                }
            }
        }
        folderHover = null
        drag = null
        normalize()
        save()
    }

    private fun folderName(a: AppKey, b: AppKey): String {
        val ca = repo.byKey[a]?.category
        val cb = repo.byKey[b]?.category
        return when {
            ca != null && ca == cb -> ca.title.substringBefore(" &")
            ca != null -> ca.title.substringBefore(" &")
            else -> "Folder"
        }
    }

    fun removeFromFolder(folderId: String, k: AppKey) {
        val (c, i) = locate(folderId) ?: return
        val l = listFor(c)
        val f = l[i] as? FolderItem ?: return
        val rest = f.apps - k
        l[i] = when (rest.size) {
            0 -> return run { l.removeAt(i); Unit }
            1 -> AppItem(f.id, rest[0])
            else -> f.copy(apps = rest)
        }
        save()
    }

    fun rename(folderId: String, name: String) {
        val (c, i) = locate(folderId) ?: return
        val l = listFor(c)
        (l[i] as? FolderItem)?.let { l[i] = it.copy(name = name.ifBlank { "Folder" }) }
        save()
    }

    fun remove(id: String) {
        val (c, i) = locate(id) ?: return
        listFor(c).removeAt(i)
        normalize(); save()
    }

    fun addToHome(k: AppKey) {
        if (!isOnHome(k)) addToLastPage(AppItem(newId(), k))
        save()
    }

    fun addWidget(kind: WidgetKind, size: WidgetSize, page: Int) {
        val l = listFor(Container.Page(page.coerceAtLeast(0)))
        l.add(0, WidgetItem(newId(), kind, size))
        normalize(); save()
    }

    fun resizeWidget(id: String, size: WidgetSize) {
        val (c, i) = locate(id) ?: return
        val l = listFor(c)
        (l[i] as? WidgetItem)?.let { l[i] = it.copy(size = size) }
        normalize(); save()
    }

    /** Spill overflow to following pages and drop empty pages (keeps at least one). */
    fun normalize() {
        var p = 0
        while (p < pages.size) {
            val (_, overflow) = packPage(pages[p])
            if (overflow.isNotEmpty()) {
                pages[p].removeAll { it in overflow }
                if (p + 1 >= pages.size) pages.add(mutableStateListOf())
                pages[p + 1].addAll(0, overflow)
            }
            p++
        }
        if (drag == null) {
            val it = pages.listIterator()
            while (it.hasNext()) if (it.next().isEmpty() && pages.size > 1) it.remove()
        }
        if (pages.isEmpty()) pages.add(mutableStateListOf())
        while (dock.size > 4) {
            val x = dock.removeAt(dock.lastIndex); addToLastPage(x)
        }
    }

    fun resetLayout() {
        file.delete()
        buildDefault(repo.apps.value)
        normalize(); save()
    }

    fun closeAllTransient() {
        menu = null; pending = null; openFolder = null; renameFolder = null
        if (drag != null) drop()
        editMode = false
    }

    // ---------------------------------------------------------------- persistence

    private class Snapshot(val pages: List<List<HomeItem>>, val dock: List<HomeItem>, val known: Set<String>)

    private fun apply(s: Snapshot) {
        pages.clear(); dock.clear(); known.clear()
        s.pages.forEach { pages += mutableStateListOf<HomeItem>().apply { addAll(it) } }
        dock.addAll(s.dock); known.addAll(s.known)
    }

    fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val json = JSONObject()
                .put("pages", JSONArray().apply { pages.forEach { p -> put(JSONArray().apply { p.forEach { put(enc(it)) } }) } })
                .put("dock", JSONArray().apply { dock.forEach { put(enc(it)) } })
                .put("known", JSONArray(known.toList()))
                .toString()
            withContext(Dispatchers.IO) {
                val tmp = File(file.parentFile, "layout.tmp")
                tmp.writeText(json); tmp.renameTo(file)
            }
        }
    }

    private fun enc(i: HomeItem): JSONObject = when (i) {
        is AppItem -> JSONObject().put("t", "a").put("id", i.id).put("k", i.key.raw)
        is FolderItem -> JSONObject().put("t", "f").put("id", i.id).put("n", i.name).put("a", JSONArray(i.apps.map { it.raw }))
        is WidgetItem -> JSONObject().put("t", "w").put("id", i.id).put("w", i.kind.name).put("s", i.size.name)
    }

    private fun dec(o: JSONObject): HomeItem? = when (o.optString("t")) {
        "a" -> AppItem(o.getString("id"), AppKey(o.getString("k")))
        "f" -> FolderItem(o.getString("id"), o.optString("n", "Folder"),
            (0 until o.getJSONArray("a").length()).map { AppKey(o.getJSONArray("a").getString(it)) })
        "w" -> runCatching { WidgetItem(o.getString("id"), WidgetKind.valueOf(o.getString("w")), WidgetSize.valueOf(o.getString("s"))) }.getOrNull()
        else -> null
    }

    private fun read(): Snapshot? {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val pa = o.getJSONArray("pages")
        val pages = (0 until pa.length()).map { p -> val a = pa.getJSONArray(p); (0 until a.length()).mapNotNull { dec(a.getJSONObject(it)) } }
        val da = o.getJSONArray("dock")
        val dock = (0 until da.length()).mapNotNull { dec(da.getJSONObject(it)) }
        val ka = o.optJSONArray("known") ?: JSONArray()
        return Snapshot(pages, dock, (0 until ka.length()).map { ka.getString(it) }.toSet())
    }
}
