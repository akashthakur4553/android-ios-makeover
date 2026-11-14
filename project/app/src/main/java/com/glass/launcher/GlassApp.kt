package com.glass.launcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.glass.launcher.data.AppRepository
import com.glass.launcher.data.HomeStore
import com.glass.launcher.data.Prefs
import com.glass.launcher.data.UsageTracker
import com.glass.launcher.data.WallpaperStore
import com.glass.launcher.data.WeatherRepo
import com.glass.launcher.services.MediaHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.system.exitProcess

class GlassApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var prefs: Prefs
    lateinit var repo: AppRepository
    lateinit var usage: UsageTracker
    lateinit var home: HomeStore
    lateinit var wallpaper: WallpaperStore
    lateinit var weather: WeatherRepo
    lateinit var media: MediaHub

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashFallback()
        prefs = Prefs(this)
        usage = UsageTracker(this)
        repo = AppRepository(this, scope)
        home = HomeStore(this, repo, scope)
        wallpaper = WallpaperStore(this, scope)
        weather = WeatherRepo(this, scope)
        media = MediaHub(this)
    }

    /**
     * Safety net: if Glass ever crashes, immediately hand the user over to the phone's original
     * launcher so the phone is never left without a working home screen.
     */
    private fun installCrashFallback() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, "last_crash.txt").writeText(Log.getStackTraceString(error))
                CrashGuard.recordCrash(this)
                StockLauncher.launch(this)
            } catch (_: Throwable) {
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid()); exitProcess(10)
            }
        }
    }

    companion object {
        lateinit var instance: GlassApp
            private set
    }
}

object CrashGuard {
    private const val WINDOW = 120_000L

    fun recordCrash(ctx: Context) {
        val p = ctx.getSharedPreferences("safety", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val list = recent(ctx) + now
        p.edit().putString("crashes", list.joinToString(",")).commit()
    }

    private fun recent(ctx: Context): List<Long> {
        val now = System.currentTimeMillis()
        return (ctx.getSharedPreferences("safety", Context.MODE_PRIVATE).getString("crashes", "") ?: "")
            .split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < WINDOW }
    }

    /** 3 crashes within 2 minutes = safe mode: forward HOME to the stock launcher. */
    fun inSafeMode(ctx: Context) = recent(ctx).size >= 3

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("safety", Context.MODE_PRIVATE).edit().remove("crashes").apply()
    }
}

object StockLauncher {
    private val preferred = listOf("com.mi.android.globallauncher", "com.miui.home", "com.google.android.apps.nexuslauncher")

    fun intent(ctx: Context): Intent? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val list = ctx.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .map { it.activityInfo }
            .filter { it.packageName != ctx.packageName && it.packageName != "com.android.settings" }
        val pick = preferred.firstNotNullOfOrNull { p -> list.firstOrNull { it.packageName == p } } ?: list.firstOrNull()
            ?: return null
        return Intent(home).setClassName(pick.packageName, pick.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun launch(ctx: Context): Boolean = try {
        val i = intent(ctx) ?: return false
        ctx.startActivity(i); true
    } catch (_: Throwable) {
        false
    }
}
