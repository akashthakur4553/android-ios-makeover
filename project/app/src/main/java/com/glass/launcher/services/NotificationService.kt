package com.glass.launcher.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.glass.launcher.GlassApp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class NotifItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val intent: PendingIntent?,
    val largeIcon: ImageBitmap?,
    val clearable: Boolean,
    val isMessage: Boolean,
)

class NotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        _connected.value = true
        publish()
        GlassApp.instance.media.onListenerReady()
    }

    override fun onListenerDisconnected() {
        instance = null
        _connected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        publish()
        val n = sbn.notification
        val silent = n.flags and Notification.FLAG_ONGOING_EVENT != 0 ||
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && sbn.postTime != n.`when`
        if (!silent) convert(sbn)?.let { _posted.tryEmit(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = publish()

    private fun publish() {
        val list = try {
            activeNotifications?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val byGroup = list.groupBy { it.groupKey }
        _items.value = list.filter { s ->
            val f = s.notification.flags
            if (f and Notification.FLAG_ONGOING_EVENT != 0 || f and Notification.FLAG_FOREGROUND_SERVICE != 0) return@filter false
            // Hide a group summary when its children are visible.
            if (f and Notification.FLAG_GROUP_SUMMARY != 0 && (byGroup[s.groupKey]?.size ?: 0) > 1) return@filter false
            s.packageName != packageName
        }.mapNotNull { convert(it) }.sortedByDescending { it.time }
    }

    private fun convert(s: StatusBarNotification): NotifItem? {
        val n = s.notification
        val ex = n.extras
        var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val last = msgs.last() as? android.os.Bundle
            last?.getCharSequence("text")?.let { text = it.toString() }
            ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.let { title = it.toString() }
        }
        if (title.isBlank() && text.isBlank()) return null
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.packageName, 0)).toString()
        } catch (_: Throwable) {
            s.packageName
        }
        val large = try {
            n.getLargeIcon()?.loadDrawable(this)?.let { small(it) }
        } catch (_: Throwable) {
            null
        }
        return NotifItem(
            key = s.key, pkg = s.packageName, app = app, title = title, text = text,
            time = s.postTime, intent = n.contentIntent, largeIcon = large,
            clearable = s.isClearable, isMessage = n.category == Notification.CATEGORY_MESSAGE || msgs != null,
        )
    }

    private fun small(d: Drawable): ImageBitmap {
        if (d is BitmapDrawable && d.bitmap != null) return Bitmap.createScaledBitmap(d.bitmap, 120, 120, true).asImageBitmap()
        val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 120, 120); d.draw(Canvas(b))
        return b.asImageBitmap()
    }

    companion object {
        private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
        val items: StateFlow<List<NotifItem>> = _items
        private val _posted = MutableSharedFlow<NotifItem>(extraBufferCapacity = 8)
        val posted: SharedFlow<NotifItem> = _posted
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
        @Volatile var instance: NotificationService? = null; private set

        fun component(ctx: Context) = ComponentName(ctx, NotificationService::class.java)

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(component(ctx).flattenToString())
        }

        fun dismiss(key: String) {
            runCatching { instance?.cancelNotification(key) }
        }

        fun dismissAll() {
            items.value.filter { it.clearable }.forEach { dismiss(it.key) }
        }

        fun open(ctx: Context, n: NotifItem) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val o = android.app.ActivityOptions.makeBasic()
                    @Suppress("DEPRECATION")
                    o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    n.intent?.send(ctx, 0, null, null, null, null, o.toBundle())
                } else n.intent?.send()
                if (n.clearable) dismiss(n.key)
            } catch (_: Throwable) {
                ctx.packageManager.getLaunchIntentForPackage(n.pkg)?.let {
                    ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        /** Do Not Disturb toggle (works because we are a notification listener). */
        fun setDnd(ctx: Context, on: Boolean) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val filter = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(filter)
            else instance?.requestInterruptionFilter(if (on) INTERRUPTION_FILTER_PRIORITY else INTERRUPTION_FILTER_ALL)
        }

        fun isDnd(ctx: Context): Boolean =
            ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
    }
}
