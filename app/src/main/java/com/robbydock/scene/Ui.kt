package com.robbydock.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

// ---------------------------------------------------------------- layout helpers

/** Places [v] at design coordinates. A negative width or height means wrap content. */
fun ViewGroup.place(v: View, x: Float, y: Float, w: Float, h: Float): View {
    val lw = if (w < 0) ViewGroup.LayoutParams.WRAP_CONTENT else Theme.ipx(w)
    val lh = if (h < 0) ViewGroup.LayoutParams.WRAP_CONTENT else Theme.ipx(h)
    addView(v, FrameLayout.LayoutParams(lw, lh).apply {
        leftMargin = Theme.ipx(x)
        topMargin = Theme.ipx(y)
        gravity = Gravity.TOP or Gravity.START
    })
    return v
}

fun Context.label(text: String, size: Float, color: Int, face: android.graphics.Typeface = Theme.REGULAR, lines: Int = 1): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.px(size))
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        if (lines == 1) isSingleLine = true else maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(Theme.px(size * 0.35f), 1f)
    }

/** Raised surface card: soft shadow, a faint lift at the top and a light top edge. */
fun drawCard(c: android.graphics.Canvas, p: android.graphics.Paint, w: Int, h: Int, base: Int) {
    val r = Theme.px(Theme.R_CARD)
    p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Theme.mix(base, android.graphics.Color.WHITE, 0.03f), base, base),
        floatArrayOf(0f, 0.46f, 1f), android.graphics.Shader.TileMode.CLAMP)
    p.setShadowLayer(Theme.px(28f), 0f, Theme.px(12f), 0x38000000)
    c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, p)
    p.clearShadowLayer()
    p.shader = null
    // Faint light along the top edge.
    c.save()
    c.clipRect(0f, 0f, w.toFloat(), r)
    p.style = android.graphics.Paint.Style.STROKE
    p.strokeWidth = Theme.px(1.5f)
    p.color = 0x0DFFFFFF
    c.drawRoundRect(0f, Theme.px(0.75f), w.toFloat(), h.toFloat(), r, r, p)
    p.style = android.graphics.Paint.Style.FILL
    c.restore()
}

fun Context.icon(res: Int, color: Int): Drawable = getDrawable(res)!!.mutate().apply { setTint(color) }

/** Page and dialog badge: an accent glyph in a tonal circle. Pops in when attached. */
class IconBadge(ctx: Context, iconRes: Int) : View(ctx) {
    private val glyph = ctx.icon(iconRes, Theme.accent)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Motion.pop(this) }
    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f
        fill.color = Theme.withAlpha(Theme.accent, 0.16f)
        c.drawCircle(width / 2f, height / 2f, r, fill)
        glyph.setTint(Theme.accent)
        val g = (r * 0.5f).toInt()
        glyph.setBounds(width / 2 - g, height / 2 - g, width / 2 + g, height / 2 + g)
        glyph.draw(c)
    }
}

/**
 * Wraps a scrolling view so it is clipped to its own bounds.
 */
fun Context.clipBox(child: View): FrameLayout = FrameLayout(this).apply {
    clipChildren = true
    addView(child, FrameLayout.LayoutParams(-1, -1))
}

fun View.invalidateAll() {
    invalidate()
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).invalidateAll()
}

fun TextPaint.fit(text: String, width: Float): String =
    TextUtils.ellipsize(text, this, width, TextUtils.TruncateAt.END).toString()

/**
 * One line of canvas text that scrolls when it is too long and [active]: it rests, scrolls to show
 * the rest, then loops. Inactive or short text draws normally, with an ellipsis if needed.
 */
class Marquee(private val speedDp: Float = 64f, private val rest: Long = 1400L, private val gapDp: Float = 72f) {
    private var start = 0L
    private var wasActive = false

    /** Restarts from the resting position. */
    fun restart() { wasActive = false }

    /** How far the text has drifted in the last frame, in pixels. */
    var offset = 0f
        private set

    /** [clipLeft] lets the drifting text run past [x] to the left, into a margin. */
    fun draw(c: Canvas, text: String, p: TextPaint, x: Float, baseline: Float, width: Float, active: Boolean, host: View, clipLeft: Float = x) {
        val tw = p.measureText(text)
        if (active && !wasActive) start = android.os.SystemClock.uptimeMillis()
        wasActive = active
        offset = 0f
        if (tw <= width || !active) {
            c.drawText(p.fit(text, width), x, baseline, p)
            return
        }
        val gap = Theme.px(gapDp)
        val cycle = tw + gap
        val speed = Theme.px(speedDp) / 1000f
        val loop = rest + (cycle / speed).toLong()
        val t = (android.os.SystemClock.uptimeMillis() - start) % loop
        val off = (t - rest).coerceAtLeast(0L) * speed
        offset = off
        c.save()
        c.clipRect(clipLeft, baseline - p.textSize * 1.3f, x + width, baseline + p.textSize * 0.5f)
        c.drawText(text, x - off, baseline, p)
        c.drawText(text, x - off + cycle, baseline, p)
        c.restore()
        host.postInvalidateOnAnimation()
    }
}

/**
 * The spotlight title: one bold line that shrinks a little for long names, then drifts slowly to
 * show the rest, with soft faded edges while it moves.
 */
class ScrollTitle(ctx: Context) : View(ctx) {
    var text: String = ""
        set(v) { if (v != field) { field = v; marquee.restart(); fitSize(); invalidate() } }
    private val tp = textPaint(88f, Theme.TEXT, Theme.BOLD).apply { if (Theme.CINEMA) letterSpacing = -0.01f }
    private val marquee = Marquee(speedDp = 26f, rest = 2600L, gapDp = 120f)
    // Cinema sets a larger title.
    private val maxSize = if (Theme.CINEMA) 104f else 88f
    private val minSize = if (Theme.CINEMA) 76f else 68f

    /** Height of the line the text centres in, in pixels. The rest below leaves room for descenders. Zero uses the full height. */
    var lineBox = 0f
    private val fadeL = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    private val fadeR = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }

    /**
     * Room on the left, in pixels, that the text rests to the right of. While drifting, the text
     * runs into it and fades there, toward the screen edge.
     */
    var bleed = 0f
        set(v) { field = v; requestLayout() }

    private fun fitSize() {
        if (width <= 0) return
        val room = width - bleed
        var size = maxSize
        while (size > minSize) {
            tp.textSize = Theme.px(size)
            if (tp.measureText(text) <= room) break
            size -= 4f
        }
        tp.textSize = Theme.px(size)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        fitSize()
        val e = Theme.px(56f)
        val l = if (bleed > 0f) bleed else e
        fadeL.shader = LinearGradient(0f, 0f, l, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        fadeR.shader = LinearGradient(w - e, 0f, w.toFloat(), 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        tp.color = Theme.TEXT
        val w = width.toFloat()
        val h = height.toFloat()
        val baseline = (if (lineBox > 0f) lineBox else h) / 2f - (tp.descent() + tp.ascent()) / 2f
        val scrolls = tp.measureText(text) > w - bleed
        if (!scrolls) {
            c.drawText(text, bleed, baseline, tp)
            return
        }
        val layer = c.saveLayer(0f, 0f, w, h, null)
        marquee.draw(c, text, tp, bleed, baseline, w - bleed, true, this, clipLeft = 0f)
        // The left fade comes in as the text starts to drift.
        val k = (marquee.offset / Theme.px(40f)).coerceIn(0f, 1f)
        if (k > 0f) {
            fadeL.alpha = (k * 255).toInt()
            c.drawRect(0f, 0f, if (bleed > 0f) bleed else Theme.px(56f), h, fadeL)
        }
        c.drawRect(w - Theme.px(56f), 0f, w, h, fadeR)
        c.restoreToCount(layer)
    }
}

fun textPaint(size: Float, color: Int, face: android.graphics.Typeface = Theme.REGULAR) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = Theme.px(size)
        this.color = color
        typeface = face
    }

/** Baseline that vertically centres a single line of [p] on [cy]. */
fun baseline(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2

// ---------------------------------------------------------------- glass

/**
 * Haze for the top bar. Each backdrop is rendered once into a small, heavily blurred bitmap;
 * glass surfaces draw their slice of it scaled up with bilinear filtering, then a tint.
 */
object Glass {
    private var cur: Bitmap? = null
    private var prev: Bitmap? = null
    private var mix = 1f
    private var offsetY = 0f
    private var layerAlpha = 1f
    private val views = java.util.WeakHashMap<View, Boolean>()
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = Paint()
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val loc = IntArray(2)
    private val path = Path()

    fun register(v: View) { views[v] = true }
    fun unregister(v: View) { views.remove(v) }

    private fun refresh() { for (v in views.keys.toList()) v.invalidate() }

    /** Starts a cross-fade to a new blurred backdrop. */
    fun setBackdrop(b: Bitmap?) {
        if (b === cur) return
        prev = cur
        cur = b
        mix = 0f
        refresh()
    }

    /** Cross-fade progress from the old backdrop to the new one. */
    fun fade(t: Float) {
        mix = t
        if (t >= 1f) prev = null
        refresh()
    }

    /** Follows the backdrop while the page scrolls. */
    fun follow(translationY: Float, alpha: Float) {
        if (offsetY == translationY && layerAlpha == alpha) return
        offsetY = translationY
        layerAlpha = alpha
        refresh()
    }

    private fun slice(c: Canvas, b: Bitmap, rootW: Int, a: Float) {
        if (a <= 0f) return
        val k = rootW.toFloat() / b.width
        matrix.setScale(k, k)
        matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat() + offsetY)
        bmp.alpha = (a * 255).toInt()
        c.drawBitmap(b, matrix, bmp)
    }

    fun draw(c: Canvas, v: View, r: RectF, radius: Float, alpha: Float = Theme.glass) {
        val now = cur
        path.reset()
        path.addRoundRect(r, radius, radius, Path.Direction.CW)
        if (now != null || prev != null) {
            v.getLocationInWindow(loc)
            val rootW = v.rootView.width
            c.save()
            c.clipPath(path)
            base.color = Theme.bg
            c.drawRect(r, base)
            prev?.let { slice(c, it, rootW, layerAlpha * (1f - mix)) }
            now?.let { slice(c, it, rootW, layerAlpha * if (prev != null) mix else 1f) }
            c.restore()
        }
        tint.color = Theme.withAlpha(Theme.float, if (now != null) alpha else maxOf(alpha, 0.88f))
        c.drawRoundRect(r, radius, radius, tint)
    }
}


