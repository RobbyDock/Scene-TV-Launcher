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

/**
 * Export and restore, told with the pieces that move: your profile, your colours and your apps.
 * Export lifts each piece off the TV and files it into the backup. Restore takes them out of the
 * backup and puts them back on the TV. Plays once, then rests on the finished state.
 */
class BackupArt(ctx: Context, private val export: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()
    private val caption = textPaint(22f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val mono = textPaint(18f, Theme.TEXT_DIM, android.graphics.Typeface.MONOSPACE)
    private val check = ctx.icon(R.drawable.ic_check, Theme.onAccent)
    private var start = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start = android.os.SystemClock.uptimeMillis()
    }

    private fun ease(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
    private fun easeInOut(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
    private fun pop(x: Float): Float { val y = x - 1f; return 1f + 2.5f * y * y * y + 1.5f * y * y }

    /** Travel progress of piece [i], 0 to 1. */
    private fun move(t: Long, i: Int) = ((t - LEAD - i * STEP) / MOVE.toFloat()).coerceIn(0f, 1f)

    /** One piece: 0 profile, 1 colours, 2 apps. Drawn around its centre at unit size [u]. */
    private fun piece(c: Canvas, kind: Int, cx: Float, cy: Float, u: Float, a: Float) {
        val alpha = (255 * a).toInt()
        when (kind) {
            0 -> {
                Theme.accentFill(p, cx - u, cy - u, cx + u, cy + u); p.alpha = alpha
                c.drawCircle(cx, cy, u, p); p.shader = null
                p.color = Theme.withAlpha(Color.WHITE, 0.85f * a)
                c.drawCircle(cx, cy - u * 0.22f, u * 0.3f, p)
                c.drawRoundRect(cx - u * 0.48f, cy + u * 0.18f, cx + u * 0.48f, cy + u * 0.62f, u * 0.3f, u * 0.3f, p)
            }
            1 -> {
                val cols = intArrayOf(Theme.accent, Theme.duo(Theme.accent), Theme.mix(Theme.TEXT, Theme.accent, 0.2f))
                for (k in 0 until 3) {
                    p.color = Theme.withAlpha(cols[k], a)
                    c.drawCircle(cx + (k - 1) * u * 0.9f, cy, u * 0.42f, p)
                }
            }
            else -> for (k in 0 until 3) {
                val x0 = cx + (k - 1) * u * 0.95f - u * 0.4f
                if (k == 0) Theme.accentFill(p, x0, cy - u * 0.3f, x0 + u * 0.8f, cy + u * 0.3f) else p.color = Theme.TEXT_DIM
                p.alpha = if (k == 0) alpha else (alpha * 0.55f).toInt()
                c.drawRoundRect(x0, cy - u * 0.3f, x0 + u * 0.8f, cy + u * 0.3f, u * 0.14f, u * 0.14f, p)
                p.shader = null
            }
        }
        p.alpha = 255
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = if (Theme.motion == 2) END else android.os.SystemClock.uptimeMillis() - start
        val u = h * 0.075f
        val mid = h * 0.42f

        // TV on the left.
        val tw = w * 0.44f; val th = tw * 9f / 16f
        val tx = w * 0.03f; val ty = mid - th / 2
        shadow(c, tx + tw / 2, ty + th + h * 0.08f, tw * 0.5f)
        p.color = 0xFF0E0B10.toInt()
        c.drawRect(tx + tw / 2 - tw * 0.02f, ty + th, tx + tw / 2 + tw * 0.02f, ty + th + h * 0.06f, p)
        c.drawRoundRect(tx + tw / 2 - tw * 0.14f, ty + th + h * 0.05f, tx + tw / 2 + tw * 0.14f, ty + th + h * 0.07f, h * 0.01f, h * 0.01f, p)
        r.set(tx, ty, tx + tw, ty + th)
        c.drawRoundRect(r, tw * 0.03f, tw * 0.03f, p)
        val sx = tx + tw * 0.018f; val sy = ty + tw * 0.018f; val sw = tw - tw * 0.036f; val sh = th - tw * 0.036f
        r.set(sx, sy, sx + sw, sy + sh)
        // Restore wakes the screen with the first piece; export starts with it lit.
        val lit = if (export) 1f else ease(((t - LEAD - MOVE * 0.7f) / 500f).coerceIn(0f, 1f))
        p.color = 0xFF070508.toInt()
        c.drawRoundRect(r, tw * 0.02f, tw * 0.02f, p)
        p.shader = android.graphics.LinearGradient(sx, sy, sx + sw, sy + sh, Theme.withAlpha(Theme.surface, lit), Theme.withAlpha(Theme.bg, lit), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, tw * 0.02f, tw * 0.02f, p)
        p.shader = RadialGradient(sx + sw * 0.25f, sy + sh * 0.2f, sw * 0.6f, Theme.withAlpha(Theme.accent, 0.22f * lit), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, tw * 0.02f, tw * 0.02f, p)
        p.shader = null
        val tvSlots = arrayOf(
            floatArrayOf(sx + sw * 0.16f, sy + sh * 0.26f),
            floatArrayOf(sx + sw * 0.72f, sy + sh * 0.26f),
            floatArrayOf(sx + sw * 0.5f, sy + sh * 0.72f),
        )

        // Backup file on the right: a page with a folded corner, a header and three rows.
        val fh = h * 0.8f; val fw = fh * 0.8f
        val fx = w * 0.97f - fw; val fy = mid - fh / 2
        shadow(c, fx + fw / 2, fy + fh + h * 0.05f, fw * 0.6f)
        val fold = fw * 0.2f
        path.reset()
        path.moveTo(fx + fw * 0.08f, fy)
        path.lineTo(fx + fw - fold, fy); path.lineTo(fx + fw, fy + fold); path.lineTo(fx + fw, fy + fh - fw * 0.08f)
        path.quadTo(fx + fw, fy + fh, fx + fw - fw * 0.08f, fy + fh); path.lineTo(fx + fw * 0.08f, fy + fh)
        path.quadTo(fx, fy + fh, fx, fy + fh - fw * 0.08f); path.lineTo(fx, fy + fw * 0.08f)
        path.quadTo(fx, fy, fx + fw * 0.08f, fy); path.close()
        p.shader = android.graphics.LinearGradient(fx, fy, fx + fw, fy + fh, Theme.mix(Theme.elev, Color.WHITE, 0.05f), Theme.surface, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        path.reset()
        path.moveTo(fx + fw - fold, fy); path.lineTo(fx + fw - fold, fy + fold * 0.8f)
        path.quadTo(fx + fw - fold, fy + fold, fx + fw - fold * 0.8f, fy + fold); path.lineTo(fx + fw, fy + fold); path.close()
        Theme.accentFill(p, fx + fw - fold, fy, fx + fw, fy + fold)
        c.drawPath(path, p)
        p.shader = null
        mono.textSize = fh * 0.075f
        c.drawText("{ }", fx + fw * 0.12f, fy + fh * 0.16f, mono)
        p.color = Theme.withAlpha(Theme.TEXT_DIM, 0.35f)
        c.drawRoundRect(fx + fw * 0.12f + mono.measureText("{ } "), fy + fh * 0.11f, fx + fw * 0.66f, fy + fh * 0.145f, fh * 0.02f, fh * 0.02f, p)
        val fileSlots = Array(3) { i -> floatArrayOf(fx + fw * 0.5f, fy + fh * (0.36f + i * 0.2f)) }
        for (i in 0 until 3) {
            // Empty rows are dashed; filled rows get a soft plate.
            val (cx, cy) = fileSlots[i].let { it[0] to it[1] }
            r.set(fx + fw * 0.12f, cy - fh * 0.075f, fx + fw * 0.88f, cy + fh * 0.075f)
            val filled = if (export) move(t, i) >= 1f else move(t, i) <= 0f
            if (filled) {
                p.color = Theme.withAlpha(Theme.TEXT, 0.06f)
                c.drawRoundRect(r, fh * 0.03f, fh * 0.03f, p)
            } else {
                p.style = Paint.Style.STROKE; p.strokeWidth = Theme.px(1.5f)
                p.color = Theme.withAlpha(Theme.TEXT_DIM, 0.3f)
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(Theme.px(6f), Theme.px(5f)), 0f)
                c.drawRoundRect(r, fh * 0.03f, fh * 0.03f, p)
                p.pathEffect = null; p.style = Paint.Style.FILL
            }
        }

        // Pieces at rest, and in flight.
        for (i in 0 until 3) {
            val m = move(t, i)
            val from = if (export) tvSlots[i] else fileSlots[i]
            val to = if (export) fileSlots[i] else tvSlots[i]
            // Export copies, so a faint piece stays on the TV.
            if (export) piece(c, i, tvSlots[i][0], tvSlots[i][1], u * 0.8f, if (m > 0f) 0.35f else 1f)
            if (m <= 0f) { if (!export) piece(c, i, from[0], from[1], u * 0.8f, 1f); continue }
            if (m >= 1f) {
                // Landing flash on the slot.
                val flash = ((t - LEAD - i * STEP - MOVE) / 450f).coerceIn(0f, 1f)
                if (flash < 1f) {
                    p.color = Theme.withAlpha(Theme.accent, 0.22f * (1f - flash))
                    c.drawCircle(to[0], to[1], u * (0.6f + 0.7f * ease(flash)), p)
                }
                piece(c, i, to[0], to[1], u * 0.8f, 1f)
                continue
            }
            // A short trail of fading copies behind the piece.
            for (k in 2 downTo 0) {
                val ek = easeInOut((m - k * 0.025f).coerceIn(0f, 1f))
                val lk = h * 0.28f * kotlin.math.sin(ek * Math.PI).toFloat()
                val x = from[0] + (to[0] - from[0]) * ek
                val y = from[1] + (to[1] - from[1]) * ek - lk
                val grow = 1f + 0.35f * kotlin.math.sin(ek * Math.PI).toFloat()
                piece(c, i, x, y, u * 0.8f * grow, if (k == 0) 1f else 0.12f / k)
            }
        }

        // Done: a check on the destination, with a ring that spreads and fades.
        val pd = ((t - DONE) / 420f).coerceIn(0f, 1f)
        if (pd > 0f) {
            val bx = if (export) fx + fw else tx + tw
            val by = if (export) fy + fold * 1.6f else ty
            val br = u * 0.75f * pop(pd)
            val wave = ((t - DONE) / 900f).coerceIn(0f, 1f)
            if (wave < 1f) {
                p.style = Paint.Style.STROKE; p.strokeWidth = Theme.px(3f)
                p.color = Theme.withAlpha(Theme.accent, 0.6f * (1f - wave))
                c.drawCircle(bx, by, u * 0.75f * (1f + 1.3f * ease(wave)), p)
                p.style = Paint.Style.FILL
            }
            Theme.accentFill(p, bx - br, by - br, bx + br, by + br)
            c.drawCircle(bx, by, br, p)
            p.shader = null
            val cs = (br * 1.2f).toInt()
            check.setBounds((bx - cs / 2).toInt(), (by - cs / 2).toInt(), (bx + cs / 2).toInt(), (by + cs / 2).toInt())
            check.draw(c)
        }

        // Captions.
        val cy = h * 0.97f
        val tvName = "This TV"
        c.drawText(tvName, tx + tw / 2 - caption.measureText(tvName) / 2, cy, caption)
        c.drawText(Store.BACKUP, fx + fw / 2 - caption.measureText(Store.BACKUP) / 2, cy, caption)

        if (t < END) postInvalidateOnAnimation()
    }

    private fun shadow(c: Canvas, cx: Float, cy: Float, rad: Float) {
        c.save(); c.scale(1f, 0.14f, cx, cy)
        p.shader = RadialGradient(cx, cy, rad, Theme.withAlpha(Color.BLACK, 0.5f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, p)
        p.shader = null
        c.restore()
    }

    private companion object {
        const val LEAD = 500L
        const val STEP = 650L
        const val MOVE = 900L
        const val DONE = LEAD + 2 * STEP + MOVE + 150L
        const val END = DONE + 1000L
    }
}

/**
 * A small TV whose Home screen builds itself, one setup step at a time: the profile, the Home
 * layout, weather, voice, the screensaver and Stremio. Plays once, then rests on the finished
 * screen. With motion off it shows the finished screen straight away.
 */
class SetupArt(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val names = listOf("Profile", "Home", "Weather", "Voice", "Screensaver", "Stremio")
    private val label = textPaint(22f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val clock = textPaint(20f, Theme.TEXT, Theme.SEMIBOLD)
    private val weather = ctx.icon(R.drawable.ic_partly_cloudy_day, Theme.TEXT)
    private val mic = ctx.icon(R.drawable.ic_mic, Theme.TEXT)
    private val moon = ctx.icon(R.drawable.ic_bedtime, Theme.TEXT)
    private val moonSmall = ctx.icon(R.drawable.ic_bedtime, Theme.TEXT_DIM)
    private val stremio = ctx.getDrawable(R.drawable.ic_stremio)!!
    private val check = ctx.icon(R.drawable.ic_check, Theme.onAccent)
    private var start = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start = android.os.SystemClock.uptimeMillis()
    }

    /** Progress of one step, 0 to 1. Each piece takes [MOVE] within its [STEP] slot. */
    private fun step(t: Long, i: Int, delay: Long = 0L) = ((t - LEAD - i * STEP - delay) / MOVE.toFloat()).coerceIn(0f, 1f)

    private fun ease(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

    /** Ease out with a small overshoot, for things that pop in. */
    private fun pop(x: Float): Float {
        val k = 1.5f
        val y = x - 1f
        return 1f + (k + 1f) * y * y * y + k * y * y
    }

    private fun Canvas.icon(d: android.graphics.drawable.Drawable, cx: Float, cy: Float, s: Float) {
        d.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        d.draw(this)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = if (Theme.motion == 2) END else android.os.SystemClock.uptimeMillis() - start

        // TV body, foot and floor shadow.
        val tw = minOf(w * 0.64f, h * 0.7f * 16f / 9f)
        val th = tw * 9f / 16f
        val tx = (w - tw) / 2; val ty = h * 0.04f
        val floor = ty + th + h * 0.07f
        p.shader = RadialGradient(w / 2, floor, tw * 0.5f, Theme.withAlpha(Color.BLACK, 0.55f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.save(); c.scale(1f, 0.12f, w / 2, floor)
        c.drawCircle(w / 2, floor, tw * 0.5f, p)
        c.restore(); p.shader = null
        p.color = 0xFF0E0B10.toInt()
        c.drawRoundRect(w / 2 - tw * 0.12f, ty + th + h * 0.035f, w / 2 + tw * 0.12f, ty + th + h * 0.055f, h * 0.01f, h * 0.01f, p)
        c.drawRect(w / 2 - tw * 0.02f, ty + th, w / 2 + tw * 0.02f, ty + th + h * 0.04f, p)
        r.set(tx, ty, tx + tw, ty + th)
        c.drawRoundRect(r, tw * 0.025f, tw * 0.025f, p)
        // A thin lit edge along the top of the bezel.
        p.shader = android.graphics.LinearGradient(tx, ty, tx + tw, ty, intArrayOf(Color.TRANSPARENT, Theme.withAlpha(Color.WHITE, 0.22f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(tx + tw * 0.05f, ty, tx + tw * 0.95f, ty + Theme.px(1.5f), p)
        p.shader = null
        // Power light: on from the start.
        val on = ease(((t - 150L) / 300f).coerceIn(0f, 1f))
        p.color = Theme.withAlpha(Theme.accent, 0.3f + 0.7f * on)
        c.drawCircle(tx + tw / 2, ty + th - tw * 0.006f, Theme.px(2f), p)

        // Screen: dark until Home lights it.
        val sx = tx + tw * 0.012f; val sy = ty + tw * 0.012f
        val sw = tw - tw * 0.024f; val sh = th - tw * 0.024f
        val sr = tw * 0.016f
        r.set(sx, sy, sx + sw, sy + sh)
        p.color = 0xFF070508.toInt()
        c.drawRoundRect(r, sr, sr, p)
        val lit = ease(step(t, 1))
        if (lit > 0f) {
            p.shader = android.graphics.LinearGradient(sx, sy, sx + sw, sy + sh, Theme.withAlpha(Theme.surface, lit), Theme.withAlpha(Theme.bg, lit), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, sr, sr, p)
            p.shader = RadialGradient(sx + sw * 0.2f, sy + sh * 0.15f, sw * 0.65f, Theme.withAlpha(Theme.accent, 0.26f * lit), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, sr, sr, p)
            p.shader = null
        }
        c.save(); c.clipRect(r)

        // Profile: the avatar pops in at the top left with a thin ring.
        val pa = step(t, 0)
        if (pa > 0f) {
            val ar = sh * 0.062f * pop(pa)
            val ax = sx + sw * 0.06f; val ay = sy + sh * 0.11f
            p.shader = android.graphics.LinearGradient(ax - ar, ay - ar, ax + ar, ay + ar, Theme.accent, Theme.duo(Theme.accent), Shader.TileMode.CLAMP)
            c.drawCircle(ax, ay, ar, p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = Theme.px(1.5f)
            p.color = Theme.withAlpha(Color.WHITE, 0.35f * pa)
            c.drawCircle(ax, ay, ar + Theme.px(3f), p)
            p.style = Paint.Style.FILL
        }

        // Home: a row of app tiles rises from the bottom, one after another.
        for (k in 0 until 6) {
            val q = ease(step(t, 1, 160L + k * 90L))
            if (q <= 0f) continue
            val gw = sw * 0.12f; val gh = gw * 0.56f
            val gx = sx + sw * 0.06f + k * (gw + sw * 0.03f)
            val gy = sy + sh * 0.74f + sh * 0.08f * (1f - q)
            p.color = Theme.withAlpha(Color.BLACK, 0.3f * q)
            c.drawRoundRect(gx, gy + gh * 0.12f, gx + gw, gy + gh * 1.12f, gh * 0.2f, gh * 0.2f, p)
            if (k == 0) Theme.accentFill(p, gx, gy, gx + gw, gy + gh) else p.color = Theme.elev
            p.alpha = (255 * q).toInt()
            c.drawRoundRect(gx, gy, gx + gw, gy + gh, gh * 0.2f, gh * 0.2f, p)
            p.shader = null; p.alpha = 255
        }

        // Weather: clock and weather slide down at the top right.
        val pw = ease(step(t, 2))
        if (pw > 0f) {
            clock.textSize = sh * 0.075f
            clock.alpha = (255 * pw).toInt()
            val txt = "9:41"
            val cy = sy + sh * 0.11f - sh * 0.05f * (1f - pw)
            val cx = sx + sw * 0.94f - clock.measureText(txt)
            c.drawText(txt, cx, cy + clock.textSize * 0.36f, clock)
            weather.alpha = (255 * pw).toInt()
            c.icon(weather, cx - sh * 0.07f, cy, sh * 0.085f)
        }

        // Voice: the mic pill pops in at the top centre, then pulses once as if listening.
        val pv = step(t, 3)
        if (pv > 0f) {
            val s = pop(pv)
            val mw = sw * 0.14f * s; val mh = sh * 0.09f * s
            val my = sy + sh * 0.11f
            val listen = ((t - LEAD - 3 * STEP - MOVE) / 500f).coerceIn(0f, 1f)
            if (listen in 0.01f..0.99f) {
                p.color = Theme.withAlpha(Theme.accent, 0.35f * (1f - listen))
                val g = mh * 0.6f * listen
                c.drawRoundRect(sx + sw / 2 - mw / 2 - g, my - mh / 2 - g, sx + sw / 2 + mw / 2 + g, my + mh / 2 + g, mh / 2 + g, mh / 2 + g, p)
            }
            p.color = Theme.withAlpha(Theme.TEXT, 0.14f)
            c.drawRoundRect(sx + sw / 2 - mw / 2, my - mh / 2, sx + sw / 2 + mw / 2, my + mh / 2, mh / 2, mh / 2, p)
            c.icon(mic, sx + sw / 2, my, mh * 0.62f)
        }

        // Stremio: the spotlight card slides in from the left and takes focus.
        val ps = ease(step(t, 5))
        if (ps > 0f) {
            val hx = sx + sw * 0.06f - sw * 0.06f * (1f - ps)
            val hy = sy + sh * 0.24f
            val hw = sw * 0.56f; val hh = sh * 0.4f
            p.color = Theme.withAlpha(Color.BLACK, 0.35f * ps)
            c.drawRoundRect(hx, hy + hh * 0.06f, hx + hw, hy + hh * 1.06f, hh * 0.1f, hh * 0.1f, p)
            p.shader = android.graphics.LinearGradient(hx, hy, hx + hw, hy + hh, Theme.withAlpha(Theme.elev, ps), Theme.withAlpha(Theme.mix(Theme.elev, Theme.accent, 0.5f), ps), Shader.TileMode.CLAMP)
            c.drawRoundRect(hx, hy, hx + hw, hy + hh, hh * 0.1f, hh * 0.1f, p)
            p.shader = null
            stremio.alpha = (255 * ps).toInt()
            c.icon(stremio, hx + hh * 0.36f, hy + hh / 2, hh * 0.42f)
            p.color = Theme.withAlpha(Theme.TEXT, 0.8f * ps)
            c.drawRoundRect(hx + hh * 0.7f, hy + hh * 0.36f, hx + hw * 0.82f, hy + hh * 0.46f, hh * 0.05f, hh * 0.05f, p)
            p.color = Theme.withAlpha(Theme.TEXT, 0.4f * ps)
            c.drawRoundRect(hx + hh * 0.7f, hy + hh * 0.56f, hx + hw * 0.62f, hy + hh * 0.64f, hh * 0.04f, hh * 0.04f, p)
            // Focus ring, like Scene's own.
            val ring = ease(((t - LEAD - 5 * STEP - MOVE) / 300f).coerceIn(0f, 1f))
            if (ring > 0f) {
                val g = Theme.px(4f)
                p.style = Paint.Style.STROKE; p.strokeWidth = Theme.px(2.5f)
                p.color = Theme.withAlpha(Theme.TEXT, 0.9f * ring)
                c.drawRoundRect(hx - g, hy - g, hx + hw + g, hy + hh + g, hh * 0.1f + g, hh * 0.1f + g, p)
                p.style = Paint.Style.FILL
            }
        }

        // Screensaver: the screen dims under a moon, then wakes; a small moon stays by the mic.
        val pz = step(t, 4)
        if (pz > 0f) {
            val dim = kotlin.math.sin(pz * Math.PI).toFloat()
            if (pz < 1f && dim > 0.01f) {
                p.color = Theme.withAlpha(Color.BLACK, 0.7f * dim)
                c.drawRect(r, p)
                moon.alpha = (255 * dim).toInt()
                c.icon(moon, sx + sw / 2, sy + sh / 2 - sh * 0.03f * dim, sh * 0.2f)
            }
            val stay = ((pz - 0.6f) / 0.4f).coerceIn(0f, 1f)
            if (stay > 0f) {
                moonSmall.alpha = (255 * stay).toInt()
                c.icon(moonSmall, sx + sw / 2 + sw * 0.11f, sy + sh * 0.11f, sh * 0.06f)
            }
        }

        // A soft sheen crosses the screen once everything is in place.
        val sheen = ((t - DONE) / 900f).coerceIn(0f, 1f)
        if (sheen in 0.001f..0.999f) {
            val x = sx - sw * 0.4f + (sw * 1.8f) * ease(sheen)
            p.shader = android.graphics.LinearGradient(x - sw * 0.15f, sy, x + sw * 0.15f, sy + sh,
                intArrayOf(Color.TRANSPARENT, Theme.withAlpha(Color.WHITE, 0.12f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(r, p)
            p.shader = null
        }
        c.restore()

        // A check lands on the corner, with a ring that spreads and fades.
        val pd = ((t - DONE - 200L) / 420f).coerceIn(0f, 1f)
        if (pd > 0f) {
            val br = tw * 0.04f * pop(pd)
            val wave = ((t - DONE - 200L) / 900f).coerceIn(0f, 1f)
            if (wave < 1f) {
                p.style = Paint.Style.STROKE; p.strokeWidth = Theme.px(3f)
                p.color = Theme.withAlpha(Theme.accent, 0.6f * (1f - wave))
                c.drawCircle(tx + tw, ty, tw * 0.04f * (1f + 1.2f * ease(wave)), p)
                p.style = Paint.Style.FILL
            }
            Theme.accentFill(p, tx + tw - br, ty - br, tx + tw + br, ty + br)
            c.drawCircle(tx + tw, ty, br, p)
            p.shader = null
            c.icon(check, tx + tw, ty, br * 1.2f)
        }

        // Step names joined by a track that fills as each piece arrives.
        val ly = ty + th + h * 0.2f
        val pitch = w / names.size
        val dotY = ly - label.textSize * 1.3f
        p.color = Theme.withAlpha(Theme.TEXT_DIM, 0.22f)
        c.drawRect(pitch * 0.5f, dotY - Theme.px(1f), pitch * (names.size - 0.5f), dotY + Theme.px(1f), p)
        val head = ((t - LEAD).toFloat() / STEP).coerceIn(0f, names.size - 1f)
        if (t > LEAD) {
            Theme.accentFill(p, pitch * 0.5f, 0f, pitch * (names.size - 0.5f), 0f)
            c.drawRect(pitch * 0.5f, dotY - Theme.px(1f), pitch * (0.5f + head), dotY + Theme.px(1f), p)
            p.shader = null
        }
        for ((i, n) in names.withIndex()) {
            val q = step(t, i)
            val now = q > 0f && (i == names.size - 1 || step(t, i + 1) == 0f) && t < DONE + 200L
            label.color = when {
                now -> Theme.accent
                q > 0f -> Theme.mix(Theme.TEXT_DIM, Theme.TEXT, q)
                else -> Theme.TEXT_DIM
            }
            label.alpha = (120 + 135 * q).toInt()
            val x = pitch * (i + 0.5f)
            c.drawText(n, x - label.measureText(n) / 2, ly, label)
            p.color = if (q > 0f) Theme.accent else Theme.mix(Theme.bg, Theme.TEXT_DIM, 0.5f)
            c.drawCircle(x, dotY, Theme.px(4.5f) + Theme.px(2.5f) * (if (now) pop(q) else q), p)
        }

        if (t < END) postInvalidateOnAnimation()
    }

    private companion object {
        const val LEAD = 600L
        const val STEP = 950L
        const val MOVE = 700L
        const val DONE = LEAD + 5 * STEP + MOVE
        const val END = DONE + 1200L
    }
}

/**
 * Hearts in a loose halo around the support QR code. Each one drifts and breathes slowly, out of
 * step with the others. With motion off they stay still.
 */
class HeartHalo(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heart = ctx.icon(R.drawable.ic_favorite, Theme.accent)
    private val heartDuo = ctx.icon(R.drawable.ic_favorite, Theme.duo(Theme.accent))
    private val start = android.os.SystemClock.uptimeMillis()

    /** x and y from the centre and size, as parts of the QR card size; then tilt in degrees. */
    private val spots = arrayOf(
        floatArrayOf(-0.80f, -0.40f, 0.15f, -14f), floatArrayOf(-0.96f, 0.06f, 0.09f, 8f),
        floatArrayOf(-0.76f, 0.42f, 0.18f, -6f), floatArrayOf(-0.58f, 0.66f, 0.08f, 12f),
        floatArrayOf(-0.56f, -0.66f, 0.08f, 10f), floatArrayOf(0.82f, -0.36f, 0.17f, 12f),
        floatArrayOf(0.98f, 0.10f, 0.08f, -10f), floatArrayOf(0.74f, 0.46f, 0.13f, 6f),
        floatArrayOf(0.56f, -0.64f, 0.08f, -8f), floatArrayOf(0.54f, 0.70f, 0.07f, -12f),
    )

    /** Size of the QR card in pixels; set by the pane. */
    var card = 0f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val s = if (card > 0f) card else minOf(width, height) * 0.7f
        val t = if (Theme.motion == 2) 0f else (android.os.SystemClock.uptimeMillis() - start) / 1000f
        // A soft accent glow behind the card.
        p.shader = RadialGradient(cx, cy, s * 0.95f, Theme.withAlpha(Theme.accent, 0.16f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 0.95f, p)
        p.shader = null
        for ((i, h) in spots.withIndex()) {
            val phase = i * 1.7f
            val bob = kotlin.math.sin(t * 0.55f + phase) * s * 0.02f
            val breathe = 0.5f + 0.5f * kotlin.math.sin(t * 0.4f + phase * 1.3f)
            val d = if (i % 3 == 1) heartDuo else heart
            val size = s * h[2]
            val x = cx + h[0] * s; val y = cy + h[1] * s + bob
            d.alpha = (255 * (0.10f + 0.16f * breathe + h[2] * 0.4f)).toInt().coerceAtMost(255)
            c.save()
            c.rotate(h[3] + kotlin.math.sin(t * 0.3f + phase) * 4f, x, y)
            d.setBounds((x - size / 2).toInt(), (y - size / 2).toInt(), (x + size / 2).toInt(), (y + size / 2).toInt())
            d.draw(c)
            c.restore()
        }
        if (Theme.motion != 2) postInvalidateOnAnimation()
    }
}
