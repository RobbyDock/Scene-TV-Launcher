package com.robbydock.scene

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Setup's right-hand picture. It shows what the focused choice does: a TV with Home, a Home
 * button, the weather pill, the mic, a screensaver preview or a profile picture.
 * Laid out on an 800 x 820 design box.
 */
class OnboardArt(ctx: Context) : FrameLayout(ctx) {
    enum class Kind { WELCOME, PROFILE, HOME, WEATHER, VOICE, SCREENSAVER, STREMIO, DONE }

    var kind = Kind.WELCOME
        private set
    /** Index of the focused choice. */
    var choice = 0
        set(v) { if (field != v) { field = v; rebuild() } }

    /** Shows the picture for [k] with choice [i] focused. */
    fun show(k: Kind, i: Int = 0) {
        kind = k
        choice = i
        rebuild()
    }
    /** Emoji or initial shown on the profile picture. */
    var face = ""
    var fahrenheit = false
    var spotlight = true

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val caption: TextView = TextView(ctx).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.px(24f))
        setTextColor(Theme.TEXT_DIM)
        typeface = Theme.MEDIUM
        gravity = Gravity.CENTER_HORIZONTAL
        includeFontPadding = false
    }
    private var mini: MiniHome? = null
    private var shownAt = 0L

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    private fun x(v: Float) = Theme.px(v)

    // Design-space layout: the picture and its caption sit centred as one block.
    private val tv = RectF()
    private val cx = 400f
    private var cy = 410f

    private fun boxHeight() = when (kind) {
        Kind.PROFILE -> 300f
        Kind.HOME, Kind.VOICE -> 420f
        Kind.WEATHER -> 150f
        else -> 428f
    }

    /** Sets the picture box and returns the caption's top. */
    private fun layoutBox(withCaption: Boolean): Float {
        val h = boxHeight()
        val top = (820f - h - if (withCaption) 86f else 0f) / 2
        cy = top + h / 2
        tv.set(20f, top, 780f, top + h)
        return top + h + 56f
    }

    private fun isTv() = kind == Kind.WELCOME || kind == Kind.SCREENSAVER || kind == Kind.STREMIO || kind == Kind.DONE

    private fun rebuild() {
        removeAllViews()
        mini = null
        shownAt = SystemClock.uptimeMillis()
        val capTop = layoutBox(captionText().isNotEmpty())
        val miniOn = kind == Kind.WELCOME || kind == Kind.STREMIO || kind == Kind.DONE
        if (miniOn) {
            val m = MiniHome(context).apply {
                spotlight = when (kind) { Kind.STREMIO -> choice == 0; Kind.DONE -> this@OnboardArt.spotlight; else -> true }
            }
            place(m, tv.left + 12f, tv.top + 12f, tv.width() - 24f, tv.height() - 24f)
            mini = m
        }
        caption.text = captionText()
        if (caption.text.isNotEmpty()) place(caption, 0f, capTop, 800f, 34f)
        if (Theme.MEDIUM_MS > 0) {
            alpha = 0f
            translationY = if (Theme.moves) x(14f) else 0f
            animate().alpha(1f).translationY(0f).setDuration(Theme.MEDIUM_MS + 200).setInterpolator(Theme.EASE).start()
        }
        invalidate()
    }

    private fun captionText(): String = when (kind) {
        Kind.WELCOME, Kind.DONE -> ""
        Kind.PROFILE -> "You first, then anyone else who watches"
        Kind.HOME -> if (choice == 1) "Home keeps opening the TV's own launcher" else "Home on your remote opens Scene"
        Kind.WEATHER -> arrayOf("Uses this TV's approximate location", "Pick a city by name", "Just the clock and date").getOrElse(choice) { "" }
        Kind.VOICE -> if (choice == 1) "You can allow it later in Settings" else "Listens only while the mic button is on"
        Kind.SCREENSAVER -> arrayOf("Starts after a few idle minutes", "Dim enough for a dark room", "Your TV may still use its own").getOrElse(choice) { "" }
        Kind.STREMIO -> if (choice == 0) "Continue watching with one-press resume" else "Just your apps, nothing else"
    }

    /** Seconds since this picture appeared, for loops that run only at full motion. */
    private fun t(): Float = (SystemClock.uptimeMillis() - shownAt) / 1000f

    private fun animating() = Theme.motion == 0 && when (kind) {
        Kind.HOME -> choice != 1
        Kind.VOICE -> choice != 1
        Kind.SCREENSAVER -> choice == 0
        else -> false
    }

    override fun onDraw(c: Canvas) {
        when {
            isTv() -> drawTv(c)
            kind == Kind.PROFILE -> drawProfile(c)
            kind == Kind.HOME -> drawHome(c)
            kind == Kind.WEATHER -> drawWeather(c)
            kind == Kind.VOICE -> drawVoice(c)
        }
        if (animating()) postInvalidateOnAnimation()
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (kind == Kind.DONE) drawCheck(c)
    }

    private fun accentGradient(l: Float, t: Float, rr: Float, b: Float) {
        p.shader = LinearGradient(l, t, rr, b, Theme.accent, Theme.duo(Theme.accent), Shader.TileMode.CLAMP)
    }

    // ---------------------------------------------------------------- TV

    private fun drawTv(c: Canvas) {
        r.set(x(tv.left), x(tv.top), x(tv.right), x(tv.bottom))
        p.color = 0xFF0E0B10.toInt()
        p.setShadowLayer(x(76f), 0f, x(40f), 0x8C000000.toInt())
        c.drawRoundRect(r, x(22f), x(22f), p)
        p.clearShadowLayer()
        // Stand.
        c.drawRect(x(cx - 10f), r.bottom, x(cx + 10f), r.bottom + x(22f), p)
        c.drawRoundRect(x(cx - 80f), r.bottom + x(22f), x(cx + 80f), r.bottom + x(34f), x(6f), x(6f), p)
        // Screen.
        val s = RectF(r.left + x(12f), r.top + x(12f), r.right - x(12f), r.bottom - x(12f))
        if (kind != Kind.SCREENSAVER) return
        c.save()
        val clip = android.graphics.Path().apply { addRoundRect(s, x(12f), x(12f), android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        when (choice) {
            0 -> drawAmbient(c, s)
            1 -> drawClock(c, s)
            else -> {
                c.drawColor(Theme.bg)
                val tp = textPaint(26f, Theme.TEXT_DIM, Theme.MEDIUM)
                val msg = "The screen stays on Home"
                c.drawText(msg, s.centerX() - tp.measureText(msg) / 2, baseline(tp, s.centerY()), tp)
            }
        }
        c.restore()
    }

    private fun drawAmbient(c: Canvas, s: RectF) {
        c.drawColor(0xFF07050A.toInt())
        val time = t()
        val blobs = arrayOf(
            Triple(Theme.accent, 0.2f, 0.25f), Triple(Theme.duo(Theme.accent), 0.85f, 0.55f),
            Triple(Theme.mix(Theme.accent, 0xFF3D8BFF.toInt(), 0.5f), 0.5f, 0.95f),
        )
        for ((i, b) in blobs.withIndex()) {
            val k = (Math.sin(time / (4.5 + i * 1.0)) * 0.5 + 0.5).toFloat()
            val bx = s.left + s.width() * (b.second + 0.12f * k)
            val by = s.top + s.height() * (b.third + 0.08f * k)
            val rad = s.width() * (0.42f + 0.06f * k)
            p.shader = RadialGradient(bx, by, rad, Theme.withAlpha(b.first, 0.75f), b.first and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(bx, by, rad, p)
        }
        p.shader = null
    }

    private fun drawClock(c: Canvas, s: RectF) {
        c.drawColor(Color.BLACK)
        val big = textPaint(120f, Theme.withAlpha(Theme.TEXT, 0.9f), Theme.LIGHT)
        val small = textPaint(24f, Theme.withAlpha(Theme.TEXT_DIM, 0.8f), Theme.MEDIUM)
        val time = timeText()
        val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date())
        c.drawText(time, s.centerX() - big.measureText(time) / 2, s.centerY() + x(20f), big)
        c.drawText(date, s.centerX() - small.measureText(date) / 2, s.centerY() + x(70f), small)
    }

    private fun timeText(): String = android.text.format.DateFormat.getTimeFormat(context).format(Date()).replace(Regex("\\s?[AaPp]\\.?[Mm]\\.?"), "")

    private fun drawCheck(c: Canvas) {
        val px = x(cx)
        val py = x((tv.top + tv.bottom) / 2)
        val rad = x(75f)
        // Glow under a ring of the page colour, then the badge.
        p.color = Theme.bg
        p.setShadowLayer(x(60f), 0f, x(30f), Theme.withAlpha(Theme.accent, 0.5f))
        c.drawCircle(px, py, rad + x(10f), p)
        p.clearShadowLayer()
        accentGradient(px - rad, py - rad, px + rad, py + rad)
        c.drawCircle(px, py, rad, p)
        p.shader = null
        icon(c, R.drawable.ic_check, px, py, 80f, Theme.onAccent)
    }

    private fun icon(c: Canvas, res: Int, px: Float, py: Float, size: Float, color: Int) {
        val d = context.icon(res, color)
        val h = x(size) / 2
        d.setBounds((px - h).toInt(), (py - h).toInt(), (px + h).toInt(), (py + h).toInt())
        d.draw(c)
    }

    // ---------------------------------------------------------------- profile

    private fun drawProfile(c: Canvas) {
        val py = x(cy)
        // Empty places for other people.
        p.style = Paint.Style.STROKE
        p.strokeWidth = x(4f)
        p.color = Theme.withAlpha(Theme.TEXT, 0.22f)
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(x(12f), x(10f)), 0f)
        for (gx in floatArrayOf(185f, 615f)) c.drawCircle(x(gx), py, x(73f), p)
        p.pathEffect = null
        // Accent ring around the picture.
        p.strokeWidth = x(4f)
        p.color = Theme.withAlpha(Theme.accent, 0.5f)
        c.drawCircle(x(cx), py, x(130f), p)
        p.style = Paint.Style.FILL
        val rad = x(120f)
        accentGradient(x(cx) - rad, py - rad, x(cx) + rad, py + rad)
        p.setShadowLayer(x(60f), 0f, x(30f), Theme.withAlpha(Theme.accent, 0.35f))
        c.drawCircle(x(cx), py, rad, p)
        p.clearShadowLayer()
        p.shader = null
        if (face.isEmpty()) icon(c, R.drawable.ic_account_circle, x(cx), py, 130f, Theme.onAccent)
        else {
            val tp = textPaint(130f, Color.WHITE, Theme.BOLD)
            c.drawText(face, x(cx) - tp.measureText(face) / 2, baseline(tp, py), tp)
        }
    }

    // ---------------------------------------------------------------- home button

    private fun drawHome(c: Canvas) {
        val px = x(cx)
        val py = x(cy)
        val off = choice == 1
        val time = if (animating()) t() % 2.6f / 2.6f else 0f
        // Press: a short dip and settle near the end of each loop.
        val press = when {
            time < 0.62f -> 1f
            time < 0.70f -> 1f - 0.07f * ((time - 0.62f) / 0.08f)
            time < 0.80f -> 0.93f + 0.09f * ((time - 0.70f) / 0.10f)
            else -> 1.02f - 0.02f * ((time - 0.80f) / 0.20f)
        }
        // Rings spread out after each press.
        if (!off && time > 0.66f) {
            for (d in floatArrayOf(0f, 0.12f)) {
                val k = ((time - 0.66f - d) / 0.34f).coerceIn(0f, 1f)
                if (k <= 0f) continue
                p.style = Paint.Style.STROKE
                p.strokeWidth = x(3f)
                p.color = Theme.withAlpha(Theme.accent, 0.45f * (1f - k))
                c.drawCircle(px, py, x(124f) * (1f + 0.7f * k), p)
                p.style = Paint.Style.FILL
            }
        }
        // Dark bezel.
        p.color = 0xE60E0B10.toInt()
        p.setShadowLayer(x(68f), 0f, x(36f), if (off) 0x80000000.toInt() else Theme.withAlpha(Theme.accent, 0.45f))
        c.drawCircle(px, py, x(124f), p)
        p.clearShadowLayer()
        val rad = x(110f) * press
        if (off) {
            p.shader = RadialGradient(px - rad * 0.3f, py - rad * 0.4f, rad * 1.6f, 0xFF3A3140.toInt(), 0xFF211A26.toInt(), Shader.TileMode.CLAMP)
        } else {
            val light = Theme.mix(Theme.accent, Color.WHITE, 0.3f)
            p.shader = RadialGradient(px - rad * 0.3f, py - rad * 0.4f, rad * 1.6f,
                intArrayOf(light, Theme.accent, Theme.duo(Theme.accent)), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(px, py, rad, p)
        p.shader = null
        // Light on the top edge.
        p.style = Paint.Style.STROKE
        p.strokeWidth = x(2f)
        p.color = Theme.withAlpha(Color.WHITE, if (off) 0.08f else 0.25f)
        c.drawArc(px - rad + x(1f), py - rad + x(1f), px + rad - x(1f), py + rad - x(1f), 200f, 140f, false, p)
        p.style = Paint.Style.FILL
        icon(c, R.drawable.ic_home, px, py, 104f * press, if (off) Theme.TEXT_DIM else Theme.onAccent)
    }

    // ---------------------------------------------------------------- weather

    private fun drawWeather(c: Canvas) {
        val noWeather = choice == 2
        val temp = textPaint(60f, Theme.TEXT, Theme.MEDIUM)
        val clock = textPaint(78f, Theme.TEXT, Theme.LIGHT).apply { fontFeatureSettings = "tnum" }
        val date = textPaint(22f, Theme.TEXT_DIM, Theme.SEMIBOLD).apply { letterSpacing = 0.14f }
        val tText = if (fahrenheit) "68°" else "20°"
        val time = timeText()
        val day = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
        val clockW = maxOf(clock.measureText(time), date.measureText(day))
        val weatherW = if (noWeather) 0f else x(72f) + x(20f) + temp.measureText(tText) + x(44f) * 2 + x(3f)
        val w = x(64f) * 2 + weatherW + clockW
        val h = x(150f)
        val left = x(cx) - w / 2
        val top = x(cy) - h / 2
        r.set(left, top, left + w, top + h)
        p.color = Theme.withAlpha(Theme.float, 0.6f)
        p.setShadowLayer(x(60f), 0f, x(30f), 0x73000000)
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.clearShadowLayer()
        var at = left + x(64f)
        if (!noWeather) {
            icon(c, R.drawable.ic_partly_cloudy_day, at + x(36f), x(cy), 72f, Theme.STAR)
            at += x(72f) + x(20f)
            c.drawText(tText, at, baseline(temp, x(cy)), temp)
            at += temp.measureText(tText) + x(44f)
            p.color = Theme.withAlpha(Theme.TEXT, 0.16f)
            c.drawRect(at, x(cy) - x(40f), at + x(3f), x(cy) + x(40f), p)
            at += x(3f) + x(44f)
        }
        val right = at + clockW
        c.drawText(time, right - clock.measureText(time), x(cy) + x(14f), clock)
        c.drawText(day, right - date.measureText(day), x(cy) + x(52f), date)
    }

    // ---------------------------------------------------------------- voice

    private fun drawVoice(c: Canvas) {
        val px = x(cx)
        val py = x(cy)
        val off = choice == 1
        val rad = x(90f)
        if (!off && Theme.motion == 0) {
            for (i in 0 until 3) {
                val k = ((t() + i * 0.8f) % 2.4f) / 2.4f
                p.style = Paint.Style.STROKE
                p.strokeWidth = x(3f)
                p.color = Theme.withAlpha(Theme.accent, 0.5f * (1f - k))
                c.drawCircle(px, py, rad * (1f + 1.3f * k), p)
            }
            p.style = Paint.Style.FILL
        }
        if (off) p.color = Theme.elev
        else {
            accentGradient(px - rad, py - rad, px + rad, py + rad)
            p.setShadowLayer(x(60f), 0f, x(30f), Theme.withAlpha(Theme.accent, 0.4f))
        }
        c.drawCircle(px, py, rad, p)
        p.clearShadowLayer()
        p.shader = null
        icon(c, R.drawable.ic_mic, px, py, 84f, if (off) Theme.TEXT_DIM else Theme.onAccent)
        if (off) {
            // Slash across the mic.
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = x(7f)
            p.color = Theme.elev
            c.drawLine(px - x(34f), py - x(34f), px + x(34f), py + x(34f), p)
            p.strokeWidth = x(4f)
            p.color = Theme.TEXT_DIM
            c.drawLine(px - x(34f), py - x(34f), px + x(34f), py + x(34f), p)
            p.style = Paint.Style.FILL
        }
    }
}
