package com.glass.launcher.ui

import android.content.pm.ShortcutInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.glass.launcher.GlassApp
import com.glass.launcher.data.AppEntry
import com.glass.launcher.data.AppItem
import com.glass.launcher.data.FolderItem
import com.glass.launcher.data.HomeItem
import com.glass.launcher.data.PendingDrag
import com.glass.launcher.data.WidgetItem
import com.glass.launcher.data.WidgetKind
import com.glass.launcher.data.WidgetSize
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------------------ UI state

enum class MenuSource { Home, Dock, Folder, Library }

class MenuSpec(val item: HomeItem, val anchor: Rect, val source: MenuSource, val folderId: String? = null)

data class AlertButton(val label: String, val destructive: Boolean = false, val cancel: Boolean = false, val action: () -> Unit = {})
data class AlertSpec(val title: String, val message: String?, val buttons: List<AlertButton>)

class HomeUi {
    var menu by mutableStateOf<MenuSpec?>(null)
    var alert by mutableStateOf<AlertSpec?>(null)
    var gallery by mutableStateOf(false)
    var group by mutableStateOf<LibraryGroup?>(null)
    var renaming by mutableStateOf<String?>(null)
}

/** Keeps the last non-null value around so exit animations have something to draw. */
@Composable
fun <T : Any> rememberLast(v: T?): T? {
    var last by remember { mutableStateOf(v) }
    if (v != null) last = v
    return last
}

// ------------------------------------------------------------------------------------------ Context menu

private class MenuRow(val label: String, val icon: ImageVector?, val image: ImageBitmap? = null, val destructive: Boolean = false, val action: () -> Unit)