// ---------------------------------------------------------------- focus base

/**
 * Every focusable surface: a light ring plus a small scale-up on focus. Shadows are real elevation
 * shadows (soft ambient plus a key light), raised on focus.
 */
abstract class FocusView(ctx: Context, focusable: Boolean = true) : View(ctx) {
    private var shown = false
    protected val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val box = RectF()
    open val focusScale = Theme.FOCUS_SCALE
    var focusKey: String? = null
    private var focusAnim: android.animation.Animator? = null

    /** Resting and focused elevation in design units. Zero means no shadow. */
    open val restZ = 0f
    open val hotZ = 0f
    /** Coloured key shadow. Zero keeps the default black. */
    open fun shadowTint(): Int = 0

    /** Shape that casts the shadow. Defaults to the whole view with a card radius. */
    protected open fun shadowShape(o: android.graphics.Outline) {
        o.setRoundRect(0, 0, width, height, Theme.px(Theme.R_CARD))
    }

    init {
        isFocusable = focusable
        isClickable = focusable
        // Lets taps and code move focus while a mouse or touch screen is in use.
        isFocusableInTouchMode = focusable
        // Turns off the system focus highlight.
        defaultFocusHighlightEnabled = false
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (restZ == 0f && hotZ == 0f) outline.setEmpty() else shadowShape(outline)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        elevation = Theme.px(restZ)
        applyTint()
    }

    /** Keeps the tinted shadow on while resting, not only when focused. */
    open val glowAtRest = false

    protected fun applyTint() {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        // A tinted spot shadow lands below the view.
        // The glow uses only the ambient shadow, which spreads on all sides.
        val t = shadowTint()
        val lit = t != 0 && (hot() || glowAtRest)
        // A resting glow also tints the spot shadow, which is much stronger than the ambient one.
        outlineSpotShadowColor = if (lit) (if (glowAtRest) t else Color.TRANSPARENT) else Color.BLACK
        outlineAmbientShadowColor = if (lit) t else Color.BLACK
    }

    protected fun hot() = isFocused || isSelected

    private fun sync() {
        val h = hot()
        if (h == shown) return
        shown = h
        // Uses its own animator.
        focusAnim?.cancel()
        val s = if (h) focusScale else 1f
        val z = if (h) Theme.px(hotZ - restZ).coerceAtLeast(0f) else 0f
        if (Theme.FOCUS_MS == 0L || quiet) {
            focusS = s; applyScale(); translationZ = z
        } else {
            focusAnim = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                this,
                android.animation.PropertyValuesHolder.ofFloat(FOCUS_S, s),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Z, z),
            ).apply {
                // Focus lands with a light spring at full motion; leaving is a plain ease.
                if (h && Theme.moves) { duration = Theme.FOCUS_MS * 3; interpolator = Motion.SPRING }
                else { duration = Theme.FOCUS_MS; interpolator = Theme.EASE }
                start()
            }
        }
        applyTint()
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) press(false)
        sync()
    }

    // Scale is the focus scale times the press scale, so a press works on a focused view.
    private var focusS = 1f
    private var pressS = 1f
    private var pressAnim: android.animation.Animator? = null
    private fun applyScale() { scaleX = focusS * pressS; scaleY = focusS * pressS }

    /**
     * Press feedback: the view sinks when OK goes down, sinks further while held, and springs
     * back on release or when the long press fires.
     */
    fun press(down: Boolean) {
        if (Theme.motion == 2 || (down && !isClickable)) return
        if (!down && pressAnim == null && pressS == 1f) return
        pressAnim?.cancel()
        val a = if (down) {
            val k = if (Theme.moves) 0.96f else 0.985f
            android.animation.ValueAnimator.ofPropertyValuesHolder(android.animation.PropertyValuesHolder.ofKeyframe("p",
                android.animation.Keyframe.ofFloat(0f, 1f), android.animation.Keyframe.ofFloat(0.2f, k), android.animation.Keyframe.ofFloat(1f, k - 0.05f),
            )).apply { duration = 500; interpolator = Theme.EASE }
        } else {
            android.animation.ValueAnimator.ofFloat(pressS, 1f).apply {
                if (Theme.moves) { duration = 460; interpolator = Motion.SPRING } else { duration = 140; interpolator = Theme.EASE }
            }
        }
        a.addUpdateListener { pressS = (it.getAnimatedValue("p") ?: it.animatedValue) as Float; applyScale() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { if (pressAnim === animation) pressAnim = null }
        })
        pressAnim = a; a.start()
    }

    override fun performLongClick(): Boolean { press(false); return super.performLongClick() }

    private var heldByTouch = false
    private val touchHold = Runnable { heldByTouch = true; performLongClick() }

    /** A tap or mouse click focuses the view, then acts as OK. Holding acts as a long press. */
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!isFocusable || !isEnabled) return super.onTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                heldByTouch = false
                press(true)
                postDelayed(touchHold, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            android.view.MotionEvent.ACTION_UP -> {
                removeCallbacks(touchHold)
                press(false)
                // The first tap focuses, a tap on the focused view acts as OK.
                if (!heldByTouch) {
                    if (!isFocused) requestFocus() else performClick()
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> { removeCallbacks(touchHold); press(false) }
        }
        return true
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        sync()
    }

    companion object {
        /** Animates the focus part of the scale. */
        private val FOCUS_S = object : android.util.Property<FocusView, Float>(Float::class.java, "focusS") {
            override fun get(v: FocusView) = v.focusS
            override fun set(v: FocusView, value: Float) { v.focusS = value; v.applyScale() }
        }

        /** True while focus is restored by code, so it lands without the focus animation. */
        var quiet = false

        /** Runs [block] with focus changes applied instantly. Nests safely. */
        inline fun <T> quietly(block: () -> T): T {
            val was = quiet
            quiet = true
            try { return block() } finally { quiet = was }
        }
    }

    /** Shows focus without the ring, as on the Cinema Home. */
    var ringless = false

    protected fun drawRing(c: Canvas, radius: Float, rect: RectF? = null) {
        if (!hot() || ringless) return
        val sw = Theme.px(6f)
        ring.color = Theme.TEXT
        ring.strokeWidth = sw
        val r = rect ?: box.apply { set(0f, 0f, width.toFloat(), height.toFloat()) }
        // Drawn inside the bounds.
        val i = sw / 2
        c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, maxOf(0f, radius - i), maxOf(0f, radius - i), ring)
    }
}

/** Small rounded "IMDb" tag set inline before a rating, in the dim text colour. */
class ImdbSpan : android.text.style.ReplacementSpan() {
    private val tp = textPaint(19f, Theme.BRAND_BG, Theme.BOLD)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT_DIM }
    private val padX = Theme.px(7f)
    private val gap = Theme.px(10f)

    override fun getSize(p: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (tp.measureText("IMDb") + padX * 2 + gap).toInt()

    override fun draw(c: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
        val w = tp.measureText("IMDb") + padX * 2
        val h = Theme.px(26f)
        // Centred on the line's x-height, like the dots between parts.
        val cy = y + (p.ascent() + p.descent()) / 2
        c.drawRoundRect(x, cy - h / 2, x + w, cy + h / 2, Theme.px(5f), Theme.px(5f), fill)
        c.drawText("IMDb", x + padX, baseline(tp, cy), tp)
    }
}

// ---------------------------------------------------------------- top bar

class SearchPill(ctx: Context) : FocusView(ctx) {
    private val ic = ctx.icon(R.drawable.ic_search, Theme.TEXT_DIM)
    private val tp = textPaint(if (Theme.CINEMA) 28f else 30f, Theme.TEXT_DIM)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    override val focusScale = 1.03f
    override val hotZ get() = if (Theme.CINEMA) 16f else 0f
    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, height, height / 2f)

    /** Width that fits the icon and text, in design units. Cinema only. */
    fun fitWidth() = 24f + 40f + 16f + 36f + tp.measureText(TEXT) / Theme.u

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!Theme.CINEMA) Glass.register(this) }
    override fun onDetachedFromWindow() { Glass.unregister(this); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        box.set(0f, 0f, width.toFloat(), h)
        val s = Theme.ipx(40f)
        val x: Int
        if (Theme.CINEMA) {
            // A light veil at rest that lifts to a solid pill on focus.
            fill.color = if (hot()) Theme.float else Theme.withAlpha(Theme.TEXT, 0.08f)
            c.drawRoundRect(box, h / 2, h / 2, fill)
            x = Theme.ipx(24f)
            val fg = if (hot()) Theme.TEXT else Theme.TEXT_DIM
            ic.setTint(fg); tp.color = fg
        } else {
            Glass.draw(c, this, box, h / 2)
            x = Theme.ipx(32f)
        }
        ic.setBounds(x, ((h - s) / 2).toInt(), x + s, ((h + s) / 2).toInt())
        ic.draw(c)
        c.drawText(TEXT, x + s + Theme.px(if (Theme.CINEMA) 16f else 20f), baseline(tp, h / 2), tp)
        drawRing(c, h / 2)
    }

    private companion object { const val TEXT = "Search shows, films, apps and more" }
}

