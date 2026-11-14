package com.glass.launcher.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.GlassApp
import com.glass.launcher.data.AppCategory
import com.glass.launcher.data.AppEntry
import kotlinx.coroutines.launch

data class LibraryGroup(val title: String, val apps: List<AppEntry>)

fun libraryGroups(apps: List<AppEntry>): List<LibraryGroup> {
    val usage = GlassApp.instance.usage
    val out = ArrayList<LibraryGroup>()
    out += LibraryGroup("Suggestions", suggestedApps(4))
    out += LibraryGroup("Recently Added", apps.sortedByDescending { it.installTime }.take(8))
    val byCat = apps.groupBy { it.category }
    AppCategory.entries.mapNotNull { c -> byCat[c]?.let { c to it } }
        .sortedByDescending { (_, l) -> l.sumOf { usage.score(it.key.raw) } * 10 + l.size }
        .forEach { (c, l) -> out += LibraryGroup(c.title, l.sortedByDescending { usage.score(it.key.raw) }) }
    return out
}

/** iOS App Library: auto-categorised tiles + A–Z search list. */
@Composable
fun AppLibrary(onOpenGroup: (LibraryGroup) -> Unit, onLongPressApp: (AppEntry, androidx.compose.ui.geometry.Rect) -> Unit) {
    val app = GlassApp.instance
    val apps by app.repo.apps.collectAsState()
    val view = LocalView.current
    val bd = wallpaperBackdrop()
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    val groups = remember(apps, app.usage.version) { libraryGroups(apps) }
    fun launch(e: AppEntry) { app.repo.launch(e, view, null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(42.dp).glass(Ios.capsule, bd, tint = Color.White.copy(alpha = 0.12f), refraction = 0.5f).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(19.dp))
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    if (q.isEmpty()) Text("App Library", style = Ios.body.copy(fontSize = 17.sp, color = Color.White.copy(alpha = 0.6f)))
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = Ios.body.copy(fontSize = 17.sp), cursorBrush = SolidColor(Ios.Blue),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { if (it.isFocused) searching = true })
                }
                if (q.isNotEmpty()) Icon(Icons.Rounded.Cancel, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp).pressable({ q = "" }))
            }
            if (searching) Text("Cancel", style = Ios.body.copy(fontSize = 17.sp), modifier = Modifier.padding(start = 12.dp).pressable({
                q = ""; searching = false; fm.clearFocus(); kb?.hide()
            }))
        }

        if (searching) {
            AlphabetList(apps.filter { q.isBlank() || it.label.contains(q.trim(), true) }, onClick = { launch(it); searching = false; q = ""; fm.clearFocus() }, onLong = onLongPressApp)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it.title }) { g ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LibraryTile(g, bd, onApp = ::launch, onMore = { onOpenGroup(g) }, onLong = onLongPressApp)
                        Text(g.title, style = Ios.label.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryTile(g: LibraryGroup, bd: com.kyant.backdrop.Backdrop?, onApp: (AppEntry) -> Unit, onMore: () -> Unit, onLong: (AppEntry, androidx.compose.ui.geometry.Rect) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).glass(Ios.rr(30f), bd, tint = Color.White.copy(alpha = 0.12f), blurRadius = 16f, refraction = 0.6f).padding(13.dp)) {
        val cell = (maxWidth - 13.dp) / 2
        val big = if (g.apps.size <= 4) g.apps.take(4) else g.apps.take(3)
        val rest = if (g.apps.size <= 4) emptyList() else g.apps.drop(3).take(4)
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            for (r in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                for (c in 0 until 2) {
                    val i = r * 2 + c
                    val e = big.getOrNull(i)
                    when {
                        e != null -> LongPressIcon(e, cell, onApp, onLong)
                        i == 3 && rest.isNotEmpty() -> Box(Modifier.size(cell).pressable(onMore)) {
                            val m = (cell - 6.dp) / 2
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                rest.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { AppIconImage(it, m) } } }
                            }
                        }
                        else -> Spacer(Modifier.size(cell))
                    }
                }
            }
        }
    }
}

@Composable
private fun LongPressIcon(e: AppEntry, size: androidx.compose.ui.unit.Dp, onApp: (AppEntry) -> Unit, onLong: (AppEntry, androidx.compose.ui.geometry.Rect) -> Unit) {
    var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    AppIconImage(e, size, pressed = pressed, modifier = Modifier
        .onGloballyPositioned { coords = it }
        .pointerInput(e.key) {
            detectTapGestures(
                onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                onTap = { onApp(e) },
                onLongPress = { view.thud(); coords?.let { c -> onLong(e, c.boundsInRoot()) } },
            )
        })
}


@Composable
fun AlphabetList(list: List<AppEntry>, onClick: (AppEntry) -> Unit, onLong: (AppEntry, androidx.compose.ui.geometry.Rect) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val sections = remember(list) {
        list.groupBy { it.label.firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#' }.toSortedMap(compareBy { if (it == '#') '{' else it })
    }
    val indexOf = remember(sections) {
        var i = 0
        sections.map { (k, v) -> val at = i; i += v.size + 1; k to at }.toMap()
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 36.dp, bottom = 120.dp)) {
            sections.forEach { (letter, apps) ->
                item(key = "h$letter") {
                    Text(letter.toString(), style = Ios.headline.copy(color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(apps, key = { it.key.raw }) { e ->
                    Row(Modifier.fillMaxWidth().pressable({ onClick(e) }, scaleTo = 0.98f).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIconImage(e, 40.dp)
                        Text(e.label, style = Ios.body.copy(fontSize = 17.sp), modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
        }
        // Scrubbable A–Z index on the right edge
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
                .pointerInput(sections) {
                    fun jump(y: Float) {
                        val keys = sections.keys.toList()
                        if (keys.isEmpty()) return
                        val k = keys[((y / size.height) * keys.size).toInt().coerceIn(0, keys.lastIndex)]
                        scope.launch { state.scrollToItem(indexOf[k] ?: 0) }; view.tick()
                    }
                    detectVerticalDragGestures(onDragStart = { jump(it.y) }) { c, _ -> jump(c.position.y) }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sections.keys.forEach { k ->
                Text(k.toString(), color = Ios.Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.width(16.dp).pressable({ scope.launch { state.scrollToItem(indexOf[k] ?: 0) } }))
            }
        }
    }
}