@Composable
fun ContextMenuLayer(ui: HomeUi, backdrop: Backdrop?, onRequestRemove: (MenuSpec) -> Unit, onEditHome: () -> Unit) {
    val app = GlassApp.instance
    val store = app.home
    val view = LocalView.current
    val p by animateFloatAsState(if (ui.menu != null) 1f else 0f, Ios.bouncy(), label = "menu")
    val spec = rememberLast(ui.menu) ?: return
    if (p <= 0.01f && ui.menu == null) return
    val close = { ui.menu = null }
    val entry: AppEntry? = (spec.item as? AppItem)?.let { app.repo.byKey[it.key] }
    var shortcuts by remember(spec) { mutableStateOf<List<Pair<ShortcutInfo, ImageBitmap?>>>(emptyList()) }
    LaunchedEffect(spec) {
        if (entry != null) shortcuts = withContext(Dispatchers.Default) { app.repo.shortcuts(entry).map { it to app.repo.shortcutIcon(it) } }
    }

    val rows = ArrayList<MenuRow>()
    when (val it = spec.item) {
        is AppItem -> if (entry != null) {
            if (spec.source == MenuSource.Library && !store.isOnHome(entry.key)) rows += MenuRow("Add to Home Screen", Icons.Rounded.AddBox) { store.addToHome(entry.key); close() }
            shortcuts.forEach { (s, icon) -> rows += MenuRow((s.shortLabel ?: s.longLabel ?: "").toString(), null, icon) { app.repo.startShortcut(s, spec.anchor.toAndroid()); close() } }
            if (spec.source != MenuSource.Library) rows += MenuRow("Edit Home Screen", Icons.Rounded.Apps) { close(); onEditHome() }
            rows += MenuRow("App Info", Icons.Rounded.Info) { app.repo.appInfo(entry); close() }
            if (spec.source == MenuSource.Library) {
                if (app.repo.canUninstall(entry)) rows += MenuRow("Delete App", Icons.Rounded.DeleteOutline, destructive = true) { app.repo.uninstall(entry); close() }
            } else rows += MenuRow("Remove App", Icons.Rounded.RemoveCircleOutline, destructive = true) { close(); onRequestRemove(spec) }
        }
        is FolderItem -> {
            rows += MenuRow("Rename", Icons.Rounded.Edit) { close(); store.openFolder = com.glass.launcher.data.OpenFolder(it.id, spec.anchor); store.renameFolder = it.id }
            rows += MenuRow("Edit Home Screen", Icons.Rounded.Apps) { close(); onEditHome() }
        }
        is WidgetItem -> {
            WidgetSize.entries.filter { s -> s != it.size }.forEach { s -> rows += MenuRow(s.title, Icons.Rounded.Widgets) { store.resizeWidget(it.id, s); close() } }
            rows += MenuRow("Edit Home Screen", Icons.Rounded.Apps) { close(); onEditHome() }
            rows += MenuRow("Remove Widget", Icons.Rounded.RemoveCircleOutline, destructive = true) { close(); onRequestRemove(spec) }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f * p))
            .pointerInput(Unit) { detectTapGestures { close() } },
    ) {
        val d = LocalDensity.current
        val a = spec.anchor
        val screenH = with(d) { maxHeight.toPx() }
        val screenW = with(d) { maxWidth.toPx() }
        val menuW = with(d) { 254.dp.toPx() }
        val menuH = with(d) { (rows.size * 46 + 4).dp.toPx() }
        val gap = with(d) { 12.dp.toPx() }
        val below = a.bottom + gap + menuH < screenH - with(d) { 60.dp.toPx() }
        val mx = (if (a.center.x > screenW / 2) a.right - menuW else a.left).coerceIn(gap, screenW - menuW - gap)
        val my = if (below) a.bottom + gap else (a.top - gap - menuH).coerceAtLeast(gap * 4)

        // The lifted item
        Box(
            Modifier
                .offset { IntOffset(a.left.roundToInt(), a.top.roundToInt()) }
                .size(with(d) { a.width.toDp() }, with(d) { a.height.toDp() })
                .graphicsLayer { val s = 1f + 0.07f * p; scaleX = s; scaleY = s },
        ) {
            when (val it = spec.item) {
                is AppItem -> AppIconImage(app.repo.byKey[it.key], with(d) { a.width.toDp() })
                is FolderItem -> FolderIconView(it, with(d) { a.width.toDp() }, wallpaperBackdrop())
                is WidgetItem -> WidgetView(it.kind, it.size, Modifier.fillMaxSize())
            }
        }
        // The menu
        Column(
            Modifier
                .offset { IntOffset(mx.roundToInt(), my.roundToInt()) }
                .width(254.dp)
                .graphicsLayer {
                    val s = lerp(0.4f, 1f, p); scaleX = s; scaleY = s; alpha = p.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(if (a.center.x > screenW / 2) 1f else 0f, if (below) 0f else 1f)
                }
                .glass(Ios.rr(16f), backdrop, tint = Color(0xFF2C2C2E).copy(alpha = 0.55f), blurRadius = 26f, refraction = 0.5f)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            rows.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(if (rows[i - 1].icon == Icons.Rounded.Apps || (i > 0 && rows[i - 1].image != null && r.image == null)) 6.dp else 0.5.dp)
                    .background(if (rows[i - 1].image != null && r.image == null) Color.Black.copy(alpha = 0.25f) else Ios.Separator))
                Row(
                    Modifier.fillMaxWidth().height(45.dp).pressable({ view.confirm(); r.action() }, scaleTo = 0.97f).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.label, style = Ios.body.copy(fontSize = 16.sp, color = if (r.destructive) Ios.Red else Color.White), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (r.image != null) Image(r.image, null, modifier = Modifier.size(22.dp))
                    else if (r.icon != null) Icon(r.icon, null, tint = if (r.destructive) Ios.Red else Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

fun Rect.toAndroid() = android.graphics.Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

// ------------------------------------------------------------------------------------------ Folder

@Composable
fun FolderOverlay(ui: HomeUi, backdrop: Backdrop?, onIconLongPress: (FolderItem, AppItem, Rect, PendingDrag) -> Unit) {
    val app = GlassApp.instance
    val store = app.home
    val view = LocalView.current
    val open = store.openFolder
    val p by animateFloatAsState(if (open != null) 1f else 0f, Ios.bouncy(), label = "folder")
    val last = rememberLast(open) ?: return
    if (p <= 0.01f && open == null) return
    val folder = store.find(last.id) as? FolderItem
    if (folder == null) { if (open != null) store.openFolder = null; return }
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    val renaming = store.renameFolder == folder.id || store.editMode
    val focus = remember { FocusRequester() }
    fun close() {
        if (name != folder.name) store.rename(folder.id, name)
        store.renameFolder = null; store.openFolder = null
    }
    LaunchedEffect(store.renameFolder) { if (store.renameFolder == folder.id) runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f * p)).pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.Center,
    ) {
        val d = LocalDensity.current
        val panel = maxWidth - 64.dp
        val panelPx = with(d) { panel.toPx() }
        val screenW = with(d) { maxWidth.toPx() }
        val screenH = with(d) { maxHeight.toPx() }
        val a = last.anchor
        // fly out of the folder icon
        val fromScale = (a.width / panelPx).coerceIn(0.05f, 1f)
        val dx = a.center.x - screenW / 2
        val dy = a.center.y - screenH / 2
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val s = lerp(fromScale, 1f, p); scaleX = s; scaleY = s
                translationX = lerp(dx, 0f, p); translationY = lerp(dy, 0f, p)
                alpha = (p * 2f).coerceAtMost(1f)
            },
        ) {
            Box(Modifier.width(panel).padding(bottom = 18.dp), contentAlignment = Alignment.CenterStart) {
                if (renaming) {
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = Ios.largeTitle.copy(fontSize = 30.sp), cursorBrush = SolidColor(Ios.Blue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { store.rename(folder.id, name); store.renameFolder = null }),
                        modifier = Modifier.fillMaxWidth().glass(Ios.rr(14f), backdrop, tint = Color.White.copy(alpha = 0.14f), refraction = 0.3f)
                            .padding(horizontal = 14.dp, vertical = 6.dp).focusRequester(focus))
                } else Text(folder.name, style = Ios.largeTitle.copy(fontSize = 30.sp), modifier = Modifier.padding(start = 6.dp))
            }
            Box(
                Modifier.size(panel).glass(Ios.rr(40f), backdrop, tint = Color.White.copy(alpha = 0.14f), blurRadius = 24f, refraction = 0.8f)
                    .pointerInput(Unit) { detectTapGestures { } }.padding(18.dp),
            ) {
                LazyVerticalGrid(GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(folder.apps, key = { it.raw }) { k ->
                        val e = app.repo.byKey[k]
                        FolderAppCell(e, folder, k, onLaunch = { if (e != null) { app.repo.launch(e, view, null); close() } }, onLong = onIconLongPress)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderAppCell(e: AppEntry?, folder: FolderItem, k: com.glass.launcher.data.AppKey, onLaunch: () -> Unit, onLong: (FolderItem, AppItem, Rect, PendingDrag) -> Unit) {
    var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppIconImage(e, 60.dp, pressed = pressed, modifier = Modifier
            .onGloballyPositioned { coords = it }
            .pointerInput(k) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onLaunch() },
                    onLongPress = { o ->
                        val c = coords ?: return@detectTapGestures
                        view.thud()
                        val item = AppItem("fld-" + k.raw.hashCode(), k)
                        onLong(folder, item, c.boundsInRoot(), PendingDrag(item, c.localToRoot(o), o, c.size.width.toFloat(), folder.id))
                    },
                )
            })
        Text(e?.label ?: "", style = Ios.label.copy(shadow = null), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
}

// ------------------------------------------------------------------------------------------ App Library group

@Composable
fun GroupPanel(ui: HomeUi, backdrop: Backdrop?) {
    val app = GlassApp.instance
    val view = LocalView.current
    val p by animateFloatAsState(if (ui.group != null) 1f else 0f, Ios.bouncy(), label = "group")
    val g = rememberLast(ui.group) ?: return
    if (p <= 0.01f && ui.group == null) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f * p)).pointerInput(Unit) { detectTapGestures { ui.group = null } }, contentAlignment = Alignment.Center) {
        Column(Modifier.graphicsLayer { val s = lerp(0.6f, 1f, p); scaleX = s; scaleY = s; alpha = p.coerceIn(0f, 1f) }.padding(horizontal = 26.dp)) {
            Text(g.title, style = Ios.largeTitle.copy(fontSize = 30.sp), modifier = Modifier.padding(start = 6.dp, bottom = 16.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(0.82f).glass(Ios.rr(40f), backdrop, tint = Color.White.copy(alpha = 0.14f), blurRadius = 24f, refraction = 0.8f)
                .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp)) {
                LazyVerticalGrid(GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(g.apps, key = { it.key.raw }) { e ->
                        Column(Modifier.pressable({ app.repo.launch(e, view, null); ui.group = null }), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppIconImage(e, 60.dp)
                            Text(e.label, style = Ios.label.copy(shadow = null), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Alert

@Composable
fun AlertLayer(ui: HomeUi, backdrop: Backdrop?) {
    val p by animateFloatAsState(if (ui.alert != null) 1f else 0f, Ios.bouncy(), label = "alert")
    val a = rememberLast(ui.alert) ?: return
    if (p <= 0.01f && ui.alert == null) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * p)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(272.dp)
                .graphicsLayer { val s = lerp(1.15f, 1f, p); scaleX = s; scaleY = s; alpha = p.coerceIn(0f, 1f) }
                .glass(Ios.rr(16f), backdrop, tint = Color(0xFF2C2C2E).copy(alpha = 0.6f), blurRadius = 28f, refraction = 0.4f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(a.title, style = Ios.headline.copy(fontSize = 17.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp))
            if (a.message != null) Text(a.message, style = Ios.footnote.copy(color = Color.White.copy(alpha = 0.85f)), textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
            Spacer(Modifier.height(18.dp))
            a.buttons.forEach { b ->
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ios.Separator))
                Box(Modifier.fillMaxWidth().height(46.dp).pressable({ ui.alert = null; b.action() }, scaleTo = 0.98f), contentAlignment = Alignment.Center) {
                    Text(b.label, fontSize = 17.sp, color = if (b.destructive) Ios.Red else Ios.Blue, fontWeight = if (b.cancel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Widget gallery

@Composable
fun WidgetGallery(ui: HomeUi, backdrop: Backdrop?, onAdd: (WidgetKind, WidgetSize) -> Unit) {
    val p by animateFloatAsState(if (ui.gallery) 1f else 0f, Ios.snappy(), label = "gallery")
    if (p <= 0.01f && !ui.gallery) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * p)).pointerInput(Unit) { detectTapGestures { ui.gallery = false } }) {
        BoxWithConstraints(Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth().height(maxHeight * 0.0f + 640.dp)
                    .graphicsLayer { translationY = (1f - p) * size.height }
                    .glass(Ios.rr(38f), backdrop, tint = Color(0xFF1C1C1E).copy(alpha = 0.75f), blurRadius = 30f, refraction = 0.3f)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding(),
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Widgets", style = Ios.title1, modifier = Modifier.weight(1f))
                    Box(Modifier.size(30.dp).background(Color.White.copy(alpha = 0.16f), CircleShape).pressable({ ui.gallery = false }), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(WidgetKind.entries) { k ->
                        var size by remember { mutableStateOf(WidgetSize.Small) }
                        Column {
                            Text(k.title, style = Ios.headline.copy(fontSize = 18.sp))
                            Text(k.blurb, style = Ios.footnote, modifier = Modifier.padding(bottom = 10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val w: Dp = if (size == WidgetSize.Small) 150.dp else 300.dp
                                CompositionLocalProviderWallpaper(backdrop) {
                                    WidgetView(k, if (size == WidgetSize.Large) WidgetSize.Medium else size, Modifier.width(w).height(150.dp))
                                }
                            }
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                WidgetSize.entries.forEach { s ->
                                    Text(s.title, style = Ios.footnote.copy(color = if (s == size) Color.Black else Color.White, fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier.background(if (s == size) Color.White else Color.White.copy(alpha = 0.14f), CircleShape)
                                            .pressable({ size = s }).padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                Text("Add Widget", style = Ios.headline.copy(fontSize = 15.sp),
                                    modifier = Modifier.background(Ios.Blue, CircleShape).pressable({ onAdd(k, size); ui.gallery = false }, haptic = true)
                                        .padding(horizontal = 14.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompositionLocalProviderWallpaper(b: Backdrop?, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalWallpaperBackdrop provides b, content = content)
}