/** Stacked usage bar with a legend underneath, for storage and memory. */
class UsageBar(ctx: Context) : View(ctx) {
    class Part(val label: String, val value: Long, val color: Int)
    var parts: List<Part> = emptyList()
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tl = textPaint(21f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val clip = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val bh = Theme.px(28f)
        val total = parts.sumOf { it.value }.coerceAtLeast(1L).toFloat()
        clip.reset()
        clip.addRoundRect(0f, 0f, w, bh, bh / 2, bh / 2, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        fill.color = Theme.elev
        c.drawRect(0f, 0f, w, bh, fill)
        var x = 0f
        val gap = Theme.px(3f)
        for (p in parts) {
            if (p.value <= 0L) continue
            // Small parts keep a minimum width.
            val pw = maxOf(w * p.value / total, Theme.px(10f))
            fill.color = p.color
            c.drawRect(x, 0f, x + pw - gap, bh, fill)
            x += pw
        }
        c.restore()
        // Legend: dot, label and size, flowing left to right.
        var lx = 0f
        val ly = bh + Theme.px(42f)
        val d = Theme.px(14f)
        for (p in parts) {
            fill.color = p.color
            c.drawCircle(lx + d / 2, ly - d / 2 + Theme.px(1f), d / 2, fill)
            val t = p.label
            c.drawText(t, lx + d + Theme.px(10f), ly, tl)
            lx += d + Theme.px(10f) + tl.measureText(t) + Theme.px(28f)
        }
    }
}

/** Profile avatar: a coloured circle with the first letter. [addMode] draws a dashed "add" circle. */
class AvatarView(ctx: Context, private val addMode: Boolean = false) : FocusView(ctx) {
    var letter = ""
        set(v) { field = v; invalidate() }
    var colorIndex = 0
        set(v) { field = v; applyTint(); invalidate() }
    /** Large views: a glow in the second colour around the picture. */
    var glow = false
        set(v) { field = v; applyTint() }
    /** Emoji picture; replaces the letter when set. */
    var emoji = ""
        set(v) { field = v; invalidate() }
    /** Small lock badge for profiles with a passcode. */
    var locked = false
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = textPaint(40f, Color.WHITE, Theme.BOLD)
    private val lock = ctx.icon(R.drawable.ic_lock, Theme.TEXT)
    private val plus = ctx.icon(R.drawable.ic_add, Theme.TEXT_DIM)
    override val focusScale = 1.08f
    override val restZ get() = if (addMode) 0f else if (glow) 14f else 6f
    override val hotZ get() = if (addMode) 0f else if (glow) 30f else 24f
    override val glowAtRest get() = glow
    override fun shadowTint() = if (glow && !addMode) Theme.duo(base()) else 0
    override fun shadowShape(o: android.graphics.Outline) = o.setOval(0, 0, width, height)

    private fun base() = Theme.ACCENTS[colorIndex.coerceIn(0, Theme.ACCENTS.size - 1)].color

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        box.set(0f, 0f, w, w)
        if (addMode) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = Theme.px(3f)
            fill.color = if (hot()) Theme.TEXT_DIM else Theme.outline
            fill.pathEffect = android.graphics.DashPathEffect(floatArrayOf(Theme.px(10f), Theme.px(8f)), 0f)
            val i = Theme.px(1.5f)
            c.drawOval(i, i, w - i, w - i, fill)
            fill.pathEffect = null
            fill.style = Paint.Style.FILL
            val s = (w * 0.36f).toInt()
            plus.setBounds(((w - s) / 2).toInt(), ((w - s) / 2).toInt(), ((w + s) / 2).toInt(), ((w + s) / 2).toInt())
            plus.draw(c)
        } else {
            // Duo tone: the accent blends diagonally into a nearby hue.
            val base = base()
            fill.shader = LinearGradient(0f, 0f, w, w, base, Theme.duo(base), Shader.TileMode.CLAMP)
            c.drawOval(box, fill)
            fill.shader = null
            val t = emoji.ifEmpty { letter }
            tp.textSize = w * if (emoji.isEmpty()) 0.46f else 0.52f
            tp.color = Color.WHITE
            tp.setShadowLayer(w * 0.04f, 0f, w * 0.02f, Theme.withAlpha(Color.BLACK, 0.35f))
            c.drawText(t, (w - tp.measureText(t)) / 2, baseline(tp, w / 2), tp)
            tp.clearShadowLayer()
        }
        drawRing(c, w / 2, box)
        // The lock sits above the focus ring, cut out from it by a thin gap.
        if (locked && !addMode) {
            val r = w * 0.17f
            val cx = w - r - Theme.px(4f)
            val cy = cx
            fill.color = Theme.bg
            c.drawCircle(cx, cy, r + Theme.px(4f), fill)
            fill.color = Theme.surface
            c.drawCircle(cx, cy, r, fill)
            val s = (r * 1.2f).toInt()
            lock.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            lock.draw(c)
        }
    }
}

class RoundButton(ctx: Context, iconRes: Int, private val filled: Boolean) : FocusView(ctx) {
    private val ic = ctx.icon(iconRes, Theme.TEXT)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    var active = false
        set(v) { field = v; invalidate() }
    /** No glass at rest; a solid circle only while focused. Set before the view is attached. */
    var bare = false
    override val restZ get() = if (filled) 6f else 0f
    override val hotZ get() = if (filled) 24f else if (bare) 16f else 0f
    override fun shadowTint() = if (filled) Theme.accent else 0
    override fun shadowShape(o: android.graphics.Outline) = o.setOval(0, 0, width, height)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!filled && !bare) Glass.register(this) }
    override fun onDetachedFromWindow() { Glass.unregister(this); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        box.set(0f, 0f, w, height.toFloat())
        if (filled) {
            Theme.accentFill(fill, 0f, 0f, w, w)
            c.drawOval(box, fill)
            fill.shader = null
            ic.setTint(Theme.onAccent)
        } else if (bare) {
            if (hot()) { fill.color = Theme.float; c.drawOval(box, fill) }
            ic.setTint(Theme.TEXT)
        } else {
            Glass.draw(c, this, box, w / 2)
            ic.setTint(Theme.TEXT)
        }
        val s = (w * 0.5f).toInt()
        ic.setBounds(((w - s) / 2).toInt(), ((height - s) / 2f).toInt(), ((w + s) / 2).toInt(), ((height + s) / 2f).toInt())
        ic.draw(c)
        drawRing(c, w / 2)
    }
}

/** Weather and clock merged into one glass pill. Ticks once a minute while visible. */
class InfoPill(ctx: Context) : View(ctx) {
    var weather: WeatherNow? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var fahrenheit = false
    var clockMode = 0
    var showClock = true
    var showDate = true
    var showWeather = true
    /** 0 condition and high/low, 1 high/low only, 2 temperature only. */
    var detail = 0
    var placeholder = "Set location in Settings"

    /** Copies the clock and weather options from [cfg]. */
    fun apply(cfg: Config, w: WeatherNow?) {
        fahrenheit = cfg.fahrenheit
        clockMode = cfg.clock
        showClock = cfg.clockOn
        showDate = cfg.showDate
        showWeather = cfg.weatherOn
        detail = cfg.weatherDetail
        visibility = if (showClock || showWeather) VISIBLE else GONE
        weather = w
    }

    private fun detailLine(w: WeatherNow): String {
        val (cond, _) = Weather.describe(w.code, w.day)
        val hl = "${Weather.format(w.high, fahrenheit)} / ${Weather.format(w.low, fahrenheit)}"
        return when (detail) {
            1 -> hl
            2 -> ""
            else -> "$cond · $hl"
        }
    }

