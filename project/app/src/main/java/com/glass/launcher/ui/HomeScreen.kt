package com.glass.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.GlassApp
import com.glass.launcher.data.AppEntry
import com.glass.launcher.data.AppItem
import com.glass.launcher.data.COLS
import com.glass.launcher.data.Container
import com.glass.launcher.data.FolderItem
import com.glass.launcher.data.HomeItem
import com.glass.launcher.data.OpenFolder
import com.glass.launcher.data.PendingDrag
import com.glass.launcher.data.ROWS
import com.glass.launcher.data.WidgetItem
import com.glass.launcher.data.newId
import com.glass.launcher.data.packPage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plain (non-state) geometry shared between layout and the drag engine. */
class Geo {
    var grid = Rect.Zero
    var dock = Rect.Zero
    var width = 0f
    var iconPx = 0f
    var iconTopPx = 0f
}

sealed interface Hover {
    data object None : Hover
    data class Cell(val page: Int, val index: Int) : Hover
    data class Dock(val index: Int) : Hover
    data class Folder(val id: String) : Hover
    data class Edge(val dir: Int) : Hover
}

@Composable
fun HomeScreen(homePresses: SharedFlow<Unit>, overlays: OverlayCtl, ui: HomeUi, onRequestPermission: (String) -> Unit) {
    val app = GlassApp.instance
    val store = app.home
    val repo = app.repo
    val prefs = app.prefs
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val apps by repo.apps.collectAsState()
    val wallBackdrop = rememberLayerBackdrop()
    // Overlays sit on the blurred home layer, so they use frosted panels (no extra recording).
    val homeBackdrop: com.kyant.backdrop.Backdrop? = null
    val geo = remember { Geo() }
    val pager = rememberPagerState(initialPage = 1) { store.pages.size + 2 }
    var hover by remember { mutableStateOf<Hover>(Hover.None) }

    val homePageCount = store.pages.size
    val lastPage = homePageCount + 1
    fun homeIndex(): Int = (pager.currentPage - 1).coerceIn(0, (store.pages.size - 1).coerceAtLeast(0))

    // ---------------- side-page fraction (Today view / App Library): blur wallpaper + hide dock
    // Read only inside draw/layer lambdas so swiping never recomposes the screen.
    fun side(): Float {
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val n = store.pages.size
        return when {
            position < 1f -> (1f - position).coerceIn(0f, 1f)
            position > n -> (position - n).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    val menuOpen = ui.menu != null
    val folderP = animateFloatAsState(if (store.openFolder != null || ui.group != null) 1f else 0f, Ios.bouncy(), label = "fp")
    val menuP = animateFloatAsState(if (menuOpen || ui.alert != null || ui.gallery) 1f else 0f, Ios.snappy(), label = "mp")
    fun overlayAmount() = maxOf(overlays.amount.coerceIn(0f, 1f), folderP.value, menuP.value)
    remember(wallBackdrop) { PreBlurred.set.add(wallBackdrop) }

    // ---------------- home button / back
    LaunchedEffect(Unit) {
        homePresses.collect {
            when {
                overlays.anyOpen -> overlays.closeAll()
                ui.menu != null || ui.alert != null || ui.gallery || ui.group != null -> { ui.menu = null; ui.alert = null; ui.gallery = false; ui.group = null }
                store.openFolder != null -> { store.openFolder = null; store.renameFolder = null }
                store.editMode -> store.editMode = false
                pager.currentPage != 1 -> pager.animateScrollToPage(1)
            }
        }
    }
    BackHandler(enabled = overlays.anyOpen || ui.menu != null || ui.alert != null || ui.gallery || ui.group != null || store.openFolder != null || store.editMode) {
        scope.launch { (homePresses as? kotlinx.coroutines.flow.MutableSharedFlow<Unit>)?.emit(Unit) }
    }

    // ---------------- hover -> live layout changes (with small dwell times like iOS)
    LaunchedEffect(hover) {
        val d = store.drag ?: return@LaunchedEffect
        when (val h = hover) {
            is Hover.Cell -> { delay(110); store.folderHover = null; store.move(d.item.id, Container.Page(h.page), h.index) }
            is Hover.Dock -> { delay(110); store.folderHover = null; store.move(d.item.id, Container.Dock, h.index) }
            is Hover.Folder -> { delay(380); store.folderHover = h.id; view.tick() }
            is Hover.Edge -> {
                store.folderHover = null
                while (true) {
                    delay(520)
                    val cur = pager.currentPage
                    var target = cur + h.dir
                    if (h.dir > 0 && target > store.pages.size) { store.ensureTrailingEmptyPage(); target = store.pages.size }
                    target = target.coerceIn(1, store.pages.size.coerceAtLeast(1))
                    if (target == cur) break
                    pager.animateScrollToPage(target)
                    // move the dragged item onto the new page
                    store.move(d.item.id, Container.Page(target - 1), store.pages[target - 1].size)
                }
            }
            Hover.None -> store.folderHover = null
        }
    }

    fun computeHover(posPointer: Offset): Hover {
        val d = store.drag ?: return Hover.None
        val edge = with(density) { 22.dp.toPx() }
        if (geo.dock.contains(posPointer) && d.item !is WidgetItem) {
            val inDock = store.dock.any { it.id == d.item.id }
            val n = (store.dock.size + if (inDock) 0 else 1).coerceAtLeast(1)
            val idx = ((posPointer.x - geo.dock.left) / (geo.dock.width / n)).toInt().coerceIn(0, n - 1)
            return Hover.Dock(idx)
        }
        if (posPointer.x < edge) return Hover.Edge(-1)
        if (posPointer.x > geo.width - edge) return Hover.Edge(1)
        val pi = pager.currentPage - 1
        if (pi !in store.pages.indices) return Hover.None
        val g = geo.grid
        val anchor = posPointer - d.touchOffset + Offset(geo.iconPx / 2, geo.iconPx / 2)
        if (!g.contains(anchor)) return Hover.None
        val cw = g.width / COLS
        val ch = g.height / ROWS
        val col = ((anchor.x - g.left) / cw).toInt().coerceIn(0, COLS - 1)
        val row = ((anchor.y - g.top) / ch).toInt().coerceIn(0, ROWS - 1)
        val list = store.pages[pi]
        val placed = packPage(list).first
        val hit = placed.firstOrNull { col in it.col until it.col + it.item.spanX && row in it.row until it.row + it.item.spanY }
            ?: return Hover.Cell(pi, list.size)
        if (hit.item.id == d.item.id) return Hover.None
        val cx = g.left + hit.col * cw + cw / 2
        val cy = g.top + hit.row * ch + geo.iconTopPx + geo.iconPx / 2
        val near = abs(anchor.x - cx) < geo.iconPx * 0.36f && abs(anchor.y - cy) < geo.iconPx * 0.36f
        if (near && d.item is AppItem && (hit.item is AppItem || hit.item is FolderItem)) return Hover.Folder(hit.item.id)
        return Hover.Cell(pi, list.indexOf(hit.item))
    }

    // ---------------- vertical pull-downs: Spotlight (middle), Notification Centre (top-left), Control Centre (top-right)
    val pullModifier = Modifier.pointerInput(store.editMode) {
        val tracker = VelocityTracker()
        var target: androidx.compose.animation.core.Animatable<Float, *>? = null
        var acc = 0f
        var dist = 1f
        detectVerticalDragGestures(
            onDragStart = { o ->
                tracker.resetTracking(); acc = 0f
                val topZone = o.y < with(density) { 130.dp.toPx() }
                target = when {
                    store.editMode || store.drag != null || store.pending != null -> null
                    topZone && o.x < size.width * 0.55f -> overlays.nc
                    topZone -> overlays.cc
                    pager.currentPage in 1..store.pages.size -> overlays.spotlight
                    else -> null
                }
                dist = when (target) {
                    overlays.spotlight -> with(density) { 150.dp.toPx() }
                    overlays.nc -> size.height * 0.6f
                    else -> with(density) { 320.dp.toPx() }
                }
            },
            onVerticalDrag = { c, dy ->
                val t = target ?: return@detectVerticalDragGestures
                tracker.addPointerInputChange(c)
                acc += dy
                if (acc > 0) { c.consume(); overlays.drag(t, acc / dist) }
            },
            onDragEnd = {
                val t = target ?: return@detectVerticalDragGestures
                val v = tracker.calculateVelocity().y
                val open = t.value > 0.33f || v > 900f
                overlays.settle(t, open, v / dist)
                if (open) { view.tick(); if (t === overlays.spotlight) { overlays.settle(overlays.nc, false); overlays.settle(overlays.cc, false) } }
                target = null
            },
            onDragCancel = { target?.let { overlays.settle(it, false) }; target = null },
        )
    }

    // ---------------- root: owns the drag engine (sees every pointer event first)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { geo.width = it.size.width.toFloat() }
            .pointerInput(Unit) {
                val slop = 10.dp.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val pend = store.pending
                        val d = store.drag
                        if (pend == null && d == null) continue
                        val ch = ev.changes.firstOrNull() ?: continue
                        if (d == null && pend != null) {
                            if (!ch.pressed) { store.pending = null; continue }
                            ev.changes.forEach { it.consume() }
                            if ((ch.position - pend.start).getDistance() > slop) {
                                ui.menu = null
                                val fromLibrary = pend.fromFolder == "library"
                                val page = if (fromLibrary) store.pages.lastIndex.coerceAtLeast(0) else homeIndex()
                                store.beginDrag(if (fromLibrary) PendingDrag(pend.item, pend.start, pend.touchOffset, pend.iconSizePx, null) else pend, page)
                                store.drag?.pos = ch.position
                                if (fromLibrary || pager.currentPage !in 1..store.pages.size) scope.launch { pager.animateScrollToPage(page + 1) }
                                view.tick()
                            }
                            continue
                        }
                        if (d != null) {
                            ev.changes.forEach { it.consume() }
                            d.pos = ch.position
                            val h = computeHover(ch.position)
                            if (h != hover) hover = h
                            if (!ch.pressed) {
                                // Commit where the finger let go, even if the hover dwell hadn't fired yet.
                                when (val hv = h) {
                                    is Hover.Cell -> if (store.folderHover == null) store.move(d.item.id, Container.Page(hv.page), hv.index)
                                    is Hover.Dock -> store.move(d.item.id, Container.Dock, hv.index)
                                    else -> {}
                                }
                                hover = Hover.None
                                if (store.folderHover != null) view.confirm()
                                store.drop()
                            }
                        }
                    }
                }
            },
    ) {
        // ===================== the home layer (gets blurred behind overlays)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val r = overlayAmount() * 26.dp.toPx()
                    renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                    val s = 1f - 0.04f * overlays.amount.coerceIn(0f, 1f)
                    scaleX = s; scaleY = s
                },
        ) {
            // Wallpaper: a pre-blurred copy underneath (sampled by all glass), the sharp one on top.
            // On Today / App Library the sharp layer simply fades out — no per-frame blur.
            val wp = app.wallpaper.image
            val wpBlur = app.wallpaper.blurred
            // (alpha 0 layers are skipped by the GPU, so normally only ONE full-screen image is drawn)
            if (wpBlur != null) Image(wpBlur, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (wp == null) 1f else side() }.layerBackdrop(wallBackdrop))
            else Box(Modifier.fillMaxSize().background(Color(0xFF101528)).layerBackdrop(wallBackdrop))
            if (wp != null) Image(wp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - side() })
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = side() }.background(Color.Black.copy(alpha = 0.22f)))

            androidx.compose.runtime.CompositionLocalProvider(LocalWallpaperBackdrop provides wallBackdrop) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().then(pullModifier)) {
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                                state = pager,
                                snapPositionalThreshold = 0.22f,
                                snapAnimationSpec = spring(dampingRatio = 1f, stiffness = 650f),
                            ),
                            userScrollEnabled = store.drag == null && store.pending == null,
                            key = { it },
                        ) { page ->
                            when {
                                page == 0 -> TodayView(onSearch = { overlays.open(overlays.spotlight) }, onRequestPermission = onRequestPermission)
                                page == lastPage -> AppLibrary(
                                    onOpenGroup = { ui.group = it },
                                    onLongPressApp = { e, r ->
                                        val item = AppItem(newId(), e.key)
                                        ui.menu = MenuSpec(item, r, MenuSource.Library)
                                        store.pending = PendingDrag(item, r.center, Offset(r.width / 2, r.height / 2), r.width, "library")
                                    },
                                )
                                else -> HomePage(page - 1, pager, geo, ui, onRequestPermission)
                            }
                        }
                    }
                    // Search pill / page dots + Dock float above the full-screen pages (like iOS)
                    Column(Modifier.align(Alignment.BottomCenter)) {
                    Box(Modifier.fillMaxWidth().height(40.dp).graphicsLayer { val sd = side(); alpha = 1f - sd; translationY = sd * 60.dp.toPx() }, contentAlignment = Alignment.Center) {
                        val showDots = pager.isScrollInProgress || store.editMode || store.drag != null
                        Crossfade(showDots, label = "dots") { dots ->
                            if (dots) Dots(store.pages.size, (pager.currentPage - 1).coerceIn(0, store.pages.size - 1))
                            else Row(
                                Modifier.glass(Ios.capsule, wallBackdrop, tint = Color.White.copy(alpha = 0.10f), refraction = 0.6f)
                                    .pressable({ overlays.open(overlays.spotlight) }, haptic = true)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Text("Search", style = Ios.footnote.copy(color = Color.White, fontSize = 13.sp), modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                    Dock(geo, ui, Modifier.graphicsLayer { val sd = side(); alpha = 1f - sd; translationY = sd * 160.dp.toPx() })
                    }
                }
                // Edit mode top bar
                AnimatedVisibility(store.editMode, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.statusBarsPadding().padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.glass(Ios.capsule, wallBackdrop, tint = Color.White.copy(alpha = 0.16f), refraction = 0.5f)
                                .pressable({ ui.gallery = true }, haptic = true).padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("Edit", style = Ios.headline.copy(fontSize = 15.sp), modifier = Modifier.padding(start = 2.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Done", style = Ios.headline.copy(fontSize = 15.sp),
                            modifier = Modifier.glass(Ios.capsule, wallBackdrop, tint = Color.White.copy(alpha = 0.16f), refraction = 0.5f)
                                .pressable({ store.editMode = false; store.normalize(); store.save() }, haptic = true).padding(horizontal = 16.dp, vertical = 7.dp))
                    }
                }
            }
        }

        // ===================== overlays
        val anyDrag = store.drag
        FolderOverlay(ui, homeBackdrop) { folder, item, rect, pend ->
            ui.menu = MenuSpec(item, rect, MenuSource.Folder, folder.id)
            store.pending = pend
        }
        GroupPanel(ui, homeBackdrop)
        ContextMenuLayer(ui, homeBackdrop,
            onRequestRemove = { spec -> requestRemove(spec, ui) },
            onEditHome = { store.editMode = true; view.thud() })
        OverlayHosts(overlays, homeBackdrop, onRequestPermission)
        WidgetGallery(ui, homeBackdrop) { k, s -> store.addWidget(k, s, homeIndex()); store.editMode = true }
        AlertLayer(ui, homeBackdrop)

        // Dragged item follows the finger
        if (anyDrag != null) {
            val b = store.bounds[anyDrag.item.id]
            val sz = b?.size ?: Size(geo.iconPx, geo.iconPx)
            val dp = with(density) { Pair(sz.width.toDp(), sz.height.toDp()) }
            Box(
                Modifier
                    .offset { (anyDrag.pos - anyDrag.touchOffset).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                    .size(dp.first, dp.second)
                    .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = 0.96f },
            ) {
                ItemVisual(anyDrag.item, dp.first, dp.second, label = false)
            }
        }
    }
}

