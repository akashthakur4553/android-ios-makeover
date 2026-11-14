package com.glass.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import com.glass.launcher.ui.ControlCentre
import com.glass.launcher.ui.NotificationCentre
import com.glass.launcher.ui.OverlayCtl
import kotlinx.coroutines.flow.collectLatest

/** Control Centre / Notification Centre shown on top of any app, with real background blur. */
class OverlayActivity : ComponentActivity() {
    private var blurOk = false
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        if (Build.VERSION.SDK_INT >= 31) {
            blurOk = windowManager.isCrossWindowBlurEnabled
            if (blurOk) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        val mode = intent.getStringExtra("mode") ?: "cc"
        setContent {
            val scope = rememberCoroutineScope()
            val ctl = remember { OverlayCtl(scope) }
            val anim = if (mode == "nc") ctl.nc else ctl.cc

            fun close() { closing = true; ctl.settle(anim, false) }
            LaunchedEffect(Unit) { ctl.open(anim) }
            LaunchedEffect(Unit) {
                snapshotFlow { anim.value }.collectLatest { v ->
                    val p = v.coerceIn(0f, 1f)
                    window.setDimAmount(if (blurOk) 0.25f * p else 0.6f * p)
                    if (blurOk && Build.VERSION.SDK_INT >= 31) {
                        val lp = window.attributes
                        lp.blurBehindRadius = (p * 70 * resources.displayMetrics.density).toInt()
                        window.attributes = lp
                    }
                    if (v <= 0.001f && anim.targetValue == 0f && (closing || anim.isRunning.not())) {
                        if (closing) { finish(); overridePendingTransition(0, 0) }
                    }
                }
            }
            BackHandler { close() }
            if (mode == "nc") NotificationCentre(anim.value, null, onClose = ::close,
                onDrag = { ctl.drag(anim, it) }, onDragEnd = { if (it > 0.5f) ctl.settle(anim, true) else close() })
            else ControlCentre(anim.value, null, onClose = ::close,
                onDragClose = { ctl.drag(anim, it) }, onDragEnd = { if (it > 0.6f) ctl.settle(anim, true) else close() })
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) { finish(); overridePendingTransition(0, 0) }
    }

    companion object {
        fun show(ctx: Context, mode: String) {
            runCatching {
                ctx.startActivity(Intent(ctx, OverlayActivity::class.java).putExtra("mode", mode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
    }
}
