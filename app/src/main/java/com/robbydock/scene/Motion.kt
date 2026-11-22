package com.robbydock.scene

import android.animation.TimeInterpolator
import android.view.View
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Shared motion: a spring, entrances and staggered reveals. Every call respects the Motion
 * setting: Full moves and fades, Reduced only fades, Off does nothing.
 */
object Motion {

    /**
     * Damped spring as an interpolator. [damping] below 1 overshoots a little; 1 settles without
     * overshoot.
     */
    class Spring(private val damping: Float = 0.72f, private val freq: Float = 1.15f) : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t >= 1f) return 1f
            val w = 2.0 * PI * freq * 2.2
            val z = damping.toDouble()
            if (z >= 1.0) return (1 - (1 + w * t) * exp(-w * t)).toFloat()
            val wd = w * sqrt(1 - z * z)
            return (1 - exp(-z * w * t) * (cos(wd * t) + z * w / wd * kotlin.math.sin(wd * t))).toFloat()
        }
    }

    /** Small overshoot, for focus and pop-ins. */
    val SPRING = Spring(0.62f, 1.1f)

    private const val RISE = 18f

    /** Fades a view in from slightly below. */
    fun enter(v: View, delay: Long = 0L) {
        val ms = Theme.MEDIUM_MS
        if (ms == 0L) return
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = if (Theme.moves) Theme.px(RISE) else 0f
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(ms + 80).setInterpolator(Theme.EASE).start()
    }

    /** Reveals [views] one after another, top to bottom. */
    fun stagger(views: List<View>, step: Long = 28L, max: Int = 10) {
        if (Theme.MEDIUM_MS == 0L) return
        for ((i, v) in views.withIndex()) enter(v, if (Theme.moves) minOf(i, max) * step else 0L)
    }

    /** Reveals the direct children of [group]. */
    fun staggerChildren(group: ViewGroup, step: Long = 28L) = stagger((0 until group.childCount).map { group.getChildAt(it) }, step)

    /** Grows a view in with a soft spring, for avatars and badges. */
    fun pop(v: View, delay: Long = 0L) {
        val ms = Theme.MEDIUM_MS
        if (ms == 0L) return
        v.alpha = 0f
        if (Theme.moves) { v.scaleX = 0.8f; v.scaleY = 0.8f }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(ms * 2 + 120).setInterpolator(SPRING).start()
    }

    private val loops = java.util.WeakHashMap<View, android.animation.Animator>()

    /** Gentle tilt for tiles in edit mode. Each tile starts at a random phase. */
    fun wiggle(v: View) {
        loops.remove(v)?.cancel()
        if (!Theme.moves) return
        val a = android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, -1.2f, 1.2f).apply {
            duration = 260L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        loops[v] = a; a.start()
        a.currentPlayTime = (Math.random() * 260).toLong()
    }

    /** Slow pulse for a listening mic or a waiting state. Reduced keeps it, as a fade. */
    fun breathe(v: View) {
        loops.remove(v)?.cancel()
        if (Theme.MEDIUM_MS == 0L) return
        val a = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.6f).apply {
            duration = 1200L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = Theme.EASE
        }
        loops[v] = a; a.start()
    }

    /** Ends a [wiggle] or [breathe] and settles the view. */
    fun stop(v: View) {
        loops.remove(v)?.cancel()
        v.rotation = 0f
        v.alpha = 1f
    }

    /** A brief sideways shake, for a wrong entry. */
    fun shake(v: View) {
        if (Theme.MEDIUM_MS == 0L) return
        val d = Theme.px(16f)
        v.animate().translationX(d).setDuration(50).withEndAction {
            v.animate().translationX(-d).setDuration(70).withEndAction {
                v.animate().translationX(d / 2).setDuration(60).withEndAction {
                    v.animate().translationX(0f).setDuration(60).start()
                }.start()
            }.start()
        }.start()
    }
}

/** Countdown ring that empties as time runs out. */
class CountdownRing(ctx: android.content.Context) : View(ctx) {
    private val stroke = Theme.px(4f)
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke; color = Theme.withAlpha(Theme.TEXT, 0.18f)
    }
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val box = android.graphics.RectF()
    private var endAt = 0L
    private var total = 1L
    private val tick = object : Runnable {
        override fun run() { invalidate(); if (remaining() > 0) postDelayed(this, 1000L) }
    }

    /** Starts counting down [totalMs], ending [remainingMs] from now. Uses time since boot. */
    fun start(totalMs: Long, remainingMs: Long) {
        total = totalMs.coerceAtLeast(1L)
        endAt = android.os.SystemClock.elapsedRealtime() + remainingMs.coerceIn(0L, total)
        removeCallbacks(tick); post(tick)
    }

    fun stop() { removeCallbacks(tick); endAt = 0L; invalidate() }

    fun remaining() = (endAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (remaining() > 0) post(tick) }

    override fun onDraw(c: android.graphics.Canvas) {
        val h = stroke / 2f
        box.set(h, h, width - h, height - h)
        c.drawOval(box, track)
        val f = remaining() / total.toFloat()
        if (f <= 0f) return
        arc.color = Theme.accent
        c.drawArc(box, -90f, 360f * f, false, arc)
    }
}