    private val cinema = Theme.CINEMA
    private val big = if (cinema) textPaint(28f, Theme.TEXT_DIM, Theme.MEDIUM) else textPaint(34f, Theme.TEXT, Theme.SEMIBOLD)
    private val small = textPaint(24f, Theme.TEXT_DIM)
    private val time = (if (cinema) textPaint(44f, Theme.TEXT, Theme.LIGHT).apply { letterSpacing = 0.01f } else textPaint(38f, Theme.TEXT, Theme.BOLD)).apply { fontFeatureSettings = "tnum" }
    private val date = if (cinema) textPaint(16f, Theme.TEXT_DIM, Theme.SEMIBOLD).apply { letterSpacing = 0.14f } else textPaint(21f, Theme.TEXT_DIM).apply { letterSpacing = 0.02f }
    private val div = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val tick = Runnable { invalidate(); schedule() }
    /** Soft dark halo behind the bare Cinema text, so it reads on bright art. */
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        if (cinema) for (p in listOf(big, small, time, date)) p.setShadowLayer(Theme.px(6f), 0f, Theme.px(1f), Theme.withAlpha(Color.BLACK, 0.4f))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!cinema) Glass.register(this) }
    override fun onDetachedFromWindow() { Glass.unregister(this); super.onDetachedFromWindow() }

    private fun schedule() {
        removeCallbacks(tick)
        val now = System.currentTimeMillis()
        postDelayed(tick, 60_000 - now % 60_000 + 50)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { invalidate(); schedule() } else removeCallbacks(tick)
    }

    private fun timeText(): Pair<String, String> {
        val is24 = when (clockMode) {
            1 -> false
            2 -> true
            else -> android.text.format.DateFormat.is24HourFormat(context)
        }
        val now = java.util.Date()
        val t = java.text.SimpleDateFormat(if (is24) "HH:mm" else "h:mm", java.util.Locale.getDefault()).format(now)
        val d = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(now)
        return t to d
    }

    private fun dateText(d: String) = if (cinema) d.uppercase(java.util.Locale.getDefault()) else d

    // Cinema shows the temperature without the condition line.
    private fun lineFor(w: WeatherNow) = if (cinema) "" else detailLine(w)
    private val iconSize get() = if (cinema) 34f else 40f
    private val iconGap get() = if (cinema) 10f else 14f
    private val divGap get() = if (cinema) 26f else 28f

    private fun weatherWidth(): Float {
        val w = weather ?: return small.measureText(placeholder)
        val line = lineFor(w)
        return Theme.px(iconSize + iconGap) + big.measureText(Weather.format(w.temp, fahrenheit)) +
            (if (line.isEmpty()) 0f else Theme.px(14f) + small.measureText(line))
    }

    private fun clockWidth(): Float {
        val (t, d) = timeText()
        val tw = time.measureText(if (t.length < 5) "00:00" else t)
        return if (showDate) maxOf(tw, date.measureText(dateText(d))) else tw
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Cinema has no pill, so the text runs to the edges.
        val padL = Theme.px(if (cinema) 0f else 32f)
        val padR = Theme.px(if (cinema) 8f else 32f)
        var total = padL + padR + if (showClock && !cinema) Theme.px(8f) else 0f
        if (showWeather) total += weatherWidth()
        if (showWeather && showClock) total += Theme.px(divGap * 2)
        if (showClock) total += clockWidth()
        setMeasuredDimension(total.toInt(), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        box.set(0f, 0f, width.toFloat(), h)
        if (cinema) {
            // Drawn past the view's edges; the parent does not clip.
            val cx = width / 2f; val rx = width / 2f + Theme.px(80f); val ry = h / 2 + Theme.px(52f)
            halo.shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x4D0A060C, 0x240A060C, 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.save(); c.translate(cx, h / 2); c.scale(rx, ry); c.drawCircle(0f, 0f, 1f, halo); c.restore()
        } else Glass.draw(c, this, box, h / 2)
        var x = Theme.px(if (cinema) 0f else 32f)
        val w = weather
        if (showWeather) {
            if (w != null) {
                val (_, res) = Weather.describe(w.code, w.day)
                val s = Theme.ipx(iconSize)
                val ic = context.icon(res, Theme.STAR)
                ic.setBounds(x.toInt(), ((h - s) / 2).toInt(), x.toInt() + s, ((h + s) / 2).toInt())
                ic.draw(c)
                x += s + Theme.px(iconGap)
                val temp = Weather.format(w.temp, fahrenheit)
                c.drawText(temp, x, baseline(big, h / 2), big)
                x += big.measureText(temp)
                val line = lineFor(w)
                if (line.isNotEmpty()) {
                    x += Theme.px(14f)
                    c.drawText(line, x, baseline(small, h / 2), small)
                    x += small.measureText(line)
                }
            } else {
                c.drawText(placeholder, x, baseline(small, h / 2), small)
                x += small.measureText(placeholder)
            }
        }
        if (showWeather && showClock) {
            x += Theme.px(divGap)
            div.color = Theme.withAlpha(Theme.TEXT, if (cinema) 0.14f else 0.16f)
            if (cinema) c.drawRect(x, h / 2 - Theme.px(20f), x + Theme.px(1.5f), h / 2 + Theme.px(20f), div)
            else c.drawRect(x, h * 0.25f, x + Theme.px(2f), h * 0.75f, div)
            x += Theme.px(divGap)
        }
        if (showClock && cinema) {
            // Time and date share a right edge.
            val (t, d) = timeText()
            val right = width - Theme.px(8f)
            if (showDate) {
                val du = dateText(d)
                c.drawText(t, right - time.measureText(t), h / 2 + Theme.px(4f), time)
                c.drawText(du, right - date.measureText(du), h / 2 + Theme.px(28f), date)
            } else c.drawText(t, right - time.measureText(t), baseline(time, h / 2), time)
        } else if (showClock) {
            val (t, d) = timeText()
            if (showDate) {
                c.drawText(t, x, h / 2 + Theme.px(1f), time)
                c.drawText(d, x, h / 2 + Theme.px(30f), date)
            } else {
                c.drawText(t, x, baseline(time, h / 2), time)
            }
        }
    }
}

// ---------------------------------------------------------------- buttons and chips

class PillButton(ctx: Context, var text: String, iconRes: Int, private val primary: Boolean) : FocusView(ctx) {
    private val ic = if (iconRes != 0) ctx.icon(iconRes, Theme.TEXT) else null
    private val tp = textPaint(30f, Theme.TEXT, Theme.SEMIBOLD)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    override val focusScale = 1.04f
    /** Secondary buttons show no pill until focused. Set before the view is attached. */
    var bare = false
    override val restZ get() = if (primary) 6f else if (bare) 0f else 0.1f
    override val hotZ = 22f
    override fun shadowTint() = if (primary) Theme.accent else 0
    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, height, height / 2f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (text.isEmpty()) {
            // Icon only: a circle.
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(h, h)
            return
        }
        val iconW = if (ic != null) Theme.px(40f + 14f) else 0f
        val w = Theme.px(44f) * 2 + iconW + tp.measureText(text)
        setMeasuredDimension(w.toInt(), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        box.set(0f, 0f, width.toFloat(), h)
        fill.color = Theme.float
        if (primary) Theme.accentFill(fill, 0f, 0f, box.right, h)
        if (primary || !bare || hot()) c.drawRoundRect(box, h / 2, h / 2, fill)
        fill.shader = null
        val fg = if (primary) Theme.onAccent else Theme.TEXT
        var x = if (text.isEmpty()) (width - Theme.px(40f)) / 2 else Theme.px(44f)
        if (ic != null) {
            val s = Theme.ipx(40f)
            ic.setTint(fg)
            ic.setBounds(x.toInt(), ((h - s) / 2).toInt(), x.toInt() + s, ((h + s) / 2).toInt())
            ic.draw(c)
            x += s + Theme.px(14f)
        }
        tp.color = fg
        c.drawText(text, x, baseline(tp, h / 2), tp)
        drawRing(c, h / 2)
    }
}

/** Small header toggle such as "Show all 14". */
class SmallPill(ctx: Context, var text: String, var iconRes: Int) : FocusView(ctx) {
    private val tp = textPaint(24f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    override val focusScale = 1.05f
    /** No fill until focused. */
    var quiet = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = Theme.px(22f + 30f + 8f + 22f) + tp.measureText(text)
        setMeasuredDimension(w.toInt(), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        box.set(0f, 0f, width.toFloat(), h)
        fill.color = if (hot()) Theme.float else Theme.withAlpha(Theme.float, 0.6f)
        if (hot() || !quiet) c.drawRoundRect(box, h / 2, h / 2, fill)
        val s = Theme.ipx(30f)
        val x = Theme.ipx(22f)
        val ic = context.icon(iconRes, if (hot()) Theme.TEXT else Theme.TEXT_DIM)
        ic.setBounds(x, ((h - s) / 2).toInt(), x + s, ((h + s) / 2).toInt())
        ic.draw(c)
        tp.color = if (hot()) Theme.TEXT else Theme.TEXT_DIM
        c.drawText(text, x + s + Theme.px(8f), baseline(tp, h / 2), tp)
        drawRing(c, h / 2)
    }
}

class Chip(ctx: Context, private val text: String, private val tinted: Boolean = false) : View(ctx) {
    private val tp = if (tinted) textPaint(22f, Theme.accent, Theme.SEMIBOLD) else textPaint(22f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((tp.measureText(text) + Theme.px(40f)).toInt(), Theme.ipx(44f))
    }

    override fun onDraw(c: Canvas) {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.color = if (tinted) Theme.withAlpha(Theme.accent, 0.14f) else Theme.withAlpha(Theme.float, 0.75f)
        c.drawRoundRect(box, Theme.px(Theme.R_CHIP), Theme.px(Theme.R_CHIP), fill)
        c.drawText(text, Theme.px(20f), baseline(tp, height / 2f), tp)
    }
}

// ---------------------------------------------------------------- loading shimmer

/**
 * Soft light band that sweeps across a placeholder while its image loads. Every shimmer shares
 * one clock. The caller keeps redrawing while [active].
 */
object Shimmer {
    private const val PERIOD = 1400L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var shader: LinearGradient? = null
    private var builtFor = 0

    fun draw(c: Canvas, r: RectF, radius: Float) {
        val base = Theme.elev
        if (shader == null || builtFor != base) {
            val hi = Theme.mix(base, Color.WHITE, 0.07f)
            shader = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(base, hi, base), floatArrayOf(0.35f, 0.5f, 0.65f), Shader.TileMode.CLAMP)
            builtFor = base
        }
        val t = (android.os.SystemClock.uptimeMillis() % PERIOD) / PERIOD.toFloat()
        val span = r.width() * 2.2f
        m.setScale(span, 1f)
        m.postTranslate(r.left - span * 0.6f + t * (r.width() + span * 0.2f) , 0f)
        shader!!.setLocalMatrix(m)
        paint.shader = shader
        c.drawRoundRect(r, radius, radius, paint)
    }
}

