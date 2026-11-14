package com.glass.launcher.data

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.glass.launcher.GlassApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The launcher draws its own wallpaper so the Liquid Glass effects can refract/blur it.
 * Source: the system wallpaper (default), a photo the user picks, or a generated one.
 */
class WallpaperStore(private val ctx: Context, private val scope: CoroutineScope) {
    private val file = File(ctx.filesDir, "wallpaper.jpg")
    var image by mutableStateOf<ImageBitmap?>(null); private set
    /** Small, heavily blurred copy — every glass surface samples this (cheap, no live blur). */
    var blurred by mutableStateOf<ImageBitmap?>(null); private set
    /** Average luminance 0..1 — used to pick light/dark status bar icons. */
    var luminance by mutableStateOf(0.3f); private set

    init {
        refresh()
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (GlassApp.instance.prefs.followSystemWallpaper) refresh(forceSystem = true)
            }
        }, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED))
    }

    fun refresh(forceSystem: Boolean = false) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val prefs = GlassApp.instance.prefs
                if (prefs.followSystemWallpaper) {
                    systemWallpaper()?.also { save(it) } ?: decodeFile() ?: generate()
                } else {
                    decodeFile() ?: generate().also { save(it) }
                }
            }
            set(bmp)
        }
    }

    fun useSystem() {
        GlassApp.instance.prefs.followSystemWallpaper = true
        file.delete(); refresh(forceSystem = true)
    }

    fun useGenerated(style: Int = 0) {
        GlassApp.instance.prefs.followSystemWallpaper = false
        scope.launch {
            val b = withContext(Dispatchers.Default) { generate(style) }
            withContext(Dispatchers.IO) { save(b) }
            set(b)
        }
    }

    fun usePhoto(uri: Uri, alsoSystem: Boolean) {
        GlassApp.instance.prefs.followSystemWallpaper = false
        scope.launch {
            val b = withContext(Dispatchers.IO) {
                val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
                val fitted = centerCrop(raw)
                save(fitted)
                if (alsoSystem) runCatching { WallpaperManager.getInstance(ctx).setBitmap(fitted) }
                fitted
            } ?: return@launch
            set(b)
        }
    }

    private fun set(b: Bitmap) {
        b.prepareToDraw()
        val bl = blur(b)
        image = b.asImageBitmap()
        blurred = bl.asImageBitmap()
        luminance = averageLuma(b)
    }

    /** Downscale x8 then 3 box-blur passes: looks like a ~60px gaussian, costs a few ms once. */
    private fun blur(src: Bitmap): Bitmap {
        val w = (src.width / 8).coerceAtLeast(8)
        val h = (src.height / 8).coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxPass(px, tmp, w, h, 4, true)
            boxPass(tmp, px, w, h, 4, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) l * w + c else c * w + l }
            for (i in -r..r) { val c = src[idx(i)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val a = src[idx(i - r)]; val b = src[idx(i + r + 1)]
                sr += ((b shr 16) and 255) - ((a shr 16) and 255)
                sg += ((b shr 8) and 255) - ((a shr 8) and 255)
                sb += (b and 255) - (a and 255)
            }
        }
    }

    private fun decodeFile(): Bitmap? = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    private fun save(b: Bitmap) {
        runCatching { file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 94, it) } }
    }

    private fun systemWallpaper(): Bitmap? = try {
        val wm = WallpaperManager.getInstance(ctx)
        val d: Drawable? = wm.drawable ?: wm.peekDrawable()
        d?.let { centerCrop(toBitmap(it)) }
    } catch (_: Throwable) {
        null
    }

    private fun toBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 1080
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 2400
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h); d.draw(Canvas(b)); return b
    }

    private fun screen(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return minOf(m.widthPixels, m.heightPixels) to maxOf(m.widthPixels, m.heightPixels)
    }

    private fun centerCrop(src: Bitmap): Bitmap {
        val (w, h) = screen()
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val sw = (src.width * scale).toInt().coerceAtLeast(w)
        val sh = (src.height * scale).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        return Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
    }

    /** An original abstract "aurora" wallpaper made of soft colour fields. */
    fun generate(style: Int = 0): Bitmap {
        val (w, h) = screen()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val palettes = listOf(
            intArrayOf(0xFF0B1030.toInt(), 0xFF3D5AFE.toInt(), 0xFFB04BFF.toInt(), 0xFFFF7A59.toInt(), 0xFF00C2D1.toInt()),
            intArrayOf(0xFF07131A.toInt(), 0xFF00A884.toInt(), 0xFF2D7BFF.toInt(), 0xFF9CFF6E.toInt(), 0xFF004D66.toInt()),
            intArrayOf(0xFF1A0710.toInt(), 0xFFFF3B6B.toInt(), 0xFFFF9F43.toInt(), 0xFF7C3AED.toInt(), 0xFFFFD166.toInt()),
        )
        val pal = palettes[style.mod(palettes.size)]
        c.drawColor(pal[0])
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val blobs = listOf(
            floatArrayOf(0.15f, 0.18f, 0.85f), floatArrayOf(0.95f, 0.35f, 0.75f),
            floatArrayOf(0.25f, 0.78f, 0.9f), floatArrayOf(0.85f, 0.92f, 0.7f), floatArrayOf(0.55f, 0.52f, 0.55f),
        )
        blobs.forEachIndexed { i, (x, y, r) ->
            val col = pal[1 + i % 4]
            val rad = r * w
            p.shader = RadialGradient(x * w, y * h, rad, intArrayOf(col, Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))), null, Shader.TileMode.CLAMP)
            p.alpha = 220
            c.drawCircle(x * w, y * h, rad, p)
        }
        // Soften everything with a down/up-scale blur and add a gentle vignette.
        val small = Bitmap.createScaledBitmap(b, w / 12, h / 12, true)
        val soft = Bitmap.createScaledBitmap(small, w, h, true)
        val cc = Canvas(soft)
        p.shader = RadialGradient(w / 2f, h / 2f, h * 0.75f, intArrayOf(0x00000000, 0x66000000), null, Shader.TileMode.CLAMP)
        p.alpha = 255
        cc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return soft
    }

    private fun averageLuma(b: Bitmap): Float {
        val s = Bitmap.createScaledBitmap(b, 16, 32, true)
        var sum = 0.0
        for (y in 0 until 8) for (x in 0 until 16) { // top quarter = behind the status bar
            val c = s.getPixel(x, y)
            sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
        }
        return (sum / (16 * 8)).toFloat()
    }
}
