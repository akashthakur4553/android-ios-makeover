package com.glass.launcher

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glass.launcher.ui.HomeScreen
import com.glass.launcher.ui.HomeUi
import com.glass.launcher.ui.OverlayCtl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    private val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val ui = HomeUi()
    private var isResumedNow = false
    private var overlays: OverlayCtl? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) GlassApp.instance.weather.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Safety: if Glass crashed repeatedly, step aside and let the original launcher run.
        if (CrashGuard.inSafeMode(this)) {
            Toast.makeText(this, "Glass had a problem — using the original launcher.", Toast.LENGTH_LONG).show()
            if (StockLauncher.launch(this)) { finish(); return }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val app = GlassApp.instance
        setContent {
            val scope = rememberCoroutineScope()
            val ov = remember { OverlayCtl(scope) }
            overlays = ov
            val light by remember { androidx.compose.runtime.derivedStateOf { app.wallpaper.luminance > 0.62f && ov.amount < 0.2f } }
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = light
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = light
            }
            LaunchedEffect(Unit) {
                // A healthy run for a while clears the crash counter.
                delay(15_000); CrashGuard.clear(this@MainActivity)
            }
            HomeScreen(homePresses, ov, ui) { perm -> permission.launch(perm) }
        }
        // Compose draws the wallpaper edge-to-edge; drop the window fill to avoid a full-screen overdraw.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on the home screen -> close things / go to page 1 (like iOS).
        val alreadyHome = isResumedNow || hasWindowFocus() || System.currentTimeMillis() - lastPausedAt < 400
        if (alreadyHome && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homePresses.tryEmit(Unit)
        } else {
            // Coming back from an app: tidy up transient UI.
            ui.menu = null; ui.alert = null; ui.group = null
            GlassApp.instance.home.closeAllTransient()
            overlays?.closeAll()
        }
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        GlassApp.instance.weather.refreshIfStale()
    }

    private var lastPausedAt = 0L
    private var lastStoppedApp = false

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        lastPausedAt = if (hasWindowFocus()) System.currentTimeMillis() else 0L
        overlays?.closeAll()
        ui.menu = null
    }
}
