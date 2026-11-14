package com.glass.launcher.services

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

/** Most-recently-used apps + small in-memory snapshots for the Glass app switcher. Never persisted. */
object RecentApps {
    val mru = mutableStateListOf<String>()
    private val thumbs = ConcurrentHashMap<String, ImageBitmap>()
    @Volatile var foreground: String? = null

    private val ignore = setOf(
        "com.glass.launcher", "com.android.systemui", "com.mi.android.globallauncher", "com.miui.home",
        "android", "com.android.intentresolver", "com.google.android.permissioncontroller",
    )

    private var stock: String? = null
    fun isTrackable(ctx: Context, pkg: String?): Boolean {
        if (pkg == null || pkg in ignore) return false
        if (stock == null) stock = com.glass.launcher.StockLauncher.intent(ctx)?.component?.packageName ?: ""
        if (pkg == stock) return false
        return com.glass.launcher.GlassApp.instance.repo.apps.value.any { it.key.pkg == pkg && !it.isWork }
    }

    fun onForeground(pkg: String) {
        foreground = pkg
        mru.remove(pkg)
        mru.add(0, pkg)
        while (mru.size > 24) thumbs.remove(mru.removeAt(mru.lastIndex))
    }

    fun putThumb(pkg: String, bmp: Bitmap) {
        thumbs[pkg] = bmp.asImageBitmap()
    }

    fun thumb(pkg: String): ImageBitmap? = thumbs[pkg]

    fun remove(pkg: String) {
        mru.remove(pkg); thumbs.remove(pkg)
        if (foreground == pkg) foreground = null
    }

    /** Seed the list from usage history (needs usage access) so it isn't empty after a reboot. */
    fun seedFromUsage(ctx: Context) {
        runCatching {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - 2 * 86_400_000L, now)
            val last = HashMap<String, Long>()
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last[e.packageName] = e.timeStamp
            }
            val ordered = last.entries.sortedByDescending { it.value }.map { it.key }.filter { isTrackable(ctx, it) }
            ordered.forEach { if (it !in mru) mru.add(it) }
            while (mru.size > 24) mru.removeAt(mru.lastIndex)
        }
    }
}