/** Placeholder text lines that shimmer while title details load. */
class ShimmerLines(ctx: Context) : View(ctx) {
    /** Width of each line as a fraction of the view, top to bottom. */
    var lines = floatArrayOf(0.42f, 0.30f, 0.92f, 0.78f)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val gap = Theme.px(22f)
        val lh = Theme.px(26f)
        var y = 0f
        for (f in lines) {
            r.set(0f, y, width * f, y + lh)
            Shimmer.draw(c, r, lh / 2)
            y += lh + gap
        }
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}

// ---------------------------------------------------------------- art cards

/** Continue watching card: 16:9 art, scrim, title, subtitle and a flush progress bar. */
class ArtCard(ctx: Context) : FocusView(ctx) {
    var art: Bitmap? = null
        set(v) { field = v; invalidate() }
    var title = ""
    var subtitle = ""
    var progress = -1f
    /** Small accent label in the top corner, such as NEW. */
    var badge = ""
    /** True while the art is on its way; the card shimmers instead of showing an icon. */
    var loading = false
        set(v) { field = v; invalidate() }
    var iconRes = 0

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tTitle = textPaint(26f, Color.WHITE, Theme.SEMIBOLD)
    private val tSub = textPaint(21f, Theme.withAlpha(Color.WHITE, 0.78f))
    private val tBadge = textPaint(20f, Color.WHITE, Theme.BOLD).apply { letterSpacing = 0.08f }
    private val clip = Path()
    private val src = Rect()
    override val restZ = 10f
    override val hotZ = 34f
    private val radius get() = Theme.px(if (Theme.CINEMA) 22f else Theme.R_CARD)
    // Cinema: focus lifts the card and draws a bar along its foot. Other cards dim and hide their labels.
    override val focusScale get() = if (cinema) 1.04f else Theme.FOCUS_SCALE
    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, height, radius)

    /** Home's Cinema focus: lift and a foot bar instead of a ring. Other screens keep the ring. */
    var cinema = false
        set(v) { field = v; ringless = v; alpha = if (v && !isFocused) DIM else 1f; invalidate() }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!cinema) return
        val a = if (gainFocus) 1f else DIM
        val y = if (gainFocus && Theme.moves) -Theme.px(8f) else 0f
        if (Theme.MEDIUM_MS == 0L || FocusView.quiet) { alpha = a; translationY = y }
        else animate().alpha(a).translationY(y).setDuration(if (gainFocus) Theme.FOCUS_MS * 3 else Theme.FOCUS_MS)
            .setInterpolator(if (gainFocus && Theme.moves) Motion.SPRING else Theme.EASE).start()
    }

    private companion object { const val DIM = 0.65f }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scrim.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Theme.withAlpha(Color.BLACK, 0.10f), Theme.withAlpha(Color.BLACK, 0.25f), Theme.withAlpha(Color.BLACK, 0.88f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        invalidateOutline()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = radius
        box.set(0f, 0f, w, h)
        fill.color = Theme.elev
        c.drawRoundRect(box, r, r, fill)
        c.save()
        c.clipPath(clip)
        val a = art
        if (a != null) {
            // Centre crop to the card's aspect ratio.
            val target = w / h
            val srcRatio = a.width.toFloat() / a.height
            if (srcRatio > target) {
                val sw = (a.height * target).toInt()
                src.set((a.width - sw) / 2, 0, (a.width + sw) / 2, a.height)
            } else {
                val sh = (a.width / target).toInt()
                src.set(0, (a.height - sh) / 4, a.width, (a.height - sh) / 4 + sh)
            }
            c.drawBitmap(a, src, box, bmp)
        } else if (loading) {
            Shimmer.draw(c, box, 0f)
            postInvalidateOnAnimation()
        } else if (iconRes != 0) {
            fill.color = Theme.float
            c.drawRect(box, fill)
            val s = Theme.ipx(64f)
            val ic = context.icon(iconRes, Theme.accent)
            ic.setBounds(((w - s) / 2).toInt(), (h * 0.32f - s / 2).toInt(), ((w + s) / 2).toInt(), (h * 0.32f + s / 2).toInt())
            ic.draw(c)
        }
        c.drawRect(box, scrim)
        val pad = Theme.px(24f)
        // Cinema names only the focused card, or a card with no art.
        if (!cinema || hot() || a == null) {
            c.drawText(tTitle.fit(title, w - pad * 2), pad, h - Theme.px(if (subtitle.isEmpty()) 30f else 58f), tTitle)
            if (subtitle.isNotEmpty()) c.drawText(tSub.fit(subtitle, w - pad * 2), pad, h - Theme.px(28f), tSub)
        }
        if (badge.isNotEmpty()) {
            val tb = tBadge
            val bw = tb.measureText(badge) + Theme.px(28f)
            val bh = Theme.px(40f)
            val bx = Theme.px(18f)
            Theme.accentFill(fill, bx, bx, bx + bw, bx + bh)
            c.drawRoundRect(bx, bx, bx + bw, bx + bh, bh / 2, bh / 2, fill)
            fill.shader = null
            tb.color = Theme.onAccent
            c.drawText(badge, bx + Theme.px(14f), baseline(tb, bx + bh / 2), tb)
        }
        if (progress > 0f) {
            val bh = Theme.px(6f)
            fill.color = Theme.withAlpha(Color.WHITE, 0.18f)
            c.drawRect(0f, h - bh, w, h, fill)
            Theme.accentFill(fill, 0f, h - bh, w * progress, h)
            c.drawRect(0f, h - bh, w * progress, h, fill)
            fill.shader = null
        }
        if (cinema && hot()) { fill.color = Theme.TEXT; c.drawRect(0f, h - Theme.px(6f), w, h, fill) }
        c.restore()
        drawRing(c, r)
    }
}

/** Search result poster, 2:3. */
class PosterCard(ctx: Context) : FocusView(ctx) {
    var art: Bitmap? = null
        set(v) { field = v; invalidate() }
    var title = ""
    var year = ""
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()
    private val tp = textPaint(22f, Color.WHITE, Theme.SEMIBOLD)
    private val ty = textPaint(19f, Theme.withAlpha(Color.WHITE, 0.75f))
    private val big = textPaint(96f, Color.WHITE, Theme.BOLD)
    private val clip = Path()
    /** True while the poster is on its way. */
    var loading = false
        set(v) { field = v; invalidate() }
    override val restZ = 8f
    override val hotZ = 30f
    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, height, Theme.px(Theme.R_CHIP))

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scrim.shader = LinearGradient(0f, h * 0.5f, 0f, h.toFloat(), 0, Theme.withAlpha(Color.BLACK, 0.88f), Shader.TileMode.CLAMP)
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), Theme.px(Theme.R_CHIP), Theme.px(Theme.R_CHIP), Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        box.set(0f, 0f, w, h)
        c.save()
        c.clipPath(clip)
        val a = art
        when {
            a != null -> c.drawBitmap(a, null, box, bmp)
            loading -> { Shimmer.draw(c, box, 0f); postInvalidateOnAnimation() }
            else -> {
                // No poster anywhere: a tinted card with the title's initial.
                fill.color = Theme.mix(Theme.elev, Theme.accent, 0.18f)
                c.drawRect(box, fill)
                val initial = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
                big.color = Theme.withAlpha(Theme.accent, 0.55f)
                c.drawText(initial, (w - big.measureText(initial)) / 2, h * 0.42f, big)
            }
        }
        c.drawRect(box, scrim)
        val pad = Theme.px(16f)
        val lay = StaticLayout.Builder.obtain(title, 0, title.length, tp, (w - pad * 2).toInt())
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save()
        c.translate(pad, h - pad - lay.height - Theme.px(26f))
        lay.draw(c)
        c.restore()
        c.drawText(year, pad, h - pad, ty)
        c.restore()
        drawRing(c, Theme.px(Theme.R_CHIP))
    }
}

/**
 * App tile: the app's icon fills a rounded square, with the name underneath. The square is as wide
 * as the view; the rest of the height holds the label.
 */
class AppTile(ctx: Context) : FocusView(ctx) {
    var icon: Bitmap? = null
        set(v) { field = v; invalidate() }
    var glyph = 0
    var label = ""
    var dashed = false
    var dim = false
    /** Picked up in move mode: accent ring and arrows show where it can go. */
    var lifted = false
        set(v) { field = v; invalidate() }
    /** In a grid the lifted tile can also go up and down. */
    var liftVertical = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tp = textPaint(24f, Theme.TEXT, Theme.MEDIUM)
    private val arrow = Path()
    private val clip = Path()
    private val ib = RectF()
    override val focusScale = 1.08f
    override val restZ get() = if (dashed) 0f else 10f
    override val hotZ get() = if (dashed) 0f else 30f

    /** Home's Cinema focus: a bar in the icon foot instead of a ring. Other screens keep the ring. */
    var cinema = false
        set(v) { field = v; ringless = v; invalidate() }

