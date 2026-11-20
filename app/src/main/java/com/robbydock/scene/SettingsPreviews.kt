package com.robbydock.scene

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View

/**
 * Glass preview: a fixed scene in the accent and its complement, with glass pills on top drawn
 * the same way as Home's top bar: the blurred scene under a tint at the chosen strength.
 */
class GlassPreview(ctx: Context) : View(ctx) {
    var strength = Theme.glass
        set(v) { field = v; invalidate() }
    private var blur: Bitmap? = null
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = textPaint(26f, Theme.TEXT, Theme.SEMIBOLD)
    private val dim = textPaint(24f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val clip = Path()
    private val shape = Path()
    private val r = RectF()
    private val dst = RectF()
    private val time = android.text.format.DateFormat.getTimeFormat(ctx)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), Theme.px(Theme.R_CARD), Theme.px(Theme.R_CARD), Path.Direction.CW)
        blur = if (w > 0 && h > 0) Images.blurSync(w, h) { scene(it, w.toFloat(), h.toFloat()) } else null
    }

    /** A neighbour of the accent, 30 degrees round toward the cooler side and a little deeper. */
    private fun complement(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(Theme.accent, hsv)
        hsv[0] = (hsv[0] + 330f) % 360f
        hsv[1] = hsv[1].coerceAtLeast(0.3f)
        hsv[2] *= 0.75f
        return Color.HSVToColor(hsv)
    }

    /** The accent lifted toward white, for the small bright shape. */
    private fun light(): Int {
        val a = Theme.accent
        val k = 0.6f
        return Color.rgb(
            (Color.red(a) + (255 - Color.red(a)) * k).toInt(),
            (Color.green(a) + (255 - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (255 - Color.blue(a)) * k).toInt(),
        )
    }

    /** Soft glows with a few crisp shapes, placed under the pills. */
    private fun scene(c: Canvas, w: Float, h: Float) {
        c.drawColor(Theme.bg)
        val two = complement()
        p.shader = RadialGradient(w * 0.78f, h * 0.22f, w * 0.6f, Theme.withAlpha(Theme.accent, 0.75f), Theme.withAlpha(Theme.accent, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * 0.15f, h * 0.9f, w * 0.55f, Theme.withAlpha(two, 0.6f), Theme.withAlpha(two, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // Each shape sits behind a pill and peeks past its edges.
        p.color = Theme.accent
        c.drawCircle(w * 0.36f, h * 0.19f, h * 0.12f, p)
        p.color = light()
        c.drawCircle(w * 0.83f, h * 0.19f, h * 0.07f, p)
        p.color = two
        c.drawCircle(w * 0.2f, h * 0.81f, h * 0.11f, p)
    }

    private fun glass(c: Canvas, l: Float, t: Float, rr: Float, b: Float) {
        r.set(l, t, rr, b)
        val rad = r.height() / 2
        shape.reset()
        shape.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(shape)
        c.drawColor(Theme.bg)
        blur?.let { dst.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawBitmap(it, null, dst, bmp) }
        c.restore()
        p.color = Theme.withAlpha(Theme.float, strength)
        c.drawRoundRect(r, rad, rad, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.save()
        c.clipPath(clip)
        scene(c, w, h)
        val m = w * 0.06f
        val ph = h * 0.17f
        // Search and clock pills along the top, round buttons lower down, as on Home.
        glass(c, m, m, w * 0.58f, m + ph)
        c.drawText("Search", m + ph * 0.5f, baseline(dim, m + ph / 2), dim)
        glass(c, w * 0.66f, m, w - m, m + ph)
        val now = time.format(java.util.Date())
        c.drawText(now, (w * 0.66f + w - m) / 2 - text.measureText(now) / 2, baseline(text, m + ph / 2), text)
        val by = h - m - ph
        for (i in 0 until 3) {
            val x = m + i * (ph + m * 0.5f)
            glass(c, x, by, x + ph, by + ph)
        }
        c.restore()
    }
}

/**
 * Glass strength bar: a wide track with the percent beside it. Left and right change it in
 * steps of 5%.
 */
class GlassBar(ctx: Context) : FocusView(ctx) {
    var value = Theme.glass
        set(v) { field = v; invalidate() }
    var onChange: (Float) -> Unit = {}
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pct = textPaint(34f, Theme.TEXT, Theme.SEMIBOLD)
    override val focusScale = 1.02f

    init { contentDescription = "Glass Strength" }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val d = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        val next = (Math.round((value + d * 0.05f) * 20f) / 20f).coerceIn(Theme.GLASS_MIN, 1f)
        if (next == value) return super.onKeyDown(keyCode, event)
        value = next
        Sounds.move()
        onChange(next)
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        p.color = if (hot()) Theme.float else Theme.elev
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        val label = "${Math.round(value * 100)}%"
        val pad = Theme.px(32f)
        val lw = pct.measureText("100%")
        c.drawText(label, w - pad - pct.measureText(label), baseline(pct, h / 2), pct)
        val l = pad
        val rr = w - pad * 2 - lw
        val th = Theme.px(14f)
        val ty = h / 2 - th / 2
        val k = (value - Theme.GLASS_MIN) / (1f - Theme.GLASS_MIN)
        p.color = Theme.withAlpha(Theme.TEXT, 0.16f)
        c.drawRoundRect(l, ty, rr, ty + th, th / 2, th / 2, p)
        val x = l + (rr - l) * k.coerceIn(0f, 1f)
        p.color = Theme.accent
        c.drawRoundRect(l, ty, x.coerceAtLeast(l + th), ty + th, th / 2, th / 2, p)
        val knob = Theme.px(if (hot()) 18f else 14f)
        c.drawCircle(x.coerceAtLeast(l + knob / 2), h / 2, knob, p)
        drawRing(c, r)
    }
}

/**
 * Start animation: an accent dot grows in while a ring breathes out from it and fades.
 * With Motion off it shows the dot only.
 */
class StartPulse(ctx: Context) : View(ctx) {
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun start() {
        if (!Theme.moves) { t = 1f; invalidate(); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = Theme.EASE
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val dot = Theme.px(36f)
        val grow = (t / 0.45f).coerceIn(0f, 1f)
        p.color = Theme.accent
        c.drawCircle(cx, cy, dot * (0.4f + 0.6f * grow), p)
        val k = ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
        if (k in 0.001f..0.999f) {
            ring.strokeWidth = Theme.px(6f) * (1f - k)
            ring.color = Theme.withAlpha(Theme.accent, 0.6f * (1f - k))
            c.drawCircle(cx, cy, dot + (width / 2f - dot) * k, ring)
        }
    }
}

/**
 * Motion preview: focus steps along a row of tiles, animated the way the chosen level animates
 * Home. Full springs, Reduced fades without movement, Off jumps.
 */
class MotionPreview(ctx: Context) : View(ctx) {
    var mode = 0
        set(v) { if (field != v) { field = v; restart() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val n = 4
    private var index = 0
    private var from = 0
    private var t = 1f
    private var anim: ValueAnimator? = null
    private val step = Runnable { advance() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restart() }
    override fun onDetachedFromWindow() { removeCallbacks(step); anim?.cancel(); super.onDetachedFromWindow() }

    private fun restart() {
        removeCallbacks(step)
        anim?.cancel()
        t = 1f
        invalidate()
        if (isAttachedToWindow) postDelayed(step, 600)
    }

    private fun advance() {
        from = index
        index = (index + 1) % n
        anim?.cancel()
        val ms = when (mode) { 0 -> 560L; 1 -> 140L; else -> 0L }
        if (ms == 0L) { t = 1f; invalidate() } else {
            t = 0f
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                interpolator = if (mode == 0) Motion.SPRING else Theme.EASE
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        postDelayed(step, 1300)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = Theme.surface
        c.drawRoundRect(0f, 0f, w, h, Theme.px(Theme.R_CARD), Theme.px(Theme.R_CARD), p)
        val size = h * 0.56f
        val gap = size * 0.28f
        val x0 = (w - n * size - (n - 1) * gap) / 2
        val rad = size * 0.22f
        for (i in 0 until n) {
            // How focused this tile is: rising on the new tile, falling on the old one.
            val f = when (i) {
                index -> t
                from -> if (mode == 2) 0f else 1f - t
                else -> 0f
            }
            val s = if (mode == 0) 1f + 0.14f * f else 1f
            val cx = x0 + i * (size + gap) + size / 2
            val cy = h / 2
            c.save()
            c.scale(s, s, cx, cy)
            p.color = Theme.elev
            c.drawRoundRect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2, rad, rad, p)
            p.color = Theme.withAlpha(Theme.accent, 0.22f + 0.6f * f.coerceIn(0f, 1f))
            c.drawCircle(cx, cy, size * 0.18f, p)
            val a = f.coerceIn(0f, 1f)
            if (a > 0f) {
                val sw = Theme.px(5f)
                ring.strokeWidth = sw
                ring.color = Theme.withAlpha(Theme.TEXT, a)
                c.drawRoundRect(cx - size / 2 + sw / 2, cy - size / 2 + sw / 2, cx + size / 2 - sw / 2, cy + size / 2 - sw / 2, rad, rad, ring)
            }
            c.restore()
        }
    }
}

/**
 * Sounds demo: a remote's D-pad. While focused, the arrows and OK light it up and play the
 * sounds, even with Sounds off. Back leaves it.
 */
class SoundPad(ctx: Context) : FocusView(ctx) {
    private var lit = -1
    private var glow = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val ok = textPaint(34f, Theme.TEXT, Theme.SEMIBOLD)
    private val arrows = intArrayOf(R.drawable.ic_expand_less, R.drawable.ic_keyboard_arrow_right, R.drawable.ic_expand_more, R.drawable.ic_keyboard_arrow_left)
        .map { ctx.icon(it, Theme.TEXT_DIM) }
    override val focusScale = 1.02f

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val k = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_DOWN -> 2
            KeyEvent.KEYCODE_DPAD_LEFT -> 3
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> 4
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0 || k != 4) press(k)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) true else super.onKeyUp(keyCode, event)

    private fun press(k: Int) {
        Sounds.demo(k == 4)
        lit = k
        anim?.cancel()
        glow = 1f
        invalidate()
        val ms = if (Theme.motion == 2) 0L else 420L
        anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = ms
            startDelay = if (ms == 0L) 180L else 60L
            interpolator = Theme.EASE
            addUpdateListener { glow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - Theme.px(8f)
        p.color = Theme.surface
        c.drawCircle(cx, cy, r, p)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        if (lit in 0..3 && glow > 0f) {
            p.color = Theme.withAlpha(Theme.accent, 0.45f * glow)
            c.drawArc(oval, -135f + lit * 90f, 90f, true, p)
        }
        // Thin gaps between the four directions.
        p.color = Theme.bg
        p.strokeWidth = Theme.px(6f)
        val d = r * 0.7071f
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p)
        c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
        val inner = r * 0.4f
        p.color = Theme.bg
        c.drawCircle(cx, cy, inner + Theme.px(6f), p)
        p.color = if (lit == 4 && glow > 0f) Theme.mix(Theme.elev, Theme.accent, glow) else Theme.elev
        c.drawCircle(cx, cy, inner, p)
        ok.color = if (lit == 4 && glow > 0.5f) Theme.onAccent else Theme.TEXT
        c.drawText("OK", cx - ok.measureText("OK") / 2, baseline(ok, cy), ok)
        val gs = r * 0.26f
        val at = r * 0.7f
        val pos = arrayOf(floatArrayOf(0f, -at), floatArrayOf(at, 0f), floatArrayOf(0f, at), floatArrayOf(-at, 0f))
        for ((i, g) in arrows.withIndex()) {
            g.setTint(if (lit == i && glow > 0.3f) Theme.TEXT else Theme.TEXT_DIM)
            val x = cx + pos[i][0]
            val y = cy + pos[i][1]
            g.setBounds((x - gs / 2).toInt(), (y - gs / 2).toInt(), (x + gs / 2).toInt(), (y + gs / 2).toInt())
            g.draw(c)
        }
        box.set(cx - r, cy - r, cx + r, cy + r)
        drawRing(c, r, box)
    }
}
