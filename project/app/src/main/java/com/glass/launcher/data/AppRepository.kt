package com.glass.launcher.data

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.glass.launcher.GlassApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AppRepository(private val ctx: Context, private val scope: CoroutineScope) {
    private val la = ctx.getSystemService(LauncherApps::class.java)
    private val um = ctx.getSystemService(UserManager::class.java)
    private val iconDir = File(ctx.cacheDir, "icons").apply { mkdirs() }
    private val memIcons = ConcurrentHashMap<String, ImageBitmap>()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    @Volatile var byKey: Map<AppKey, AppEntry> = emptyMap(); private set

    private var reloadJob: Job? = null

    init {
        la.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = reload()
            override fun onPackageAdded(p: String, u: UserHandle) = reload()
            override fun onPackageChanged(p: String, u: UserHandle) = reload()
            override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
            override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
        })
        reload(0)
    }

    fun reload(debounceMs: Long = 400) {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(debounceMs)
            val list = withContext(Dispatchers.Default) { load() }
            byKey = list.associateBy { it.key }
            _loaded.value = true
            _apps.value = list
        }
    }

    private suspend fun load(): List<AppEntry> = kotlinx.coroutines.coroutineScope {
        val me = Process.myUserHandle()
        val density = ctx.resources.displayMetrics.densityDpi
        val infos = um.userProfiles.flatMap { u -> runCatching { la.getActivityList(null, u) }.getOrDefault(emptyList()) }
            .filter { it.componentName.className != "com.glass.launcher.MainActivity" }
        infos.map { info ->
            async(Dispatchers.Default) {
                runCatching { entry(info, me, density) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun entry(info: LauncherActivityInfo, me: UserHandle, density: Int): AppEntry {
        val user = info.user
        val serial = um.getSerialNumberForUser(user)
        val key = AppKey.of(info.componentName.packageName, info.componentName.className, serial)
        val ai: ApplicationInfo = info.applicationInfo
        val pkgInfo = runCatching { ctx.packageManager.getPackageInfo(ai.packageName, 0) }.getOrNull()
        val updated = pkgInfo?.lastUpdateTime ?: 0L
        val cacheName = ("v2:" + key.raw + "@" + updated).hashCode().toString(16) + ".png"
        val icon = memIcons[cacheName] ?: run {
            val f = File(iconDir, cacheName)
            val bmp: Bitmap = (if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null) ?: run {
                val d = info.getIcon(density) ?: ctx.packageManager.defaultActivityIcon
                // Some system icons reference theme colours (?attr/...) — resolve them with the app's own theme.
                runCatching {
                    if (d.canApplyTheme()) {
                        val pc = ctx.createPackageContext(ai.packageName, Context.CONTEXT_IGNORE_SECURITY)
                        val th = pc.resources.newTheme()
                        th.applyStyle(if (info.activityInfo.theme != 0) info.activityInfo.theme else ai.theme, true)
                        d.applyTheme(th)
                    }
                }
                val raw = IconFactory.render(d)
                val badged = if (user != me) {
                    val bd = ctx.packageManager.getUserBadgedIcon(android.graphics.drawable.BitmapDrawable(ctx.resources, raw), user)
                    IconFactory.render(bd)
                } else raw
                runCatching { f.outputStream().use { badged.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                badged
            }
            bmp.prepareToDraw()
            bmp.asImageBitmap().also { memIcons[cacheName] = it }
        }
        return AppEntry(
            key = key,
            label = info.label?.toString()?.trim().orEmpty().ifEmpty { ai.packageName },
            component = info.componentName,
            user = user,
            icon = icon,
            installTime = pkgInfo?.firstInstallTime ?: 0L,
            category = if (ai.packageName == "com.glass.apps") when {
                info.componentName.className.contains("photos") || info.componentName.className.contains("camera") -> AppCategory.Creativity
                info.componentName.className.contains("music") -> AppCategory.Entertainment
                info.componentName.className.contains("messages") || info.componentName.className.contains("contacts") -> AppCategory.Social
                else -> AppCategory.Utilities
            } else AppCategory.of(ai.packageName, ai),
            isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
            isWork = user != me,
        )
    }

    // ---------- actions ----------

    fun launch(e: AppEntry, view: View?, bounds: Rect?) {
        GlassApp.instance.usage.record(e.key.raw)
        val opts: Bundle? = if (view != null && bounds != null) {
            ActivityOptions.makeScaleUpAnimation(view, bounds.left, bounds.top, bounds.width(), bounds.height()).toBundle()
        } else null
        try {
            la.startMainActivity(e.component, e.user, bounds, opts)
        } catch (_: Throwable) {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(e.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
            }
        }
    }

    fun appInfo(e: AppEntry) {
        runCatching { la.startAppDetailsActivity(e.component, e.user, null, null) }.onFailure {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.key.pkg}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun uninstall(e: AppEntry) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.key.pkg}"))
                .putExtra(Intent.EXTRA_USER, e.user).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun canUninstall(e: AppEntry) = !e.isSystem && e.key.pkg != ctx.packageName

    fun shortcuts(e: AppEntry): List<ShortcutInfo> = try {
        if (!la.hasShortcutHostPermission()) emptyList() else {
            val q = LauncherApps.ShortcutQuery().setPackage(e.key.pkg).setActivity(e.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            (la.getShortcuts(q, e.user) ?: emptyList())
                .filter { it.isEnabled }
                .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank }))
                .take(4)
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun shortcutIcon(s: ShortcutInfo): ImageBitmap? = try {
        la.getShortcutIconDrawable(s, ctx.resources.displayMetrics.densityDpi)?.let { IconFactory.render(it).asImageBitmap() }
    } catch (_: Throwable) {
        null
    }

    fun startShortcut(s: ShortcutInfo, bounds: Rect?) {
        runCatching { la.startShortcut(s, bounds, null) }
    }

    fun isInstalled(pkg: String) = try {
        ctx.packageManager.getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Finds the first installed app matching any of the given packages (in order). */
    fun find(vararg pkgs: String): AppEntry? {
        val list = _apps.value
        for (p in pkgs) list.firstOrNull { it.key.pkg == p && !it.isWork }?.let { return it }
        return null
    }

    fun forIntent(intent: Intent): AppEntry? {
        val ri = ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        if (pkg == "android") return null
        val list = _apps.value.filter { it.key.pkg == pkg && !it.isWork }
        // Prefer the launcher entry that looks like the dialer/camera itself (e.g. TwelveKeyDialer over Contacts).
        return list.firstOrNull { it.component.className == ri.activityInfo.name }
            ?: list.firstOrNull { it.component.className.contains("Dial", true) }
            ?: list.firstOrNull()
    }
}