    // Cinema rounds the icon a little less: 36 on a 168 tile.
    private val roundness = if (Theme.CINEMA) 36f / 168f else 0.24f
    private fun radius() = width * roundness

    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, width, radius())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), w.toFloat(), w * roundness, w * roundness, Path.Direction.CW)
        invalidateOutline()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = radius()
        ib.set(0f, 0f, w, w)
        if (dashed) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = Theme.px(3f)
            fill.color = if (hot()) Theme.TEXT_DIM else Theme.outline
            fill.pathEffect = android.graphics.DashPathEffect(floatArrayOf(Theme.px(10f), Theme.px(8f)), 0f)
            val i = Theme.px(1.5f)
            c.drawRoundRect(i, i, w - i, w - i, r, r, fill)
            fill.pathEffect = null
            fill.style = Paint.Style.FILL
        } else {
            c.save()
            c.clipPath(clip)
            fill.color = Theme.elev
            c.drawRect(ib, fill)
            val i = icon
            bmp.alpha = if (dim) 110 else 255
            if (i != null) c.drawBitmap(i, null, ib, bmp)
            c.restore()
        }
        if (icon == null && glyph != 0) {
            val g = context.icon(glyph, if (dashed) Theme.TEXT_DIM else Theme.accent)
            val gs = w * 0.36f
            g.setBounds((ib.centerX() - gs / 2).toInt(), (ib.centerY() - gs / 2).toInt(), (ib.centerX() + gs / 2).toInt(), (ib.centerY() + gs / 2).toInt())
            g.draw(c)
        }
        tp.color = when {
            dim -> Theme.withAlpha(Theme.TEXT_DIM, 0.6f)
            hot() -> Theme.TEXT
            else -> Theme.TEXT_DIM
        }
        val t = tp.fit(label, w + Theme.px(24f))
        c.drawText(t, (w - tp.measureText(t)) / 2, w + Theme.px(24f) - tp.ascent(), tp)
        if (lifted) drawLifted(c, w, r)
        else if (cinema && hot() && !dashed) {
            // A short bar inside the foot of the icon marks focus.
            fill.color = Theme.TEXT
            val bh = Theme.px(5f)
            c.drawRoundRect(w * 0.3f, w - Theme.px(10f) - bh, w * 0.7f, w - Theme.px(10f), bh / 2, bh / 2, fill)
        } else drawRing(c, r, ib)
    }

    private fun drawLifted(c: Canvas, w: Float, r: Float) {
        val sw = Theme.px(7f)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = sw
        fill.color = Theme.accent
        val i = sw / 2
        c.drawRoundRect(i, i, w - i, w - i, r - i, r - i, fill)
        fill.style = Paint.Style.FILL
        // Small arrows just outside the icon on each side it can move to.
        val s = Theme.px(14f)
        val off = Theme.px(12f)
        val mid = w / 2
        arrow.reset()
        arrow.moveTo(-off, mid - s); arrow.lineTo(-off - s, mid); arrow.lineTo(-off, mid + s); arrow.close()
        arrow.moveTo(w + off, mid - s); arrow.lineTo(w + off + s, mid); arrow.lineTo(w + off, mid + s); arrow.close()
        if (liftVertical) {
            arrow.moveTo(mid - s, -off); arrow.lineTo(mid, -off - s); arrow.lineTo(mid + s, -off); arrow.close()
        }
        c.drawPath(arrow, fill)
    }
}

/** Search result row for apps and settings. */
class ResultPill(ctx: Context, var title: String, var subtitle: String, var iconBitmap: Bitmap?, var iconRes: Int) : FocusView(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val t1 = textPaint(26f, Theme.TEXT, Theme.MEDIUM)
    private val t2 = textPaint(21f, Theme.TEXT_DIM)
    private val ib = RectF()
    override val focusScale = 1.04f
    override val restZ = 4f
    override val hotZ = 20f
    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, height, Theme.px(Theme.R_BUTTON))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = Theme.px(28f + 64f + 20f + 32f) + maxOf(t1.measureText(title), t2.measureText(subtitle))
        setMeasuredDimension(maxOf(w, Theme.px(360f)).toInt(), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val r = Theme.px(Theme.R_BUTTON)
        box.set(0f, 0f, width.toFloat(), h)
        fill.color = if (hot()) Theme.float else Theme.elev
        c.drawRoundRect(box, r, r, fill)
        val s = Theme.px(64f)
        val x = Theme.px(28f)
        ib.set(x, (h - s) / 2, x + s, (h + s) / 2)
        val b = iconBitmap
        if (b != null) {
            c.save()
            c.clipPath(Path().apply { addRoundRect(ib, s * 0.24f, s * 0.24f, Path.Direction.CW) })
            c.drawBitmap(b, null, ib, bmp)
            c.restore()
        } else if (iconRes != 0) {
            val g = context.icon(iconRes, Theme.accent)
            val gs = Theme.px(40f)
            g.setBounds((ib.centerX() - gs / 2).toInt(), (ib.centerY() - gs / 2).toInt(), (ib.centerX() + gs / 2).toInt(), (ib.centerY() + gs / 2).toInt())
            g.draw(c)
        }
        val tx = x + s + Theme.px(20f)
        c.drawText(title, tx, h / 2 - Theme.px(4f), t1)
        c.drawText(subtitle, tx, h / 2 + Theme.px(28f), t2)
        drawRing(c, r)
    }
}

// ---------------------------------------------------------------- settings

/** Settings row: tonal icon circle, title and subtitle, then a value, switch, stepper or chevron. */
class SettingRow(ctx: Context, val iconRes: Int, var title: String) : FocusView(ctx) {
    enum class Trail { NONE, CHEVRON, SWITCH, STEPPER, SLIDER, CHECK, VALUE, OPEN, HEART }

    var subtitle = ""
        set(v) { field = v; invalidate() }
    var value = ""
        set(v) { field = v; invalidate() }
    var trail = Trail.CHEVRON
    var checked = false
        set(v) { field = v; invalidate() }
    var divider = true
    var swatch = 0
    /** Fill of the slider trail, 0 to 1. */
    var level = 0f
        set(v) { field = v; invalidate() }
    /** Draws the icon in its own colours, for brand logos. */
    var brandIcon = false
    /** Profile whose picture replaces the icon. */
    var avatar: Profile? = null
        set(v) { field = v; invalidate() }
    private val avatarText = textPaint(30f, Color.WHITE, Theme.BOLD)
    /** Draws the row at half strength, focused or not, for an action that isn't available. */
    var dimmed = false
        set(v) { field = v; invalidate() }
    var onFocused: (() -> Unit)? = null

