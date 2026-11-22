package com.robbydock.scene

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.animation.PathInterpolator

/**
 * Design tokens, scaled for a 10 foot screen. Surfaces step up in depth from the base,
 * each mixed toward a light tint of the accent.
 */
object Theme {
    class Accent(val name: String, val color: Int)

    val ACCENTS = listOf(
        Accent("Coral", 0xFFFF7A66.toInt()), Accent("Rose", 0xFFF43F5E.toInt()),
        Accent("Orange", 0xFFF97316.toInt()), Accent("Amber", 0xFFF59E0B.toInt()),
        Accent("Lime", 0xFF84CC16.toInt()), Accent("Fern", 0xFF41C72C.toInt()),
        Accent("Green", 0xFF22C55E.toInt()), Accent("Teal", 0xFF14B8A6.toInt()),
        Accent("Sky", 0xFF0EA5E9.toInt()), Accent("Blue", 0xFF3D8BFF.toInt()),
        Accent("Indigo", 0xFF6366F1.toInt()), Accent("Violet", 0xFFA855F7.toInt()),
        Accent("Magenta", 0xFFD946A6.toInt()), Accent("Slate", 0xFF64748B.toInt()),
        // Soft tints.
        Accent("Blush", 0xFFFDA4AF.toInt()), Accent("Peach", 0xFFFDBA74.toInt()),
        Accent("Butter", 0xFFFDE047.toInt()), Accent("Mint", 0xFF86EFAC.toInt()),
        Accent("Aqua", 0xFF5EEAD4.toInt()), Accent("Ice", 0xFF7DD3FC.toInt()),
        Accent("Lavender", 0xFFC4B5FD.toInt()),
    )

    const val BRAND_BG = 0xFF17111B.toInt()
    const val TEXT = 0xFFF3E9E1.toInt()
    const val TEXT_DIM = 0xFFB8AAB4.toInt()
    const val STAR = 0xFFF5C469.toInt()

    // Corner radii.
    const val R_CHIP = 24f
    const val R_BUTTON = 36f
    const val R_CARD = 48f
    const val R_SHEET = 56f

    /** Lowest glass strength. */
    const val GLASS_MIN = 0.45f

    /** Motion level: 0 full, 1 reduced (shorter, no movement), 2 off. */
    var motion = 0
    private val motionScale get() = when (motion) { 0 -> 1f; 1 -> 0.6f; else -> 0f }
    val FOCUS_MS get() = (140 * motionScale).toLong()
    val MEDIUM_MS get() = (220 * motionScale).toLong()
    /** Screen transitions: overlays fade, and side panels slide in at full motion. */
    val SCREEN_MS get() = (260 * motionScale).toLong()
    /** Small state changes: presses, icon swaps. */
    val FAST_MS get() = if (motion == 2) 0L else 120L
    /** Hero swaps and larger surfaces. */
    val SLOW_MS get() = when (motion) { 0 -> 360L; 1 -> 200L; else -> 0L }
    /** Background art and screensaver. */
    val AMBIENT_MS get() = when (motion) { 0 -> 900L; 1 -> 600L; else -> 0L }
    val moves get() = motion == 0
    const val FOCUS_SCALE = 1.06f

    /**
     * Cinema look for Home: a bare top bar, a larger title, fewer spotlight buttons, and focus shown
     * by lift and a short bar instead of a ring.
     */
    const val CINEMA = true
    val EASE = PathInterpolator(0.22f, 0.25f, 0f, 1f)