private fun requestRemove(spec: MenuSpec, ui: HomeUi) {
    val app = GlassApp.instance
    val store = app.home
    when (val it = spec.item) {
        is WidgetItem -> ui.alert = AlertSpec("Remove “${it.kind.title}” widget?", "Removing this widget will not delete any data.", listOf(
            AlertButton("Remove", destructive = true) { store.remove(it.id) },
            AlertButton("Cancel", cancel = true),
        ))
        is AppItem -> {
            val e = app.repo.byKey[it.key]
            val name = e?.label ?: "App"
            val buttons = ArrayList<AlertButton>()
            if (e != null && app.repo.canUninstall(e)) buttons += AlertButton("Delete App", destructive = true) { app.repo.uninstall(e) }
            if (spec.source == MenuSource.Folder && spec.folderId != null) buttons += AlertButton("Remove from Folder") { store.removeFromFolder(spec.folderId, it.key); store.addToHome(it.key) }
            else buttons += AlertButton("Remove from Home Screen") { store.remove(it.id) }
            buttons += AlertButton("Cancel", cancel = true)
            ui.alert = AlertSpec("Remove “$name”?", "Removing from Home Screen will keep the app in your App Library.", buttons)
        }
        is FolderItem -> ui.alert = AlertSpec("Remove “${it.name}” folder?", "Its apps stay in your App Library.", listOf(
            AlertButton("Remove", destructive = true) { store.remove(it.id) },
            AlertButton("Cancel", cancel = true),
        ))
    }
}