    override fun draw(c: Canvas) {
        if (!dimmed) { super.draw(c); return }
        // Draws the dimmed view at half opacity.
        val s = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 128)
        super.draw(c)
        c.restoreToCount(s)
    }
    var onStep: ((Int) -> Unit)? = null
    /** Marks the list row whose pane is open while focus is in the pane. */
    var marked = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Hides the divider when the row above is marked. */
    var aboveSelected = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Leans in slightly on focus. Only for rows with room around them, such as the Settings list. */
    var lean = false
    override val focusScale get() = if (lean) 1.015f else 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glyph: Drawable? = null
    private val t1 = textPaint(30f, Theme.TEXT, Theme.MEDIUM)
    private val t2 = textPaint(22f, Theme.TEXT_DIM)
    private val tv = textPaint(26f, Theme.TEXT_DIM)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) onFocused?.invoke()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val step = onStep
        if (step != null && (trail == Trail.STEPPER || trail == Trail.SLIDER)) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { step(-1); return true }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { step(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = Theme.px(Theme.R_CARD)
        val hot = hot()
        t1.typeface = if (hot) Theme.SEMIBOLD else Theme.MEDIUM
        tv.color = if (hot) Theme.TEXT else Theme.TEXT_DIM
        if (hot) {
            // Accent light on the leading edge, fading into the raised fill.
            box.set(0f, 0f, w, h)
            fill.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Theme.mix(Theme.float, Theme.accent, 0.18f), Theme.float, Theme.float),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(box, r, r, fill)
            fill.shader = null
        } else if (marked) {
            box.set(0f, 0f, w, h)
            fill.color = Theme.withAlpha(Theme.accent, 0.09f)
            c.drawRoundRect(box, r, r, fill)
            // Short bar at the left edge, outside the card.
            val bx = -Theme.px(12f)
            fill.shader = LinearGradient(0f, Theme.px(32f), 0f, h - Theme.px(32f), Theme.accent, Theme.duo(Theme.accent), Shader.TileMode.CLAMP)
            c.drawRoundRect(bx, Theme.px(32f), bx + Theme.px(6f), h - Theme.px(32f), Theme.px(3f), Theme.px(3f), fill)
            fill.shader = null
        } else if (divider && !aboveSelected) {
            fill.color = Theme.withAlpha(Theme.outline, 0.6f)
            c.drawRect(Theme.px(32f), 0f, w - Theme.px(32f), Theme.px(1.5f), fill)
        }
        val cs = Theme.px(64f)
        val cx = Theme.px(32f) + cs / 2
        val av = avatar
        if (av != null) {
            // Profile picture in place of the icon.
            val base = Theme.ACCENTS[av.color.coerceIn(0, Theme.ACCENTS.size - 1)].color
            val top = h / 2 - cs / 2
            fill.shader = LinearGradient(cx - cs / 2, top, cx + cs / 2, top + cs, base, Theme.duo(base), Shader.TileMode.CLAMP)
            c.drawCircle(cx, h / 2, cs / 2, fill)
            fill.shader = null
            val t = av.emoji.ifEmpty { av.initial }
            avatarText.textSize = cs * if (av.emoji.isEmpty()) 0.46f else 0.54f
            c.drawText(t, cx - avatarText.measureText(t) / 2, baseline(avatarText, h / 2), avatarText)
        } else {
            // The icon lights up on focus and while its pane is open.
            val lit = (hot || marked) && !brandIcon
            if (lit) {
                // The icon lights up like the Play button.
                val top = h / 2 - cs / 2
                fill.shader = LinearGradient(cx - cs / 2, top, cx + cs / 2, top + cs, Theme.accent, Theme.duo(Theme.accent), Shader.TileMode.CLAMP)
                fill.setShadowLayer(Theme.px(15f), 0f, Theme.px(6f), Theme.withAlpha(Theme.accent, 0.4f))
            } else fill.color = if (brandIcon) Theme.elev else Theme.withAlpha(Theme.accent, 0.16f)
            c.drawCircle(cx, h / 2, cs / 2, fill)
            fill.shader = null
            fill.clearShadowLayer()
            // Brand marks keep their own colours.
            val g = glyph ?: (if (brandIcon) context.getDrawable(iconRes)!! else context.icon(iconRes, Theme.accent)).also { glyph = it }
            if (!brandIcon) g.setTint(if (lit) Theme.onAccent else Theme.accent)
            val gs = Theme.px(if (brandIcon) 38f else 34f)
            g.setBounds((cx - gs / 2).toInt(), (h / 2 - gs / 2).toInt(), (cx + gs / 2).toInt(), (h / 2 + gs / 2).toInt())
            g.draw(c)
        }
        val tx = Theme.px(32f) + cs + Theme.px(24f)
        var right = w - Theme.px(32f)
        when (trail) {
            Trail.SWITCH -> {
                val sw = Theme.px(96f)
                val sh = Theme.px(56f)
                box.set(right - sw, (h - sh) / 2, right, (h + sh) / 2)
                fill.color = Theme.outline
                if (checked) {
                    Theme.accentFill(fill, box.left, box.top, box.right, box.bottom)
                    fill.setShadowLayer(Theme.px(17f), 0f, 0f, Theme.withAlpha(Theme.accent, 0.4f))
                }
                c.drawRoundRect(box, sh / 2, sh / 2, fill)
                fill.shader = null
                fill.clearShadowLayer()
                val k = Theme.px(44f)
                val kx = if (checked) box.right - Theme.px(6f) - k / 2 else box.left + Theme.px(6f) + k / 2
                fill.color = if (checked) Theme.onAccent else Theme.TEXT_DIM
                c.drawCircle(kx, h / 2, k / 2, fill)
                right -= sw + Theme.px(20f)
            }
            Trail.CHEVRON, Trail.OPEN, Trail.HEART -> {
                val gsz = Theme.px(40f)
                val chev = when (trail) {
                    Trail.OPEN -> context.icon(R.drawable.ic_open_in_new, Theme.TEXT_DIM)
                    Trail.HEART -> context.icon(R.drawable.ic_favorite, Theme.accent)
                    else -> context.icon(R.drawable.ic_chevron_right, Theme.TEXT_DIM)
                }
                chev.setBounds((right - gsz).toInt(), (h / 2 - gsz / 2).toInt(), right.toInt(), (h / 2 + gsz / 2).toInt())
                chev.draw(c)
                right -= gsz + Theme.px(12f)
                if (value.isNotEmpty()) {
                    c.drawText(value, right - tv.measureText(value), baseline(tv, h / 2), tv)
                    right -= tv.measureText(value) + Theme.px(16f)
                }
            }
            Trail.STEPPER -> {
                val gsz = Theme.px(36f)
                val rch = context.icon(R.drawable.ic_keyboard_arrow_right, if (hot()) Theme.TEXT else Theme.TEXT_DIM)
                rch.setBounds((right - gsz).toInt(), (h / 2 - gsz / 2).toInt(), right.toInt(), (h / 2 + gsz / 2).toInt())
                rch.draw(c)
                right -= gsz + Theme.px(8f)
                val vw = tv.measureText(value)
                c.drawText(value, right - vw, baseline(tv, h / 2), tv)
                right -= vw + Theme.px(8f)
                val lch = context.icon(R.drawable.ic_keyboard_arrow_left, if (hot()) Theme.TEXT else Theme.TEXT_DIM)
                lch.setBounds((right - gsz).toInt(), (h / 2 - gsz / 2).toInt(), right.toInt(), (h / 2 + gsz / 2).toInt())
                lch.draw(c)
                right -= gsz + Theme.px(16f)
            }
            Trail.SLIDER -> {
                // A track with the value beside it; left and right adjust while focused.
                val tw = Theme.px(200f)
                val th = Theme.px(10f)
                val k = Theme.px(28f)
                val cy = h / 2
                box.set(right - tw, cy - th / 2, right, cy + th / 2)
                fill.color = Theme.outline
                c.drawRoundRect(box, th / 2, th / 2, fill)
                val fx = box.left + tw * level.coerceIn(0f, 1f)
                fill.color = Theme.accent
                c.drawRoundRect(box.left, box.top, maxOf(fx, box.left + th), box.bottom, th / 2, th / 2, fill)
                fill.color = if (hot()) Theme.TEXT else Theme.accent
                c.drawCircle(fx.coerceIn(box.left + k / 2, box.right - k / 2), cy, k / 2, fill)
                right -= tw + Theme.px(24f)
                if (value.isNotEmpty()) {
                    val vw = tv.measureText(value)
                    c.drawText(value, right - vw, baseline(tv, cy), tv)
                    right -= vw + Theme.px(16f)
                }
            }
            Trail.CHECK -> {
                val gsz = Theme.px(40f)
                if (checked) {
                    val g = context.icon(R.drawable.ic_check, Theme.accent)
                    g.setBounds((right - gsz).toInt(), (h / 2 - gsz / 2).toInt(), right.toInt(), (h / 2 + gsz / 2).toInt())
                    g.draw(c)
                }
                right -= gsz + Theme.px(12f)
            }
            Trail.VALUE -> if (value.isNotEmpty()) {
                c.drawText(value, right - tv.measureText(value), baseline(tv, h / 2), tv)
                right -= tv.measureText(value) + Theme.px(16f)
            }
            Trail.NONE -> {}
        }
        if (swatch != 0) {
            fill.color = swatch
            c.drawCircle(right - Theme.px(20f), h / 2, Theme.px(20f), fill)
            right -= Theme.px(56f)
        }
        val avail = right - tx
        if (subtitle.isEmpty()) {
            c.drawText(t1.fit(title, avail), tx, baseline(t1, h / 2), t1)
        } else {
            c.drawText(t1.fit(title, avail), tx, h / 2 - Theme.px(6f), t1)
            c.drawText(t2.fit(subtitle, avail), tx, h / 2 + Theme.px(30f), t2)
        }
        drawRing(c, r)
    }
}

/** Accent capsule: six tonal bands, a check when chosen. */
class Swatch(ctx: Context, val index: Int) : FocusView(ctx) {
    var chosen = false
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val check = ctx.icon(R.drawable.ic_check, Color.WHITE)
    override val focusScale = 1.08f
    override val restZ = 4f
    override val hotZ = 18f
    override fun shadowShape(o: android.graphics.Outline) = o.setOval(0, 0, width, height)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset()
        clip.addCircle(w / 2f, h / 2f, minOf(w, h) / 2f, Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val col = Theme.ACCENTS[index.coerceIn(0, Theme.ACCENTS.size - 1)].color
        fill.color = col
        c.drawPath(clip, fill)
        // Rim about 35% darker than the swatch.
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = Theme.px(3f)
        fill.color = Theme.mix(col, Color.BLACK, 0.35f)
        c.drawCircle(w / 2, h / 2, minOf(w, h) / 2 - Theme.px(1.5f), fill)
        fill.style = Paint.Style.FILL
        if (chosen) {
            check.setTint(if (Color.luminance(col) > 0.6f) Color.BLACK else Color.WHITE)
            val s = Theme.ipx(34f)
            check.setBounds(((w - s) / 2).toInt(), ((h - s) / 2).toInt(), ((w + s) / 2).toInt(), ((h + s) / 2).toInt())
            check.draw(c)
        }
        box.set(0f, 0f, w, h)
        drawRing(c, minOf(w, h) / 2)
    }
}

/** Circle colour swatch: a darker rim, a white inner ring when focused and a check when chosen. */
class ColorDot(ctx: Context, val index: Int) : FocusView(ctx) {
    var chosen = false
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = ctx.icon(R.drawable.ic_check, Color.WHITE)
    override val focusScale = 1.12f
    override val restZ = 4f
    override val hotZ = 18f
    override fun shadowShape(o: android.graphics.Outline) = o.setOval(0, 0, width, height)

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        val col = Theme.ACCENTS[index.coerceIn(0, Theme.ACCENTS.size - 1)].color
        val rim = Theme.px(3f)
        fill.style = Paint.Style.FILL
        fill.color = Theme.mix(col, Color.BLACK, 0.35f)
        c.drawCircle(r, r, r, fill)
        fill.color = col
        c.drawCircle(r, r, r - rim, fill)
        if (hot()) {
            // Inner white ring, set in from the rim by a small gap.
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = Theme.px(3f)
            fill.color = Color.WHITE
            c.drawCircle(r, r, r - rim - Theme.px(2f) - Theme.px(1.5f), fill)
            fill.style = Paint.Style.FILL
        }
        if (chosen) {
            check.setTint(if (Color.luminance(col) > 0.6f) Color.BLACK else Color.WHITE)
            val s = (r * 0.9f).toInt()
            check.setBounds((r - s / 2).toInt(), (r - s / 2).toInt(), (r + s / 2).toInt(), (r + s / 2).toInt())
            check.draw(c)
        }
    }
}

