package com.glass.apps.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glass.apps.common.BarButton
import com.glass.apps.common.CellRow
import com.glass.apps.common.Divider
import com.glass.apps.common.IosTheme
import com.glass.apps.common.LargeTitle
import com.glass.apps.common.NavBar
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp")
private val videoExt = setOf("mp4", "mkv", "mov", "3gp", "webm", "avi")

fun iconFor(f: File): Pair<ImageVector, Color> = when {
    f.isDirectory -> Icons.Rounded.Folder to Color(0xFF1E88FF)
    f.extension.lowercase() == "pdf" -> Icons.Rounded.PictureAsPdf to Color(0xFFE53935)
    f.extension.lowercase() in imageExt -> Icons.Rounded.Image to Color(0xFF34C759)
    f.extension.lowercase() in videoExt -> Icons.Rounded.Movie to Color(0xFFAF52DE)
    f.extension.lowercase() in setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus") -> Icons.Rounded.AudioFile to Color(0xFFFF2D55)
    f.extension.lowercase() in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Rounded.FolderZip to Color(0xFF8E8E93)
    f.extension.lowercase() == "apk" -> Icons.Rounded.Android to Color(0xFF3DDC84)
    f.extension.lowercase() in setOf("doc", "docx", "txt", "md", "rtf", "odt") -> Icons.Rounded.Description to Color(0xFF0A84FF)
    f.extension.lowercase() in setOf("xls", "xlsx", "csv", "ppt", "pptx") -> Icons.Rounded.Article to Color(0xFF30D158)
    else -> Icons.Rounded.InsertDriveFile to Color(0xFF8E8E93)
}

class FilesActivity : ComponentActivity() {
    private var granted by mutableStateOf(false)
    override fun onResume() { super.onResume(); granted = Environment.isExternalStorageManager() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        granted = Environment.isExternalStorageManager()
        setContent {
            IosTheme {
                if (!granted) Screen {
                    Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt("Allow access to files", T.title2, align = TextAlign.Center)
                        Txt("Files needs “All files access” to browse your phone storage, like the Files app on iPhone.", T.callout, ios.label2, Modifier.padding(16.dp), align = TextAlign.Center)
                        Txt("Open Settings", T.headline, Color.White, Modifier.clip(rr(14f)).background(ios.blue).tap {
                            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                        }.padding(horizontal = 26.dp, vertical = 14.dp))
                    }
                } else FilesApp()
            }
        }
    }
}

@Composable
fun FilesApp() {
    var tab by remember { mutableIntStateOf(1) }
    val stack = remember { mutableStateListOf<File>() }
    BackHandler(stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    Screen(grouped = stack.isEmpty() && tab == 1) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (stack.isNotEmpty()) FolderScreen(stack.last(), parentName = if (stack.size > 1) stack[stack.size - 2].name.ifBlank { "Browse" } else "Browse",
                    onBack = { stack.removeAt(stack.lastIndex) }, onOpen = { stack.add(it) })
                else if (tab == 0) RecentsScreen()
                else BrowseScreen { stack.add(it) }
            }
            TabBar(listOf("Recents" to Icons.Rounded.Schedule, "Browse" to Icons.Rounded.Folder), tab) { tab = it; stack.clear() }
        }
    }
}

