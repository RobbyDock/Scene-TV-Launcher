package com.robbydock.scene

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Focus touches for Home's cards and tiles at full motion: a card tips the way focus moved and
 * settles, and a band of light crosses it.
 */
object Polish {
    private const val TILT_MS = 620L
    private const val SHEEN_MS = 700L

    /** Tips [v] towards the side focus came from, then settles. */
    fun tilt(v: View, movedRight: Boolean) {
        if (!Theme.moves) return
        v.cameraDistance = Theme.px(1400f) * v.resources.displayMetrics.density
        val s = if (movedRight) 1f else -1f
        ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofKeyframe(View.ROTATION_Y,
            Keyframe.ofFloat(0f, 7f * s), Keyframe.ofFloat(0.55f, -1.5f * s), Keyframe.ofFloat(1f, 0f),
        )).apply { duration = TILT_MS; interpolator = Theme.EASE; start() }
    }

    /** Sweeps a soft band of light across the top [square] part of [v], or all of it. */
    fun sheen(v: View, square: Boolean, radius: Float) {
        if (!Theme.moves) return
        val d = Sheen(square, radius)
        v.foreground = d
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHEEN_MS
            interpolator = Theme.EASE
            addUpdateListener { d.t = it.animatedValue as Float; d.invalidateSelf() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (v.foreground === d) v.foreground = null }
            })
            start()
        }
    }

    private class Sheen(private val square: Boolean, private val radius: Float) : Drawable() {
        var t = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val m = Matrix()
        private val band = LinearGradient(-0.5f, 0f, 0.5f, 0f,
            intArrayOf(Color.TRANSPARENT, Theme.withAlpha(Color.WHITE, 0.16f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)

        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat()
            val h = if (square) w else bounds.height().toFloat()
            clip.reset(); clip.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
            // The band runs at 105 degrees, from past the right edge to past the left.
            m.reset()
            m.setScale(w * 0.6f, w * 0.6f)
            m.postRotate(15f)
            m.postTranslate(w * (1.4f - 1.8f * t), h / 2)
            band.setLocalMatrix(m)
            p.shader = band
            c.save(); c.clipPath(clip); c.drawRect(0f, 0f, w, h, p); c.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * The art's main colour: the strongest saturated hue band, averaged. Null for grey art.
     */
    fun artColor(b: android.graphics.Bitmap): Int? = try {
        val s = android.graphics.Bitmap.createScaledBitmap(b, 48, 27, true)
        val px = IntArray(48 * 27)
        s.getPixels(px, 0, 48, 0, 0, 48, 27)
        if (s !== b) s.recycle()
        val band = Array(12) { DoubleArray(4) }
        val hsv = FloatArray(3)
        for (c in px) {
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
            val k = band[(hsv[0] / 30f).toInt().coerceIn(0, 11)]
            val w = (hsv[1] * hsv[2]).toDouble()
            k[0] += Color.red(c) * w; k[1] += Color.green(c) * w; k[2] += Color.blue(c) * w; k[3] += w
        }
        val best = band.maxBy { it[3] }
        if (best[3] > 4) Color.rgb((best[0] / best[3]).toInt(), (best[1] / best[3]).toInt(), (best[2] / best[3]).toInt()) else null
    } catch (e: Exception) {
        null
    }
}
