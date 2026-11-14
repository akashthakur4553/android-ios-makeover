package com.glass.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Renders every app icon into the same iOS-style continuous-corner "squircle". */
object IconFactory {
    const val SIZE = 192

    /** Superellipse |x|^n + |y|^n = 1 with n=5 — very close to Apple's continuous icon corners. */
    fun squirclePath(w: Float, h: Float = w, n: Double = 5.0, left: Float = 0f, top: Float = 0f): Path {
        val p = Path()
        val rx = w / 2f
        val ry = h / 2f
        val steps = 240
        for (i in 0..steps) {
            val t = 2 * PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = left + rx + rx * (sign(c) * abs(c).pow(2.0 / n)).toFloat()
            val y = top + ry + ry * (sign(s) * abs(s).pow(2.0 / n)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private val maskPath by lazy { squirclePath(SIZE.toFloat()).apply { fillType = Path.FillType.INVERSE_WINDING } }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    fun render(d: Drawable): Bitmap {
        val s = SIZE
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (d is AdaptiveIconDrawable) {
            // Adaptive layers are 108dp with the middle 72dp visible -> extend 25% on each side.
            val pad = s / 4
            val bg = d.background
            if (bg != null) {
                bg.setBounds(-pad, -pad, s + pad, s + pad); bg.draw(c)
            } else c.drawColor(Color.WHITE)
            d.foreground?.let { it.setBounds(-pad, -pad, s + pad, s + pad); it.draw(c) }
        } else {
            val raw = toBitmap(d, s)
            if (isFullBleed(raw)) {
                c.drawBitmap(raw, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                // Legacy icon: place it on a white tile like iOS does for non-square art.
                c.drawColor(Color.WHITE)
                val inset = (s * 0.16f).toInt()
                val small = Bitmap.createScaledBitmap(raw, s - inset * 2, s - inset * 2, true)
                c.drawBitmap(small, inset.toFloat(), inset.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        c.drawPath(maskPath, clearPaint)
        return out
    }

    private fun toBitmap(d: Drawable, s: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return Bitmap.createScaledBitmap(d.bitmap, s, s, true)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, s, s); d.draw(c)
        return b
    }

    /** True when the art already covers the whole tile (opaque near all four corners). */
    private fun isFullBleed(b: Bitmap): Boolean {
        val i = (b.width * 0.12f).toInt()
        val pts = listOf(i to i, b.width - 1 - i to i, i to b.height - 1 - i, b.width - 1 - i to b.height - 1 - i)
        return pts.all { (x, y) -> Color.alpha(b.getPixel(x, y)) > 230 }
    }
}
