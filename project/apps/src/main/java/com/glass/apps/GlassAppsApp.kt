package com.glass.apps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder

class GlassAppsApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(CH_ALARM, "Alarms & Timers", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alarm)
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Clock", NotificationManager.IMPORTANCE_LOW))
        // Re-arm alarms after an app update.
        com.glass.apps.clock.AlarmStore.rescheduleAll(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.crossfade(180).build()

    companion object {
        const val CH_ALARM = "alarm_v1"
        const val CH_INFO = "clock_info"
        lateinit var instance: GlassAppsApp
            private set
    }
}
