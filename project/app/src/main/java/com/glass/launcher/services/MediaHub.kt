package com.glass.launcher.services

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

@Immutable
data class MediaInfo(
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    val pkg: String,
    val duration: Long,
    val position: Long,
    val positionAt: Long,
    val speed: Float,
) {
    fun currentPosition(): Long =
        if (!playing) position else (position + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()).coerceAtMost(duration.coerceAtLeast(0))
}

/** Tracks the active media session (Spotify, YouTube Music, etc.) — needs notification access. */
class MediaHub(private val ctx: Context) {
    var info by mutableStateOf<MediaInfo?>(null); private set
    private var controller: MediaController? = null
    private var lastArtKey: Any? = null
    private var lastArt: ImageBitmap? = null

    private val msm get() = ctx.getSystemService(MediaSessionManager::class.java)

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() = pick()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick() }
    private var listening = false

    fun onListenerReady() {
        if (listening) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, NotificationService.component(ctx))
            listening = true
            pick()
        } catch (_: Throwable) {
        }
    }

    private fun pick() {
        val list = try {
            msm.getActiveSessions(NotificationService.component(ctx))
        } catch (_: Throwable) {
            emptyList()
        }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb)
        }
        update()
    }

    private fun update() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            info = null; return
        }
        val st = c.playbackState
        val artBmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = if (artBmp === lastArtKey) lastArt else artBmp?.let {
            val s = Bitmap.createScaledBitmap(it, 300, 300 * it.height / it.width.coerceAtLeast(1), true)
            s.asImageBitmap()
        }.also { lastArtKey = artBmp; lastArt = it }
        info = MediaInfo(
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Not Playing",
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            art = art,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            pkg = c.packageName,
            duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            position = st?.position ?: 0L,
            positionAt = st?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed ?: 1f,
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)

    fun openApp() {
        val c = controller ?: return
        val pi = c.sessionActivity
        try {
            if (pi != null) pi.send() else ctx.packageManager.getLaunchIntentForPackage(c.packageName)
                ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (_: Throwable) {
        }
    }
}
