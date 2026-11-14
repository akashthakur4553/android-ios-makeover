package com.glass.launcher.data

import android.content.ComponentName
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** Stable identity of a launchable activity: "pkg/cls#userSerial". */
@JvmInline
value class AppKey(val raw: String) {
    val pkg get() = raw.substringBefore('/')
    val cls get() = raw.substringAfter('/').substringBefore('#')
    val serial get() = raw.substringAfter('#', "0").toLongOrNull() ?: 0L

    companion object {
        fun of(pkg: String, cls: String, serial: Long) = AppKey("$pkg/$cls#$serial")
    }
}

@Immutable
class AppEntry(
    val key: AppKey,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
    val installTime: Long,
    val category: AppCategory,
    val isSystem: Boolean,
    val isWork: Boolean,
)

enum class WidgetKind(val title: String, val blurb: String) {
    Weather("Weather", "See the current conditions and forecast."),
    Calendar("Calendar", "See today's date and your next events."),
    Clock("Clock", "Keep track of the time."),
    Battery("Batteries", "Check the battery level of your phone."),
    Music("Music", "Control what's playing."),
    Suggestions("Siri Suggestions", "Apps you're likely to use next."),
}

enum class WidgetSize(val spanX: Int, val spanY: Int, val title: String) {
    Small(2, 2, "Small"), Medium(4, 2, "Medium"), Large(4, 4, "Large")
}

@Immutable
sealed interface HomeItem {
    val id: String
    val spanX: Int get() = 1
    val spanY: Int get() = 1
}

@Immutable
data class AppItem(override val id: String, val key: AppKey) : HomeItem

@Immutable
data class FolderItem(override val id: String, val name: String, val apps: List<AppKey>) : HomeItem

@Immutable
data class WidgetItem(override val id: String, val kind: WidgetKind, val size: WidgetSize) : HomeItem {
    override val spanX get() = size.spanX
    override val spanY get() = size.spanY
}

fun newId(): String = java.util.UUID.randomUUID().toString().take(12)

const val COLS = 4
const val ROWS = 6

data class Placed(val item: HomeItem, val col: Int, val row: Int)

/**
 * iOS-style flow layout: items fill the page left-to-right, top-to-bottom; widgets take
 * 2x2 / 4x2 / 4x4 blocks. Anything that doesn't fit spills to the next page.
 */
fun packPage(items: List<HomeItem>, cols: Int = COLS, rows: Int = ROWS): Pair<List<Placed>, List<HomeItem>> {
    val occ = Array(rows) { BooleanArray(cols) }
    val placed = ArrayList<Placed>(items.size)
    val overflow = ArrayList<HomeItem>()
    for (item in items) {
        var done = false
        loop@ for (r in 0..rows - item.spanY) for (c in 0..cols - item.spanX) {
            var free = true
            for (dr in 0 until item.spanY) for (dc in 0 until item.spanX) if (occ[r + dr][c + dc]) free = false
            if (free) {
                for (dr in 0 until item.spanY) for (dc in 0 until item.spanX) occ[r + dr][c + dc] = true
                placed += Placed(item, c, r); done = true; break@loop
            }
        }
        if (!done) overflow += item
    }
    return placed to overflow
}