@Composable
private fun BrowseScreen(open: (File) -> Unit) {
    val root = Environment.getExternalStorageDirectory()
    val stat = remember { android.os.StatFs(root.path) }
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Browse") }
        item {
            Section("Locations") {
                CellRow("On My Phone", leading = { Icon(Icons.Rounded.PhoneAndroid, null, tint = ios.blue) }) { open(root) }
            }
        }
        item {
            Section("Favourites") {
                val favs = listOf(
                    Triple("Downloads", Icons.Rounded.Download, Environment.DIRECTORY_DOWNLOADS),
                    Triple("Documents", Icons.Rounded.Description, Environment.DIRECTORY_DOCUMENTS),
                    Triple("Camera", Icons.Rounded.CameraAlt, Environment.DIRECTORY_DCIM),
                    Triple("Pictures", Icons.Rounded.Image, Environment.DIRECTORY_PICTURES),
                    Triple("Music", Icons.Rounded.MusicNote, Environment.DIRECTORY_MUSIC),
                    Triple("Movies", Icons.Rounded.Movie, Environment.DIRECTORY_MOVIES),
                )
                favs.forEachIndexed { i, (n, ic, d) ->
                    if (i > 0) Divider(52.dp)
                    CellRow(n, leading = { Icon(ic, null, tint = ios.blue) }) { open(File(root, d)) }
                }
            }
        }
        item {
            Section("Storage", footer = "${Formatter.formatShortFileSize(ctx, stat.availableBytes)} available of ${Formatter.formatShortFileSize(ctx, stat.totalBytes)}") {
                val used = 1f - stat.availableBytes / stat.totalBytes.toFloat()
                Box(Modifier.padding(16.dp).fillMaxWidth().height(10.dp).clip(rr(5f)).background(ios.fill)) {
                    Box(Modifier.fillMaxWidth(used).height(10.dp).background(ios.blue))
                }
            }
        }
    }
}

@Composable
private fun RecentsScreen() {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns.DATA),
                    "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL", null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                    buildList { while (c.moveToNext() && size < 80) c.getString(0)?.let { val f = File(it); if (f.isFile) add(f) } }
                }
            }.getOrNull() ?: emptyList()
        }
    }
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Recents")
        FileGrid(files, grid = true, onOpen = { openFile(ctx, it) }, onLong = {}, refresh = {})
    }
}

fun openFile(ctx: android.content.Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "com.glass.apps.files", f)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun shareFile(ctx: android.content.Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "com.glass.apps.files", f)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) }
}

@Composable
private fun FolderScreen(dir: File, parentName: String, onBack: () -> Unit, onOpen: (File) -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var grid by remember { mutableStateOf(true) }
    var sort by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<File?>(null) }
    var rename by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    val list = remember(dir, version, sort, q) {
        (dir.listFiles()?.toList() ?: emptyList()).filter { !it.name.startsWith(".") && (q.isBlank() || it.name.contains(q, true)) }
            .sortedWith(when (sort) {
                1 -> compareByDescending<File> { it.lastModified() }
                2 -> compareByDescending { if (it.isDirectory) -1 else it.length() }
                else -> compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
            })
    }
    Column(Modifier.fillMaxSize().background(ios.bg)) {
        NavBar(if (dir == Environment.getExternalStorageDirectory()) "On My Phone" else dir.name, parentName, onBack) {
            BarButton(Icons.Rounded.CreateNewFolder) { newFolder = true }
            BarButton(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView) { grid = !grid }
        }
        SearchField(q, { q = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Segmented(listOf("Name", "Date", "Size"), sort, Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { sort = it }
        Txt("${list.size} items", T.footnote, ios.label2, Modifier.padding(16.dp, 8.dp))
        FileGrid(list, grid, onOpen = { if (it.isDirectory) onOpen(it) else openFile(ctx, it) }, onLong = { action = it }, refresh = { version++ })
    }
    action?.let { f ->
        ActionSheet(f.name, listOf(
            "Open" to { if (f.isDirectory) onOpen(f) else openFile(ctx, f) },
            "Share" to { shareFile(ctx, f) },
            "Rename" to { rename = f },
            "Duplicate" to { runCatching { val t = File(f.parentFile, f.nameWithoutExtension + " copy" + (if (f.extension.isNotEmpty()) "." + f.extension else "")); if (f.isDirectory) f.copyRecursively(t) else f.copyTo(t) }; version++ },
            "Delete" to { runCatching { f.deleteRecursively() }; version++ },
        ), destructiveLast = true) { action = null }
    }
    rename?.let { f -> TextPrompt("Rename", f.name, onCancel = { rename = null }) { n -> runCatching { f.renameTo(File(f.parentFile, n)) }; rename = null; version++ } }
    if (newFolder) TextPrompt("New Folder", "untitled folder", onCancel = { newFolder = false }) { n -> runCatching { File(dir, n).mkdirs() }; newFolder = false; version++ }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGrid(files: List<File>, grid: Boolean, onOpen: (File) -> Unit, onLong: (File) -> Unit, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val dateF = remember { SimpleDateFormat("d/M/yy", Locale.getDefault()) }
    @Composable fun Thumb(f: File, size: Dp) {
        val (ic, col) = iconFor(f)
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (f.extension.lowercase() in imageExt || f.extension.lowercase() in videoExt)
                AsyncImage(ImageRequest.Builder(ctx).data(f).size(256).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size * 0.86f).clip(rr(6f)))
            else Icon(ic, null, tint = col, modifier = Modifier.size(size))
        }
    }
    if (files.isEmpty()) { Txt("Folder is empty", T.callout, ios.label2, Modifier.fillMaxWidth().padding(top = 60.dp), align = TextAlign.Center); return }
    if (grid) LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(files, key = { it.path }) { f ->
            Column(Modifier.combinedClickable(onClick = { onOpen(f) }, onLongClick = { onLong(f) }), horizontalAlignment = Alignment.CenterHorizontally) {
                Thumb(f, 64.dp)
                Txt(f.name, T.caption, maxLines = 2, align = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                Txt(if (f.isDirectory) "${f.list()?.size ?: 0} items" else Formatter.formatShortFileSize(ctx, f.length()), T.caption2, ios.label2)
            }
        }
    } else LazyColumn {
        items(files, key = { it.path }) { f ->
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(f) }, onLongClick = { onLong(f) }).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Thumb(f, 40.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Txt(f.name, T.body, maxLines = 1)
                    Txt(dateF.format(Date(f.lastModified())) + if (f.isFile) " - " + Formatter.formatShortFileSize(ctx, f.length()) else "", T.footnote, ios.label2)
                }
            }
            Divider(68.dp)
        }
    }
}