    // Outfit is bundled so text measures the same on every device; the system font is the fallback
    // until init runs, and for any character Outfit lacks.
    var LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); private set
    var REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL); private set
    var MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); private set
    var SEMIBOLD: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else MEDIUM; private set
    var BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD); private set

    private fun outfit(ctx: Context, weight: Int): Typeface? = try {
        Typeface.Builder(ctx.assets, "fonts/Outfit.ttf")
            .setFontVariationSettings("'wght' $weight")
            .setWeight(weight)
            .setFallback("sans-serif")
            .build()
    } catch (e: Exception) { null }

    private fun loadFonts(ctx: Context) {
        if (fontsLoaded) return
        outfit(ctx, 300)?.let { LIGHT = it }
        outfit(ctx, 400)?.let { REGULAR = it }
        outfit(ctx, 500)?.let { MEDIUM = it }
        outfit(ctx, 600)?.let { SEMIBOLD = it }
        outfit(ctx, 700)?.let { BOLD = it }
        fontsLoaded = true
    }

    private var fontsLoaded = false

    var accentIndex = 0
        private set
    var trueDark = false
        private set
    var glass = 0.55f
        private set

    var accent = ACCENTS[0].color; private set
    var onAccent = 0; private set
    var bg = BRAND_BG; private set
    var surface = 0; private set
    var elev = 0; private set
    var float = 0; private set
    var outline = 0; private set
    var shadow = 0; private set

    /** Pixels per design pixel. Layout is authored on a 1920x1080 canvas. */
    var u = 1f
        private set
    /** Screen size in design pixels. One side is 1920 or 1080; the other grows on screens that are not 16:9. */
    var W = 1920f
        private set
    var H = 1080f
        private set

    init {
        apply(0, false, 0.55f)
    }

    /** The window size Home measured, which wins over the display size when they differ. */
    var measured: android.graphics.Rect? = null

    /** True when [w] by [h] pixels is not the size the layout was made for. */
    fun sizeDiffers(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        val lw = maxOf(w, h); val lh = minOf(w, h)
        return kotlin.math.abs(lw - W * u) > 2f || kotlin.math.abs(lh - H * u) > 2f
    }

    fun init(ctx: Context) {
        // The whole window, including any space under hidden system bars.
        val b = measured ?: if (android.os.Build.VERSION.SDK_INT >= 30) {
            ctx.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(m)
            android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
        }
        val w = maxOf(b.width(), b.height()).toFloat()
        val h = minOf(b.width(), b.height()).toFloat()
        u = minOf(w / 1920f, h / 1080f)
        W = w / u
        H = h / u
        loadFonts(ctx.applicationContext)
    }

    fun apply(accentIdx: Int, dark: Boolean, glassAlpha: Float) {
        accentIndex = accentIdx.coerceIn(0, ACCENTS.size - 1)
        trueDark = dark
        glass = glassAlpha.coerceIn(GLASS_MIN, 1f)
        val p = palette(accentIndex, dark)
        accent = p.accent; onAccent = p.onAccent; bg = p.bg; surface = p.surface
        elev = p.elev; float = p.float; outline = p.outline; shadow = p.shadow
    }

    class Palette(
        val accent: Int, val onAccent: Int, val bg: Int, val surface: Int,
        val elev: Int, val float: Int, val outline: Int, val shadow: Int,
    )

    /** Builds the full palette for any accent and darkness. */
    fun palette(accentIdx: Int, dark: Boolean): Palette {
        val a = ACCENTS[accentIdx.coerceIn(0, ACCENTS.size - 1)].color
        val base = if (dark) Color.BLACK else BRAND_BG
        val light = mix(Color.WHITE, a, 0.62f)
        val on = if (luminance(a) > 0.36f) mix(Color.BLACK, a, 0.22f) else Color.WHITE
        return Palette(
            accent = a, onAccent = on, bg = base,
            surface = mix(base, light, 0.075f), elev = mix(base, light, 0.125f),
            float = mix(base, light, 0.185f), outline = mix(base, light, 0.26f),
            shadow = withAlpha(mix(Color.BLACK, a, 0.10f), 0.55f),
        )
    }

    /** Six tonal bands for the accent capsule. */
    fun bands(accentIdx: Int): IntArray {
        val a = ACCENTS[accentIdx].color
        return intArrayOf(
            a, mix(a, Color.WHITE, 0.55f), mix(a, TEXT_DIM, 0.55f),
            mix(a, Color.BLACK, 0.6f), mix(a, 0xFF8D7F8D.toInt(), 0.55f), mix(a, BRAND_BG, 0.72f),
        )
    }

    fun px(v: Float) = v * u
    fun px(v: Int) = v * u
    fun ipx(v: Float) = (v * u).toInt()

    fun mix(from: Int, to: Int, t: Float): Int {
        val r = Color.red(from) + (Color.red(to) - Color.red(from)) * t
        val g = Color.green(from) + (Color.green(to) - Color.green(from)) * t
        val b = Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t
        return Color.argb(255, r.toInt(), g.toInt(), b.toInt())
    }

    /** Second colour for duo-tone fills: a nearby hue, warm colours lean pink and cool colours lean blue or magenta. */
    fun duo(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        val warm = hsv[0] < 90f || hsv[0] > 300f
        hsv[0] = (hsv[0] + if (warm) -42f else 55f + 360f) % 360f
        hsv[1] = (hsv[1] * 1.12f).coerceAtMost(1f)
        hsv[2] *= 0.92f
        return Color.HSVToColor(hsv)
    }

    /** Fills [p] with the accent duo tone across the box, top left to bottom right. Clear the shader after drawing. */
    fun accentFill(p: android.graphics.Paint, l: Float, t: Float, r: Float, b: Float) {
        p.shader = android.graphics.LinearGradient(l, t, r, b, accent, duo(accent), android.graphics.Shader.TileMode.CLAMP)
    }

    fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun luminance(c: Int): Float {
        fun ch(v: Int): Float {
            val s = v / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * ch(Color.red(c)) + 0.7152f * ch(Color.green(c)) + 0.0722f * ch(Color.blue(c))
    }
}
