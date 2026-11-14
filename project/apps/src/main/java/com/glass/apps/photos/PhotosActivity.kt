package com.glass.apps.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glass.apps.common.IosTheme
import com.glass.apps.common.LargeTitle
import com.glass.apps.common.NavBar
import com.glass.apps.common.NeedsPermissions
import com.glass.apps.common.Screen
import com.glass.apps.common.SearchField
import com.glass.apps.common.Section
import com.glass.apps.common.CellRow
import com.glass.apps.common.Divider
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Media(
    val id: Long, val uri: Uri, val video: Boolean, val date: Long, val duration: Long,
    val bucket: String, val favorite: Boolean, val size: Long, val w: Int, val h: Int, val name: String,
)

data class Album(val name: String, val items: List<Media>)

object MediaRepo {
    suspend fun load(ctx: Context): List<Media> = withContext(Dispatchers.IO) {
        val out = ArrayList<Media>(4096)
        val uri = MediaStore.Files.getContentUri("external")
        val proj = mutableListOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= 30) proj += MediaStore.MediaColumns.IS_FAVORITE
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        runCatching {
            ctx.contentResolver.query(uri, proj.toTypedArray(), sel, null, "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val taken = c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000)
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out += Media(id, ContentUris.withAppendedId(base, id), video, taken, c.getLong(4), c.getString(5) ?: "Other",
                        if (proj.size > 10) c.getInt(10) == 1 else false, c.getLong(6), c.getInt(7), c.getInt(8), c.getString(9) ?: "")
                }
            }
        }
        out.sortBy { it.date }
        out
    }
}

private fun dayKey(t: Long): String = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(t))
private fun monthKey(t: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(t))
fun fmtDuration(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

class PhotosActivity : ComponentActivity() {
    private val all = mutableStateListOf<Media>()
    private var loaded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val external = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent {
            IosTheme {
                val perms = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (external != null) {
                    val m = Media(0, external, intent.type?.startsWith("video") == true, System.currentTimeMillis(), 0, "", false, 0, 0, 0, "")
                    Viewer(listOf(m), 0, onClose = { finish() }, onChanged = {})
                } else NeedsPermissions(perms, "Allow access to your photos", "Photos shows the pictures and videos stored on this phone. Nothing leaves your device.") {
                    LaunchedEffect(Unit) { reload() }
                    PhotosApp(all, loaded) { lifecycleScope.launch { reload() } }
                }
            }
        }
    }

    private suspend fun reload() {
        val list = MediaRepo.load(this)
        all.clear(); all.addAll(list); loaded = true
    }

    override fun onResume() {
        super.onResume()
        if (loaded) lifecycleScope.launch { reload() }
    }
}

@Composable
fun PhotosApp(all: List<Media>, loaded: Boolean, reload: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<Pair<List<Media>, Int>?>(null) }
    var album by remember { mutableStateOf<Album?>(null) }
    BackHandler(viewer != null || album != null) { if (viewer != null) viewer = null else album = null }
    Screen {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val open: (List<Media>, Int) -> Unit = { l, i -> viewer = l to i }
                when {
                    album != null -> AlbumScreen(album!!, onBack = { album = null }, open = open)
                    tab == 0 -> LibraryScreen(all, loaded, open)
                    tab == 1 -> ForYouScreen(all, open)
                    tab == 2 -> AlbumsScreen(all) { album = it }
                    else -> SearchScreen(all, open) { album = it }
                }
            }
            TabBar(
                listOf("Library" to Icons.Rounded.PhotoLibrary, "For You" to Icons.Rounded.AutoAwesome, "Albums" to Icons.Rounded.Collections, "Search" to Icons.Rounded.Search),
                tab,
            ) { tab = it; album = null }
        }
        AnimatedVisibility(viewer != null, enter = fadeIn(), exit = fadeOut()) {
            val v = viewer
            if (v != null) Viewer(v.first, v.second, onClose = { viewer = null }, onChanged = reload)
        }
    }
}

// ------------------------------------------------------------------------------------------ grid

@Composable
fun MediaThumb(m: Media, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Box(modifier.aspectRatio(1f).background(ios.fill).tap(0.97f, dim = true, onClick = onClick)) {
        AsyncImage(ImageRequest.Builder(ctx).data(m.uri).size(320).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (m.video) Txt(fmtDuration(m.duration), T.caption2.copy(fontWeight = FontWeight.SemiBold), Color.White,
            Modifier.align(Alignment.BottomEnd).padding(4.dp))
        if (m.favorite) Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(13.dp))
    }
}