/** iOS action sheet. */
@Composable
fun ActionSheet(title: String, actions: List<Pair<String, () -> Unit>>, destructiveLast: Boolean = false, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).tap(1f, dim = false, onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.navigationBarsPadding().padding(10.dp)) {
            Column(Modifier.fillMaxWidth().clip(rr(14f)).background(ios.cell)) {
                Txt(title, T.footnote, ios.label2, Modifier.fillMaxWidth().padding(14.dp), align = TextAlign.Center, maxLines = 1)
                actions.forEachIndexed { i, (n, a) ->
                    Divider(0.dp)
                    Txt(n, T.title3.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), if (destructiveLast && i == actions.lastIndex) ios.red else ios.blue,
                        Modifier.fillMaxWidth().tap(1f) { onDismiss(); a() }.padding(16.dp), align = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(8.dp))
            Txt("Cancel", T.title3, ios.blue, Modifier.fillMaxWidth().clip(rr(14f)).background(ios.cell).tap(1f, onClick = onDismiss).padding(16.dp), align = TextAlign.Center)
        }
    }
}

@Composable
fun TextPrompt(title: String, initial: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(280.dp).clip(rr(14f)).background(ios.cell)) {
            Txt(title, T.headline, modifier = Modifier.fillMaxWidth().padding(top = 18.dp), align = TextAlign.Center)
            BasicTextField(v, { v = it }, singleLine = true, textStyle = T.body.copy(color = ios.label),
                modifier = Modifier.padding(16.dp).fillMaxWidth().clip(rr(6f)).background(ios.fill).padding(8.dp))
            Divider(0.dp)
            Row {
                Txt("Cancel", T.body, ios.blue, Modifier.weight(1f).tap(1f, onClick = onCancel).padding(12.dp), align = TextAlign.Center)
                Txt("Save", T.headline, ios.blue, Modifier.weight(1f).tap(1f) { if (v.isNotBlank()) onOk(v.trim()) }.padding(12.dp), align = TextAlign.Center)
            }
        }
    }
}
