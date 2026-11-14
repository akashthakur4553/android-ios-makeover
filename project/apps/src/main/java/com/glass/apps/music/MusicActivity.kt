package com.glass.apps.music

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.glass.apps.common.CellRow
import com.glass.apps.common.Divider
import com.glass.apps.common.IosTheme
import com.glass.apps.common.LargeTitle
import com.glass.apps.common.NavBar
import com.glass.apps.common.NeedsPermissions
import com.glass.apps.common.Screen
import com.glass.apps.common.SearchField
import com.glass.apps.common.Section
import com.glass.apps.common.T
import com.glass.apps.common.TabBar
import com.glass.apps.common.Txt
import com.glass.apps.common.ios
import com.glass.apps.common.rr
import com.glass.apps.common.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class Song(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val duration: Long) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val art: Uri get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
}

object MusicRepo {
    suspend fun songs(ctx: Context): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION),
                "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>30000", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                buildList { while (c.moveToNext()) add(Song(c.getLong(0), c.getString(1) ?: "Unknown", c.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown Artist",
                    c.getString(3) ?: "", c.getLong(4), c.getLong(5))) }
            }
        }.getOrNull() ?: emptyList()
    }
}

private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

class MusicActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, MusicService::class.java))).buildAsync()
        f.addListener({ runCatching { controller = f.get() } }, ContextCompat.getMainExecutor(this))
        setContent {
            IosTheme {
                val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                NeedsPermissions(listOf(perm), "Allow access to your music", "Music plays the songs stored on this phone.") { MusicApp(controller) }
            }
        }
    }

    override fun onDestroy() { controller?.release(); super.onDestroy() }
}

/** Snapshot of the player, refreshed by a listener + a small position ticker. */
class PlayerState {
    var playing by mutableStateOf(false)
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf("")
    var art by mutableStateOf<Uri?>(null)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    fun read(p: Player) {
        playing = p.isPlaying
        val md = p.mediaMetadata
        title = md.title?.toString(); artist = md.artist?.toString() ?: ""; art = md.artworkUri
        pos = p.currentPosition; dur = p.duration.coerceAtLeast(0); shuffle = p.shuffleModeEnabled; repeat = p.repeatMode
    }
}

@Composable
fun MusicApp(controller: MediaController?) {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    LaunchedEffect(Unit) { songs = MusicRepo.songs(ctx) }
    val st = remember { PlayerState() }
    DisposableEffect(controller) {
        val l = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = st.read(player) }
        controller?.addListener(l); controller?.let { st.read(it) }
        onDispose { controller?.removeListener(l) }
    }
    LaunchedEffect(controller, st.playing) { while (controller != null && st.playing) { st.pos = controller.currentPosition; delay(500) } }
    var tab by remember { mutableIntStateOf(0) }
    var full by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    BackHandler(full || page != null) { if (full) full = false else page = null }

    fun play(list: List<Song>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(list.map { s ->
            MediaItem.Builder().setUri(s.uri).setMediaId("${s.id}")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).setArtworkUri(s.art).build()).build()
        }, index, 0)
        c.prepare(); c.play()
    }

    Screen {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val p = page
                if (p != null) SongList(p.first, p.second, back = "Library", onBack = { page = null }, play = ::play)
                else when (tab) {
                    0 -> Library(songs, onOpen = { t, l -> page = t to l }, play = ::play)
                    else -> SearchSongs(songs, ::play)
                }
            }
            if (st.title != null) MiniPlayer(st, controller) { full = true }
            TabBar(listOf("Library" to Icons.Rounded.LibraryMusic, "Search" to Icons.Rounded.Search), tab) { tab = it; page = null }
        }
        AnimatedVisibility(full, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            NowPlaying(st, controller) { full = false }
        }
    }
}

@Composable
private fun Library(songs: List<Song>, onOpen: (String, List<Song>) -> Unit, play: (List<Song>, Int) -> Unit) {
    val ctx = LocalContext.current
    val albums = remember(songs) { songs.groupBy { it.albumId }.values.sortedBy { it.first().album.lowercase() } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Library") }
        item {
            Section {
                CellRow("Songs", "${songs.size}", color = ios.pink, leading = { Icon(Icons.Rounded.MusicNote, null, tint = ios.pink) }) { onOpen("Songs", songs) }
                Divider(52.dp)
                CellRow("Artists", null, color = ios.pink, leading = { Icon(Icons.Rounded.Person, null, tint = ios.pink) }) {
                    onOpen("Artists", songs.sortedBy { it.artist.lowercase() })
                }
                Divider(52.dp)
                CellRow("Albums", "${albums.size}", color = ios.pink, leading = { Icon(Icons.Rounded.Album, null, tint = ios.pink) }) {
                    onOpen("Albums", songs.sortedBy { it.album.lowercase() })
                }
            }
        }
        item { Txt("Recently Added", T.title2, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 10.dp)) }
        if (songs.isEmpty()) item {
            Txt("No songs on this phone yet. Copy MP3/FLAC files to the Music folder and they'll appear here.", T.callout, ios.label2, Modifier.padding(16.dp))
        }
        items(albums.take(30).chunked(2)) { row ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { al ->
                    Column(Modifier.weight(1f).tap { onOpen(al.first().album.ifBlank { "Album" }, al) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(rr(8f)).background(ios.fill), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.MusicNote, null, tint = ios.label3, modifier = Modifier.size(48.dp))
                            AsyncImage(al.first().art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Txt(al.first().album.ifBlank { "Unknown Album" }, T.subhead, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        Txt(al.first().artist, T.subhead, ios.label2, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun SongRow(s: Song, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(1f, onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(rr(6f)).background(ios.fill), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = ios.label3)
            AsyncImage(s.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Txt(s.title, T.body, maxLines = 1); Txt(s.artist, T.subhead, ios.label2, maxLines = 1)
        }
    }
}

@Composable
private fun SongList(title: String, songs: List<Song>, back: String, onBack: () -> Unit, play: (List<Song>, Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        NavBar(title, back, onBack)
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f).clip(rr(10f)).background(ios.fill).tap { play(songs, 0) }.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = ios.pink); Txt("Play", T.headline, ios.pink)
                    }
                    Row(Modifier.weight(1f).clip(rr(10f)).background(ios.fill).tap { val s = songs.shuffled(); play(s, 0) }.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Shuffle, null, tint = ios.pink); Txt("Shuffle", T.headline, ios.pink)
                    }
                }
            }
            items(songs.size) { i -> SongRow(songs[i]) { play(songs, i) }; Divider(76.dp) }
        }
    }
}