/** Pinch the grid to change density (like iOS). */
private fun Modifier.pinchColumns(cols: Int, options: List<Int>, set: (Int) -> Unit) = pointerInput(cols) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        do {
            val ev = awaitPointerEvent()
            if (ev.changes.size >= 2) {
                zoom *= ev.calculateZoom()
                val i = options.indexOf(cols)
                if (zoom > 1.3f && i > 0) { set(options[i - 1]); zoom = 1f }
                if (zoom < 0.75f && i < options.lastIndex) { set(options[i + 1]); zoom = 1f }
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (ev.changes.any { it.pressed })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(all: List<Media>, loaded: Boolean, open: (List<Media>, Int) -> Unit) {
    var mode by remember { mutableIntStateOf(2) } // 0 Years 1 Months 2 All
    var cols by remember { mutableIntStateOf(3) }
    val state = rememberLazyGridState()
    LaunchedEffect(all.size, mode) { if (all.isNotEmpty()) state.scrollToItem(Int.MAX_VALUE.coerceAtMost(all.size + 400)) }
    Box(Modifier.fillMaxSize()) {
        when (mode) {
            0 -> YearsView(all) { mode = 1 }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(if (mode == 1) cols.coerceAtLeast(3) else cols),
                state = state,
                modifier = Modifier.fillMaxSize().pinchColumns(cols, listOf(1, 3, 5, 7)) { cols = it },
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                verticalArrangement = Arrangement.spacedBy(1.5.dp),
                contentPadding = PaddingValues(top = 110.dp, bottom = 70.dp),
            ) {
                if (mode == 1) {
                    all.withIndex().groupBy { monthKey(it.value.date) }.forEach { (k, items) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h$k") {
                            Txt(k, T.title3, modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 8.dp))
                        }
                        items(items, key = { it.value.id }) { (i, m) -> MediaThumb(m, Modifier) { open(all, i) } }
                    }
                } else {
                    items(all.size, key = { all[it].id }) { i -> MediaThumb(all[i], Modifier) { open(all, i) } }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val p = all.count { !it.video }; val v = all.count { it.video }
                    Txt(if (!loaded) "Loading…" else "$p Photos, $v Videos", T.footnote.copy(fontWeight = FontWeight.SemiBold), ios.label,
                        Modifier.fillMaxWidth().padding(vertical = 20.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        // header over the grid
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(ios.bg, ios.bg.copy(alpha = 0.85f), Color.Transparent)))) {
            LargeTitle("Library")
        }
        Segmented(listOf("Years", "Months", "All Photos"), mode,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).width(280.dp)) { mode = it }
    }
}