// ------------------------------------------------------------------------------------------ Page grid

@Composable
private fun HomePage(pageIdx: Int, pager: PagerState, geo: Geo, ui: HomeUi, onRequestPermission: (String) -> Unit) {
    val store = GlassApp.instance.home
    val view = LocalView.current
    val items = store.pages.getOrNull(pageIdx) ?: return
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 146.dp)
            .onGloballyPositioned { if (pager.currentPage == pageIdx + 1) geo.grid = it.boundsInRoot() }
            .pointerInput(pageIdx) {
                detectTapGestures(
                    onTap = { if (store.editMode) { store.editMode = false; store.normalize(); store.save() } },
                    onLongPress = { o ->
                        val cw = size.width / COLS.toFloat(); val ch = size.height / ROWS.toFloat()
                        val col = (o.x / cw).toInt(); val row = (o.y / ch).toInt()
                        val hit = packPage(items).first.any { col in it.col until it.col + it.item.spanX && row in it.row until it.row + it.item.spanY }
                        if (!hit && !store.editMode) { store.editMode = true; view.thud() }
                    },
                )
            },
    ) {
        val cw = maxWidth / COLS
        val ch = maxHeight / ROWS
        val icon = minOf(62.dp, cw * 0.74f)
        val labelH = 18.dp
        val iconTop = ((ch - icon - labelH) / 2).coerceAtLeast(0.dp)
        val d = LocalDensity.current
        geo.iconPx = with(d) { icon.toPx() }
        geo.iconTopPx = with(d) { iconTop.toPx() }
        val placed = packPage(items).first
        placed.forEach { p ->
            key(p.item.id) {
                val target = with(d) { IntOffset((cw * p.col).roundToPx(), (ch * p.row).roundToPx()) }
                val off by animateIntOffsetAsState(target, spring(dampingRatio = 0.8f, stiffness = 380f), label = "pos")
                val hPad = (cw - icon) / 2
                Box(Modifier.offset { off }.size(cw * p.item.spanX, ch * p.item.spanY)) {
                    when (val it = p.item) {
                        is WidgetItem -> {
                            val w = cw * it.spanX - hPad * 2
                            val small = cw * 2 - hPad * 2
                            val h = if (it.spanY >= 4) small * 2 + iconTop * 2 + labelH else small
                            Box(Modifier.padding(start = hPad, top = iconTop).size(w, minOf(h, ch * it.spanY - iconTop))) {
                                HomeItemCell(it, w, minOf(h, ch * it.spanY - iconTop), MenuSource.Home, ui, onRequestPermission)
                            }
                        }
                        else -> Column(Modifier.fillMaxWidth().padding(top = iconTop), horizontalAlignment = Alignment.CenterHorizontally) {
                            HomeItemCell(it, icon, icon, MenuSource.Home, ui, onRequestPermission)
                            if (GlassApp.instance.prefs.showLabels) {
                                val label = when (it) { is AppItem -> GlassApp.instance.repo.byKey[it.key]?.label; is FolderItem -> it.name; else -> null } ?: ""
                                Text(label, style = Ios.label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 5.dp).width(cw - 4.dp)
                                        .graphicsLayer { alpha = if (store.drag?.item?.id == it.id) 0f else 1f })
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Dock

@Composable
private fun Dock(geo: Geo, ui: HomeUi, modifier: Modifier) {
    val store = GlassApp.instance.home
    val bd = wallpaperBackdrop()
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .onGloballyPositioned { geo.dock = it.boundsInRoot() }
                .glass(Ios.rr(34f), bd, tint = Color.White.copy(alpha = 0.10f), blurRadius = 14f, refraction = 1f, hero = true),
        ) {
            val d = LocalDensity.current
            val icon = 62.dp
            val n = store.dock.size.coerceAtLeast(1)
            val slot = maxWidth / maxOf(n, 1)
            store.dock.forEachIndexed { i, item ->
                key(item.id) {
                    val target = with(d) { IntOffset((slot * i + (slot - icon) / 2).roundToPx(), ((92.dp - icon) / 2).roundToPx()) }
                    val off by animateIntOffsetAsState(target, spring(dampingRatio = 0.8f, stiffness = 380f), label = "dock")
                    Box(Modifier.offset { off }) { HomeItemCell(item, icon, icon, MenuSource.Dock, ui) {} }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Item cell (icon/folder/widget + gestures)

@Composable
fun ItemVisual(item: HomeItem, w: Dp, h: Dp, label: Boolean, pressed: Boolean = false, onRequestPermission: (String) -> Unit = {}) {
    val app = GlassApp.instance
    when (item) {
        is AppItem -> AppIconImage(app.repo.byKey[item.key], w, pressed = pressed)
        is FolderItem -> FolderIconView(item, w, wallpaperBackdrop(), highlighted = app.home.folderHover == item.id, pressed = pressed)
        is WidgetItem -> WidgetView(item.kind, item.size, Modifier.size(w, h), onRequestPermission)
    }
}

@Composable
private fun HomeItemCell(item: HomeItem, w: Dp, h: Dp, source: MenuSource, ui: HomeUi, onRequestPermission: (String) -> Unit) {
    val app = GlassApp.instance
    val store = app.home
    val view = LocalView.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val dragging = store.drag?.item?.id == item.id
    val edit = store.editMode
    val folderHighlight = store.folderHover == item.id
    Box(
        Modifier
            .size(w, h)
            .graphicsLayer { alpha = if (dragging) 0f else 1f }
            .jiggle(edit && !dragging, item.id.hashCode())
            .onGloballyPositioned { c -> coords = c }
            .pointerInput(item.id, edit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    val timeout = if (edit) 160L else 420L
                    var moved = false
                    var released: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    withTimeoutOrNull(timeout) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (c.isConsumed) { moved = true; break }
                            if (!c.pressed) { released = c; break }
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; break }
                        }
                    }
                    pressed = false
                    when {
                        released != null -> {
                            released!!.consume()
                            onTap(item, coords, edit, view)
                        }
                        moved -> {}
                        else -> {
                            val c = coords ?: return@awaitEachGesture
                            val root = c.localToRoot(down.position)
                            store.bounds[item.id] = c.boundsInRoot()
                            store.pending = PendingDrag(item, root, down.position, c.size.width.toFloat(), null)
                            if (!edit) { view.thud(); ui.menu = MenuSpec(item, c.boundsInRoot(), source) }
                            else view.tick()
                        }
                    }
                }
            },
    ) {
        ItemVisual(item, w, h, label = false, pressed = pressed || folderHighlight, onRequestPermission = onRequestPermission)
        if (edit && !dragging && item !is WidgetItem || edit && item is WidgetItem && !dragging) {
            MinusBadge(Modifier.align(Alignment.TopStart).offset((-7).dp, (-7).dp)) {
                requestRemove(MenuSpec(item, store.bounds[item.id] ?: Rect.Zero, source), ui)
            }
        }
    }
}

private fun onTap(item: HomeItem, coords: LayoutCoordinates?, edit: Boolean, view: android.view.View) {
    val app = GlassApp.instance
    val store = app.home
    when (item) {
        is AppItem -> if (!edit) {
            val e: AppEntry = app.repo.byKey[item.key] ?: return
            val r = coords?.boundsInRoot()?.toAndroid()
            app.repo.launch(e, view, r)
        }
        is FolderItem -> store.openFolder = OpenFolder(item.id, coords?.boundsInRoot() ?: Rect.Zero)
        is WidgetItem -> if (!edit) when (item.kind) {
            com.glass.launcher.data.WidgetKind.Weather -> app.repo.find("com.miui.weather2", "com.google.android.googlequicksearchbox")?.let { app.repo.launch(it, view, null) }
            com.glass.launcher.data.WidgetKind.Calendar -> app.repo.find("com.xiaomi.calendar", "com.google.android.calendar")?.let { app.repo.launch(it, view, null) }
            com.glass.launcher.data.WidgetKind.Clock -> app.repo.find("com.android.deskclock", "com.google.android.deskclock")?.let { app.repo.launch(it, view, null) }
            com.glass.launcher.data.WidgetKind.Music -> app.media.openApp()
            com.glass.launcher.data.WidgetKind.Battery -> com.glass.launcher.util.Sys.open(view.context, android.content.Intent(android.content.Intent.ACTION_POWER_USAGE_SUMMARY))
            com.glass.launcher.data.WidgetKind.Suggestions -> {}
        }
    }
}


/** Each overlay reads its own animation value here, so animating one never recomposes the home screen. */
@Composable
private fun OverlayHosts(overlays: OverlayCtl, backdrop: com.kyant.backdrop.Backdrop?, onRequestPermission: (String) -> Unit) {
    SpotlightHost(overlays, backdrop, onRequestPermission)
    NcHost(overlays, backdrop)
    CcHost(overlays, backdrop)
}

@Composable
private fun SpotlightHost(overlays: OverlayCtl, backdrop: com.kyant.backdrop.Backdrop?, onRequestPermission: (String) -> Unit) {
    Spotlight(overlays.spotlight.value, overlays.spotlight.targetValue > 0.5f, backdrop, onClose = { overlays.settle(overlays.spotlight, false) }, onRequestPermission = onRequestPermission)
}

@Composable
private fun NcHost(overlays: OverlayCtl, backdrop: com.kyant.backdrop.Backdrop?) {
    NotificationCentre(overlays.nc.value, backdrop, onClose = { overlays.settle(overlays.nc, false) },
        onDrag = { overlays.drag(overlays.nc, it) }, onDragEnd = { overlays.settle(overlays.nc, it > 0.5f) })
}

@Composable
private fun CcHost(overlays: OverlayCtl, backdrop: com.kyant.backdrop.Backdrop?) {
    ControlCentre(overlays.cc.value, backdrop, onClose = { overlays.settle(overlays.cc, false) },
        onDragClose = { overlays.drag(overlays.cc, it) }, onDragEnd = { overlays.settle(overlays.cc, it > 0.6f) })
}