@Composable
private fun SearchSongs(songs: List<Song>, play: (List<Song>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    val res = remember(q, songs) { if (q.isBlank()) emptyList() else songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) } }
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Search")
        SearchField(q, { q = it }, Modifier.padding(horizontal = 16.dp), "Artists, Songs, Albums")
        LazyColumn(contentPadding = PaddingValues(top = 8.dp)) { items(res.size) { i -> SongRow(res[i]) { play(res, i) } } }
    }
}

@Composable
private fun MiniPlayer(st: PlayerState, c: MediaController?, onOpen: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth().height(60.dp).shadow(10.dp, rr(14f)).clip(rr(14f)).background(ios.cell)
        .tap(0.98f, onClick = onOpen).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(rr(6f)).background(ios.fill)) { AsyncImage(st.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Txt(st.title ?: "", T.body, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
        Icon(if (st.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = ios.label, modifier = Modifier.size(34.dp).tap { c?.let { if (it.isPlaying) it.pause() else it.play() } })
        Icon(Icons.Rounded.FastForward, null, tint = ios.label, modifier = Modifier.padding(start = 10.dp).size(32.dp).tap { c?.seekToNextMediaItem() })
    }
}

@Composable
private fun NowPlaying(st: PlayerState, c: MediaController?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC) / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val artScale by animateFloatAsState(if (st.playing) 1f else 0.8f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f), label = "art")
    var drag by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = drag.coerceAtLeast(0f) }.background(Color(0xFF2A2A2E))
        .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (drag > 220) onClose(); drag = 0f }) { ch, d -> ch.consume(); drag += d } }) {
        AsyncImage(st.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(90.dp).graphicsLayer { alpha = 0.85f })
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp)) {
            Box(Modifier.padding(top = 8.dp).size(40.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.5f)).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(36.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = artScale; scaleY = artScale }.shadow(30.dp, rr(14f)).clip(rr(14f)).background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(100.dp))
                AsyncImage(st.art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(34.dp))
            Txt(st.title ?: "Not Playing", T.title3, Color.White, maxLines = 1)
            Txt(st.artist, T.title3.copy(fontWeight = FontWeight.Normal), Color.White.copy(alpha = 0.6f), maxLines = 1)
            Spacer(Modifier.height(22.dp))
            Scrubber(if (st.dur > 0) st.pos / st.dur.toFloat() else 0f) { f -> c?.seekTo((f * st.dur).toLong()); st.pos = (f * st.dur).toLong() }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Txt(fmt(st.pos), T.caption, Color.White.copy(alpha = 0.6f)); Spacer(Modifier.weight(1f)); Txt("-" + fmt(st.dur - st.pos), T.caption, Color.White.copy(alpha = 0.6f))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(52.dp).tap { c?.seekToPreviousMediaItem() })
                Icon(if (st.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(70.dp).tap { c?.let { if (it.isPlaying) it.pause() else it.play() } })
                Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(52.dp).tap { c?.seekToNextMediaItem() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VolumeDown, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) { Scrubber(vol) { vol = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, (it * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0) } }
                Icon(Icons.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Icon(Icons.Rounded.Shuffle, null, tint = if (st.shuffle) Color.White else Color.White.copy(alpha = 0.45f), modifier = Modifier.size(26.dp).tap {
                    c?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                })
                Icon(if (st.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null,
                    tint = if (st.repeat != Player.REPEAT_MODE_OFF) Color.White else Color.White.copy(alpha = 0.45f), modifier = Modifier.size(26.dp).tap {
                        c?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
                    })
            }
        }
    }
}

/** Thin iOS scrubber that thickens while dragging. */
@Composable
fun Scrubber(value: Float, onChange: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) local else value
    val h by animateFloatAsState(if (dragging) 12f else 6f, label = "h")
    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp)
        .pointerInput(Unit) { detectTapGestures { o -> onChange((o.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { o -> dragging = true; local = (o.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { dragging = false; onChange(local) }, onDragCancel = { dragging = false }) { ch, _ ->
                ch.consume(); local = (ch.position.x / size.width).coerceIn(0f, 1f)
                if (!dragging) onChange(local)
            }
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(h.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(h.dp).background(Color.White.copy(alpha = if (dragging) 1f else 0.75f)))
        }
    }
}