@Composable
private fun YearsView(all: List<Media>, onYear: () -> Unit) {
    val years = all.groupBy { Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.YEAR) }.toSortedMap()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 110.dp, bottom = 80.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(years.entries.toList()) { (y, list) ->
            val ctx = LocalContext.current
            Box(Modifier.fillMaxWidth().aspectRatio(1.4f).clip(rr(18f)).tap(onClick = onYear)) {
                AsyncImage(ImageRequest.Builder(ctx).data(list.last().uri).size(900).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Transparent))))
                Txt("$y", T.title1, Color.White, Modifier.padding(16.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ For You

@Composable
fun ForYouScreen(all: List<Media>, open: (List<Media>, Int) -> Unit) {
    val ctx = LocalContext.current
    val today = Calendar.getInstance()
    val onThisDay = remember(all.size) {
        all.filter { val c = Calendar.getInstance().apply { timeInMillis = it.date }
            c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) != today.get(Calendar.YEAR) }
    }
    val recentMonth = remember(all.size) { all.filter { System.currentTimeMillis() - it.date < 30L * 86_400_000 && !it.video } }
    val featured = remember(all.size) { all.filter { !it.video }.shuffled().take(12) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { LargeTitle("For You") }
        item { Txt("Memories", T.title2, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 10.dp)) }
        val memories = listOfNotNull(
            onThisDay.takeIf { it.isNotEmpty() }?.let { "On This Day" to it },
            recentMonth.takeIf { it.size > 3 }?.let { "This Month" to it },
            featured.takeIf { it.isNotEmpty() }?.let { "Featured Photos" to it },
        )
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(memories) { (title, list) ->
                    Box(Modifier.width(300.dp).aspectRatio(0.8f).clip(rr(18f)).tap { open(list, 0) }) {
                        AsyncImage(ImageRequest.Builder(ctx).data(list.first().uri).size(900).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                            Txt(title, T.title2, Color.White)
                            Txt("${list.size} items", T.subhead, Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
        if (memories.isEmpty()) item { Txt("Memories will appear as you take more photos.", T.callout, ios.label2, Modifier.padding(16.dp)) }
        item { Txt("Recent Highlights", T.title2, modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 10.dp)) }
        item {
            val hl = all.takeLast(9).reversed()
            Column(Modifier.padding(horizontal = 16.dp).clip(rr(16f)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                hl.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { row.forEach { m -> MediaThumb(m, Modifier.weight(1f)) { open(hl, hl.indexOf(m)) } } }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Albums

fun albumsOf(all: List<Media>): List<Album> {
    val buckets = all.groupBy { it.bucket }.map { (k, v) -> Album(k, v) }.sortedByDescending { it.items.size }
    val cam = buckets.firstOrNull { it.name.equals("Camera", true) }
    return listOfNotNull(Album("Recents", all), cam, Album("Favourites", all.filter { it.favorite })) + buckets.filter { it !== cam }
}

@Composable
fun AlbumsScreen(all: List<Media>, onAlbum: (Album) -> Unit) {
    val ctx = LocalContext.current
    val albums = remember(all.size) { albumsOf(all) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { LargeTitle("Albums") }
        item { Txt("My Albums", T.title2, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 10.dp)) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(albums.chunked(2)) { pair ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { a ->
                            Column(Modifier.width(170.dp).tap { onAlbum(a) }) {
                                Box(Modifier.size(170.dp).clip(rr(10f)).background(ios.fill)) {
                                    a.items.lastOrNull()?.let { AsyncImage(ImageRequest.Builder(ctx).data(it.uri).size(500).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                }
                                Txt(a.name, T.subhead, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                                Txt("${a.items.size}", T.subhead, ios.label2)
                            }
                        }
                    }
                }
            }
        }
        item {
            Section("Media Types") {
                val types = listOf(
                    Triple("Videos", Icons.Rounded.Videocam, all.filter { it.video }),
                    Triple("Screenshots", Icons.Rounded.Screenshot, all.filter { it.bucket.contains("Screenshot", true) }),
                    Triple("Camera", Icons.Rounded.CameraAlt, all.filter { it.bucket.equals("Camera", true) }),
                    Triple("Favourites", Icons.Rounded.Favorite, all.filter { it.favorite }),
                )
                types.forEachIndexed { i, (n, ic, l) ->
                    if (i > 0) Divider(52.dp)
                    CellRow(n, "${l.size}", leading = { Icon(ic, null, tint = ios.blue, modifier = Modifier.size(24.dp)) }) { onAlbum(Album(n, l)) }
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(a: Album, onBack: () -> Unit, open: (List<Media>, Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        NavBar(a.name, "Albums", onBack)
        val state = rememberLazyGridState()
        LaunchedEffect(a) { if (a.items.isNotEmpty()) state.scrollToItem(a.items.lastIndex) }
        LazyVerticalGrid(GridCells.Fixed(3), state = state, horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
            items(a.items.size, key = { a.items[it].id }) { i -> MediaThumb(a.items[i], Modifier) { open(a.items, i) } }
        }
    }
}

// ------------------------------------------------------------------------------------------ Search

@Composable
fun SearchScreen(all: List<Media>, open: (List<Media>, Int) -> Unit, onAlbum: (Album) -> Unit) {
    var q by remember { mutableStateOf("") }
    val albums = remember(all.size) { albumsOf(all) }
    val results = remember(q, all.size) {
        if (q.isBlank()) emptyList() else all.filter { monthKey(it.date).contains(q, true) || dayKey(it.date).contains(q, true) || it.bucket.contains(q, true) || it.name.contains(q, true) }
    }
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Search")
        SearchField(q, { q = it }, Modifier.padding(horizontal = 16.dp), "Places, months, albums…")
        if (q.isBlank()) {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                item { Section("Albums") { albums.take(8).forEachIndexed { i, a -> if (i > 0) Divider(); CellRow(a.name, "${a.items.size}") { onAlbum(a) } } } }
            }
        } else {
            Txt("${results.size} results", T.footnote, ios.label2, Modifier.padding(16.dp))
            LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                items(results.size) { i -> MediaThumb(results[i], Modifier) { open(results, i) } }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Viewer

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(list: List<Media>, start: Int, onClose: () -> Unit, onChanged: () -> Unit) {
    if (list.isEmpty()) { onClose(); return }
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.lastIndex)) { list.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val req = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onChanged() }
    val cur = list[pager.currentPage.coerceIn(0, list.lastIndex)]
    var fav by remember(cur.id) { mutableStateOf(cur.favorite) }
    BackHandler { if (info) info = false else onClose() }
    val bg by androidx.compose.animation.animateColorAsState(if (chrome) ios.bg else Color.Black, label = "vbg")

    Box(Modifier.fillMaxSize().background(bg)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { list[it].uri.toString() }) { page ->
            val m = list[page]
            if (m.video) VideoPage(m, page == pager.currentPage) { chrome = !chrome }
            else ZoomImage(m) { chrome = !chrome }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().background(ios.bar)) {
                    Box(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp)) {
                        Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { com.glass.apps.common.BackButton("") { onClose() } }
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Txt(if (DateUtils.isToday(cur.date)) "Today" else SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(cur.date)), T.headline.copy(fontSize = 15.sp))
                            Txt(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(cur.date)), T.caption, ios.label2)
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(ios.bar).navigationBarsPadding()) {
                    // thumbnail scrubber
                    val strip = rememberLazyListState()
                    LaunchedEffect(pager.currentPage) { strip.animateScrollToItem((pager.currentPage - 6).coerceAtLeast(0)) }
                    LazyRow(state = strip, modifier = Modifier.fillMaxWidth().height(42.dp).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(1.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        itemsIndexed(list, key = { _, m -> m.uri.toString() }) { i, m ->
                            Box(Modifier.width(if (i == pager.currentPage) 46.dp else 26.dp).fillMaxSize().clip(RoundedCornerShape(2.dp)).tap(1f) { scope.launch { pager.scrollToPage(i) } }) {
                                AsyncImage(ImageRequest.Builder(ctx).data(m.uri).size(120).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.IosShare, null, tint = ios.blue, modifier = Modifier.size(26.dp).tap {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (cur.video) "video/*" else "image/*")
                                .putExtra(Intent.EXTRA_STREAM, cur.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        })
                        Icon(if (fav) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder, null, tint = ios.blue, modifier = Modifier.size(26.dp).tap {
                            if (Build.VERSION.SDK_INT >= 30 && cur.id != 0L) runCatching {
                                val pi = MediaStore.createFavoriteRequest(ctx.contentResolver, listOf(cur.uri), !fav)
                                req.launch(IntentSenderRequest.Builder(pi.intentSender).build()); fav = !fav
                            }
                        })
                        Icon(Icons.Outlined.Info, null, tint = ios.blue, modifier = Modifier.size(26.dp).tap { info = !info })
                        Icon(Icons.Outlined.Delete, null, tint = ios.blue, modifier = Modifier.size(26.dp).tap {
                            if (Build.VERSION.SDK_INT >= 30 && cur.id != 0L) runCatching {
                                val pi = MediaStore.createTrashRequest(ctx.contentResolver, listOf(cur.uri), true)
                                req.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                            }
                        })
                    }
                }
                if (info) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp).padding(horizontal = 12.dp).fillMaxWidth()
                    .clip(rr(16f)).background(ios.cell).padding(16.dp)) {
                    Txt(SimpleDateFormat("EEEE • d MMM yyyy • h:mm a", Locale.getDefault()).format(Date(cur.date)), T.headline)
                    Txt(cur.name, T.subhead, ios.label2, Modifier.padding(top = 6.dp))
                    Txt("${cur.w} × ${cur.h} • ${Formatter.formatShortFileSize(ctx, cur.size)} • ${cur.bucket}", T.subhead, ios.label2)
                }
            }
        }
    }
}

@Composable
private fun ZoomImage(m: Media, onTap: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val anim by animateFloatAsState(scale, label = "z")
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(m.id) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { p ->
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f }
                })
            }
            .pointerInput(m.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val multi = ev.changes.size >= 2
                        if (multi || scale > 1.01f) {
                            val z = ev.calculateZoom()
                            scale = (scale * z).coerceIn(1f, 6f)
                            val pan = ev.calculatePan()
                            val maxX = size.width * (scale - 1) / 2; val maxY = size.height * (scale - 1) / 2
                            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            if (scale <= 1.01f) offset = Offset.Zero
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            ImageRequest.Builder(ctx).data(m.uri).size(2400).build(), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = anim; scaleY = anim; translationX = offset.x; translationY = offset.y },
        )
    }
}

@Composable
private fun VideoPage(m: Media, active: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    if (!active) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(ImageRequest.Builder(ctx).data(m.uri).size(1200).build(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
        return
    }
    val player = remember { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(m.uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        Modifier.fillMaxSize().padding(vertical = 60.dp))
}