/**
 * Simplified drawing of Home for previews in Settings: the same layout as blocks and bars,
 * in any accent, darkness, glass strength and spotlight setting.
 */
class MiniHome(ctx: Context) : FocusView(ctx, false) {
    var accentIndex = Theme.accentIndex
        set(v) { field = v; invalidate() }
    var dark = Theme.trueDark
        set(v) { field = v; invalidate() }
    var glass = Theme.glass
        set(v) { field = v; invalidate() }
    var spotlight = true
        set(v) { field = v; invalidate() }
    var outlined = false
        set(v) { field = v; invalidate() }
    var ringColor = 0
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val rf = RectF()
    override val focusScale = 1.03f
    override val restZ = 14f
    override val hotZ = 34f

    private fun radius() = Theme.px(24f)

    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, height, radius())

    fun makeFocusable(on: Boolean) {
        isFocusable = on
        isClickable = on
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius(), radius(), Path.Direction.CW)
        invalidateOutline()
    }

    /** Rounded block in Home's 1920 x 1080 design space. */
    private fun block(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        fill.color = color
        rf.set(x, y, x + w, y + h)
        c.drawRoundRect(rf, r, r, fill)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = Theme.palette(accentIndex, dark)
        val text = Theme.withAlpha(Theme.TEXT, 0.92f)
        val dim = Theme.withAlpha(Theme.TEXT_DIM, 0.55f)
        c.save()
        c.clipPath(clip)
        c.drawColor(p.bg)
        c.scale(w / 1920f, h / 1080f)
        var y: Float
        if (spotlight) {
            block(c, 96f, 230f, 560f, 72f, 36f, text)
            block(c, 96f, 330f, 380f, 24f, 12f, dim)
            for (i in 0 until 3) block(c, 96f + i * 132f, 378f, 116f, 40f, 20f, Theme.withAlpha(p.float, 0.9f))
            block(c, 96f, 450f, 740f, 22f, 11f, dim)
            block(c, 96f, 486f, 620f, 22f, 11f, dim)
            block(c, 96f, 540f, 360f, 8f, 4f, Theme.withAlpha(Color.WHITE, 0.18f))
            block(c, 96f, 540f, 150f, 8f, 4f, p.accent)
            block(c, 96f, 590f, 230f, 76f, 38f, p.accent)
            block(c, 346f, 590f, 290f, 76f, 38f, p.float)
            for (i in 0 until 4) block(c, 656f + i * 96f, 590f, 76f, 76f, 38f, p.float)
            block(c, 96f, 720f, 330f, 30f, 15f, text)
            for (i in 0 until 5) {
                val x = 96f + i * 412f
                block(c, x, 780f, 384f, 216f, 40f, if (i == 0) p.float else p.elev)
                block(c, x + 24f, 960f, 140f, 8f, 4f, p.accent)
            }
            y = 1040f
        } else {
            y = 220f
        }
        // Pinned apps, then All apps underneath.
        var ty = y
        for (r in 0 until 3) {
            if (r < 2) {
                block(c, 96f, ty, if (r == 0) 280f else 200f, 30f, 15f, text)
                ty += 70f
            }
            for (i in 0 until 8) {
                val x = 96f + i * 212f
                block(c, x, ty, 168f, 168f, 40f, if (r == 0 && i < 3) Theme.mix(p.elev, p.accent, 0.35f) else p.elev)
                block(c, x + 30f, ty + 190f, 108f, 18f, 9f, dim)
            }
            ty += 264f
        }
        // Top bar: glass search with mic, clock and weather, settings.
        val glassC = Theme.withAlpha(p.float, glass.coerceIn(Theme.GLASS_MIN, 1f))
        block(c, 96f, 48f, 760f, 88f, 44f, glassC)
        block(c, 140f, 82f, 300f, 20f, 10f, dim)
        fill.color = p.accent
        c.drawCircle(812f, 92f, 32f, fill)
        block(c, 1180f, 48f, 532f, 88f, 44f, glassC)
        block(c, 1220f, 80f, 220f, 24f, 12f, dim)
        block(c, 1500f, 70f, 150f, 30f, 15f, text)
        block(c, 1736f, 48f, 88f, 88f, 44f, glassC)
        c.restore()
        val r = radius()
        val i = Theme.px(1f)
        edge.strokeWidth = Theme.px(2f)
        edge.color = Theme.withAlpha(Color.WHITE, 0.10f)
        c.drawRoundRect(i, i, w - i, h - i, r, r, edge)
        if (outlined) {
            edge.strokeWidth = Theme.px(5f)
            edge.color = if (ringColor != 0) ringColor else p.accent
            val o = Theme.px(2.5f)
            c.drawRoundRect(o, o, w - o, h - o, r, r, edge)
        }
        drawRing(c, r)
    }
}

/**
 * Stylised map card for the weather location: a faint latitude and longitude grid around the
 * place, radar rings and a pin, with the town, coordinates and current weather. Drawn locally
 * without a map service.
 */
class LocationCard(ctx: Context) : View(ctx) {
    var city = ""
    var lat = Double.NaN
    var lon = Double.NaN
    var weatherLine = ""
    var automatic = true
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tCity = textPaint(40f, Theme.TEXT, Theme.SEMIBOLD)
    private val tSmall = textPaint(22f, Theme.TEXT_DIM)
    private val tChip = textPaint(20f, Theme.onAccent, Theme.SEMIBOLD)
    private val clip = Path()
    private val r = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), Theme.px(Theme.R_CARD), Theme.px(Theme.R_CARD), Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.70f
        val cy = h * 0.48f
        c.save()
        c.clipPath(clip)
        c.drawColor(Theme.elev)
        // Grid lines one degree apart, offset so the place sits between them.
        val step = Theme.px(64f)
        val ox = if (lon.isNaN()) 0f else (((lon % 1.0) + 1.0) % 1.0).toFloat() * step
        val oy = if (lat.isNaN()) 0f else (((lat % 1.0) + 1.0) % 1.0).toFloat() * step
        line.strokeWidth = Theme.px(1.5f)
        line.color = Theme.withAlpha(Theme.TEXT, 0.06f)
        var x = cx - ox - step * 12
        while (x < w) { c.drawLine(x, 0f, x, h, line); x += step }
        var y = cy + oy - step * 12
        while (y < h) { c.drawLine(0f, y, w, y, line); y += step }
        // Glow and radar rings around the pin.
        fill.shader = RadialGradient(cx, cy, h * 0.75f, Theme.withAlpha(Theme.accent, 0.28f), Theme.withAlpha(Theme.accent, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        line.strokeWidth = Theme.px(2f)
        for (i in 1..3) {
            line.color = Theme.withAlpha(Theme.accent, 0.42f - i * 0.11f)
            c.drawCircle(cx, cy, Theme.px(34f) * i, line)
        }
        val ps = Theme.ipx(56f)
        val pin = context.icon(R.drawable.ic_location_on, Theme.accent)
        pin.setBounds((cx - ps / 2).toInt(), (cy - ps).toInt() + Theme.ipx(6f), (cx + ps / 2).toInt(), cy.toInt() + Theme.ipx(6f))
        pin.draw(c)
        // Left side fades to the card colour.
        fill.shader = LinearGradient(0f, 0f, w * 0.62f, 0f, Theme.elev, Theme.withAlpha(Theme.elev, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        c.restore()

        val pad = Theme.px(32f)
        val chip = if (automatic) "AUTOMATIC" else "CHOSEN CITY"
        val cw = tChip.measureText(chip) + Theme.px(28f)
        val chH = Theme.px(38f)
        r.set(pad, pad, pad + cw, pad + chH)
        Theme.accentFill(fill, r.left, r.top, r.right, r.bottom)
        c.drawRoundRect(r, chH / 2, chH / 2, fill)
        fill.shader = null
        c.drawText(chip, pad + Theme.px(14f), baseline(tChip, pad + chH / 2), tChip)
        c.drawText(tCity.fit(city.ifEmpty { "Locating…" }, w * 0.55f), pad, pad + chH + Theme.px(62f), tCity)
        if (!lat.isNaN()) {
            val ns = if (lat >= 0) "N" else "S"
            val ew = if (lon >= 0) "E" else "W"
            c.drawText(String.format(java.util.Locale.US, "%.2f° %s, %.2f° %s", Math.abs(lat), ns, Math.abs(lon), ew), pad, pad + chH + Theme.px(104f), tSmall)
        }
        if (weatherLine.isNotEmpty()) c.drawText(tSmall.fit(weatherLine, w * 0.55f), pad, h - pad, tSmall)
    }
}

/** Rounded panel background drawn on a FrameLayout, with a real elevation shadow. */
class Panel(ctx: Context, private val radius: Float, private val color: () -> Int, private val shadowed: Boolean = true) : FrameLayout(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, Theme.px(radius))
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        elevation = if (shadowed) Theme.px(36f) else 0f
    }

    override fun onDraw(c: Canvas) {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.color = color()
        c.drawRoundRect(box, Theme.px(radius), Theme.px(radius), fill)
    }
}

/** Restores focus after a rebuild with no focus animation. */
fun View.focusQuietly() = FocusView.quietly { requestFocus() }
