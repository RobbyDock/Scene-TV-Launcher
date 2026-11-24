package com.robbydock.scene

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.service.dreams.DreamService
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the screensaver shows besides the clock. Home fills it while it runs; the system
 * screensaver reads it in the same process. Empty when Home is gone, so the glows show alone.
 */
object SaverData {
    /** A title for the art behind the glows, and the line that says why it is there. */
    class Title(val id: String, val type: String, val name: String, val line: String)
    /** Reads the titles afresh each round, so aired episodes drop out. */
    @Volatile var titles: (() -> List<Title>)? = null
    /** Weather icon, its tint and a line such as "70° · Partly cloudy"; null when off or not known. */
    @Volatile var weather: (() -> Triple<Int, Int, String>?)? = null
}

/**
 * Screensaver with ambient colour, your titles behind it and one block of time, date, weather
 * and the shown title, or a large clock on near-black. The clock keeps to set spots in a fixed
 * order and fades to the next every [MOVE_MS]. Any key ends it.
 */
class ClockDream : DreamService() {

    companion object {
        /** How long the clock stays in one spot before it fades to the next. */
        private const val MOVE_MS = 180_000L
        /** Fade out, then in, when the clock changes spot. */
        private const val SPOT_FADE_MS = 900L

        private fun component(ctx: Context) = ComponentName(ctx, ClockDream::class.java)

        const val AMBIENT = 0
        const val CLOCK = 1

        /** 0 ambient colour, 1 large clock. Device-wide. */
        fun mode(ctx: Context) = ctx.getSharedPreferences("state", Context.MODE_PRIVATE).getInt("dreamMode", AMBIENT)
        fun setMode(ctx: Context, m: Int) = ctx.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putInt("dreamMode", m).apply()

        private const val COMPONENTS = "screensaver_components"
        private const val ENABLED = "screensaver_enabled"

        /** True unless the screensaver was turned off in Scene's settings. */
        fun isOn(ctx: Context) = ctx.packageManager.getComponentEnabledSetting(component(ctx)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        /** True when Android lets Scene choose the system screensaver itself. Rare; most TVs never grant it. */
        fun canSelect(ctx: Context) =
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /** True when Android will start Scene's screensaver. */
        fun isSelected(ctx: Context): Boolean {
            val r = ctx.contentResolver
            val list = android.provider.Settings.Secure.getString(r, COMPONENTS).orEmpty()
            val enabled = android.provider.Settings.Secure.getInt(r, ENABLED, 1) == 1
            return enabled && list.split(',').firstOrNull() == component(ctx).flattenToString()
        }

        /** Android's own screensaver picker, on devices that have one. */
        fun pickerIntent(ctx: Context): android.content.Intent? =
            android.content.Intent(android.provider.Settings.ACTION_DREAM_SETTINGS).takeIf { it.resolveActivity(ctx.packageManager) != null }

        /** Home shows Scene's screensaver itself after this long without input, when Android won't start it. */
        const val HOME_IDLE_MS = 5 * 60_000L

        /** Off removes Scene from Android's screensaver list. Device-wide. */
        fun setOn(ctx: Context, on: Boolean) {
            ctx.packageManager.setComponentEnabledSetting(component(ctx),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
            select(ctx)
        }

        /** Makes Android's screensaver match Scene's setting, when Android allows it. */
        fun select(ctx: Context) {
            if (!canSelect(ctx)) return
            val r = ctx.contentResolver
            try {
                if (isOn(ctx)) {
                    android.provider.Settings.Secure.putString(r, COMPONENTS, component(ctx).flattenToString())
                    android.provider.Settings.Secure.putInt(r, ENABLED, 1)
                } else if (isSelected(ctx)) {
                    android.provider.Settings.Secure.putInt(r, ENABLED, 0)
                }
            } catch (e: SecurityException) {
                // Permission was revoked.
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        Theme.init(this)
        // Loads the current profile's accent and clock style.
        if (Profiles.list.isEmpty()) Profiles.load(this)
        val cfg = Store.load(this) ?: Config()
        val accent = Theme.ACCENTS[cfg.accent.coerceIn(0, Theme.ACCENTS.size - 1)].color
        // While music plays, the music screensaver takes over when it is on.
        Images.init(this)
        NowPlaying.start(this)
        if (NowPlaying.saver(this) && NowPlaying.state == 1) {
            Theme.apply(cfg.accent, cfg.trueDark, cfg.glass)
            Theme.motion = cfg.motion
            setContentView(MusicSaver(this))
            return
        }
        setContentView(if (mode(this) == AMBIENT) Ambient(this, accent, cfg.clock, cfg.motion) else Face(this, accent, cfg.clock, cfg.motion))
    }

    /**
     * Soft fields of colour that drift, swell and fade on unrelated orbits, while the colours
     * cross-fade from one set to the next every few minutes. Your titles fade behind them, and
     * one block of time, date, weather and the shown title keeps to a corner, moving to the next
     * in a fixed order. With motion off, all of it holds still. Drawn live.
     */
    class Ambient(ctx: Context, accent: Int, private val clockMode: Int, private val motion: Int) : View(ctx) {
        private val scenes = Scenery.tour(accent)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val scale = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG)
        private var radius = 0f
        // The colour is drawn small and scaled up.
        private val small = android.graphics.Bitmap.createBitmap(SMALL_W, SMALL_H, android.graphics.Bitmap.Config.ARGB_8888)
        private val smallCanvas = Canvas(small)
        private val full = android.graphics.Rect()
        private val start = android.os.SystemClock.uptimeMillis()
        private val still = motion == 2
        private val frame = object : Runnable {
            // Redraws about 30 times a second, or once a minute when motion is off.
            override fun run() { invalidate(); postDelayed(this, if (still) 60_000L else 33L) }
        }
        private companion object {
            const val SMALL_W = 320; const val SMALL_H = 180
            /** Seconds each set holds, then seconds it blends into the next. */
            const val HOLD = 120.0; const val FADE = 30.0
            /** How long each title shows, its cross-fade and its slow pan. */
            const val SHOW_MS = 30_000L; const val ART_FADE_MS = 2_400L; const val PAN_MS = 34_000L
            /** Strength of the art over the glows, so the colour still leads. */
            const val ART_ALPHA = 0.5f
        }

        // ---- title art

        /** One title's art and when it started to show. */
        private class Layer(val bmp: android.graphics.Bitmap, val at: Long)
        private var cur: Layer? = null
        private var prev: Layer? = null
        private var titles: List<SaverData.Title> = emptyList()
        private var next = 0
        /** The title on screen, never one still loading. */
        private var shown: SaverData.Title? = null
        private val show = object : Runnable {
            override fun run() { nextTitle(0); postDelayed(this, SHOW_MS) }
        }
        private val artPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val artMatrix = android.graphics.Matrix()

        /** Loads the next title's art. One that won't load is skipped at once; when none loads, the glows show alone. */
        private fun nextTitle(tries: Int) {
            if (!isAttachedToWindow) return
            // The list is read again each round, so it stays current.
            if (next >= titles.size) { titles = SaverData.titles?.invoke().orEmpty(); next = 0 }
            if (titles.isEmpty() || tries >= titles.size) return
            val h = titles[next++]
            Net.first({
                // Show art only, never an episode still.
                if (h.id.startsWith("kitsu:")) Coming.kitsuMeta(context, h.id)?.optString("background")?.ifEmpty { null }
                else Watch.meta(context, h.type, h.id)?.let { it.background ?: it.poster }
            }) { url ->
                if (!isAttachedToWindow) return@first
                if (url == null) { nextTitle(tries + 1); return@first }
                Images.load(url, Theme.ipx(1280f), exact = false) { b ->
                    if (!isAttachedToWindow) return@load
                    if (b == null) { nextTitle(tries + 1); return@load }
                    prev = cur
                    cur = Layer(b, android.os.SystemClock.uptimeMillis())
                    shown = h
                    invalidate()
                }
            }
        }

        /** Draws [l] to cover the screen, kept to its upper part, slowly zooming and panning. */
        private fun drawLayer(c: Canvas, l: Layer, alpha: Float, now: Long) {
            val p = if (still) 0f else ((now - l.at).toFloat() / PAN_MS).coerceIn(0f, 1f)
            val zoom = 1.02f + 0.08f * p
            val s = maxOf(width / l.bmp.width.toFloat(), height / l.bmp.height.toFloat()) * zoom
            val dw = l.bmp.width * s
            val dh = l.bmp.height * s
            artMatrix.setScale(s, s)
            artMatrix.postTranslate((width - dw) / 2f - 0.015f * width * p, (height - dh) * 0.2f)
            artPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            c.drawBitmap(l.bmp, artMatrix, artPaint)
        }

        // ---- the block

        private val ink = Theme.TEXT
        private val time = textPaint(132f, ink, Theme.SEMIBOLD).apply { letterSpacing = -0.015f }
        private val date = textPaint(32f, Theme.withAlpha(ink, 0.72f), Theme.MEDIUM)
        private val wx = textPaint(28f, Theme.withAlpha(ink, 0.72f), Theme.MEDIUM)
        private val why = textPaint(22f, Theme.withAlpha(ink, 0.6f), Theme.SEMIBOLD).apply { letterSpacing = 0.07f }
        private val name = textPaint(40f, ink, Theme.SEMIBOLD)
        private val rule = android.graphics.Paint().apply { color = Theme.withAlpha(ink, 0.16f) }
        private val shade = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
        /** Corners in the order the block visits them: bottom left, bottom right, top right, top left. */
        private var corner = 0
        private var cornerAt = start
        /** When the block faded back in, or 0 while it is not fading. */
        private var fadeInAt = 0L

        /** The block's opacity, and its move to the next corner when its time there is up. */
        private fun blockAlpha(now: Long): Float {
            val el = now - cornerAt
            if (still) { if (el >= MOVE_MS) { corner = (corner + 1) % 4; cornerAt = now }; return 1f }
            if (el >= MOVE_MS + SPOT_FADE_MS) { corner = (corner + 1) % 4; cornerAt = now; fadeInAt = now }
            else if (el >= MOVE_MS) return 1f - (el - MOVE_MS).toFloat() / SPOT_FADE_MS
            if (fadeInAt > 0L) {
                val f = (now - fadeInAt).toFloat() / SPOT_FADE_MS
                if (f >= 1f) fadeInAt = 0L else return f
            }
            return 1f
        }

        private fun drawBlock(c: Canvas, now: Long, t: Double) {
            val a = blockAlpha(now)
            if (a <= 0f) return
            val right = corner == 1 || corner == 2
            val bottom = corner == 0 || corner == 1
            // A shade in the block's corner, so the text reads on any art.
            val m = android.graphics.Matrix().apply { setScale(width * 0.6f, height * 0.65f); postTranslate(if (right) width.toFloat() else 0f, if (bottom) height.toFloat() else 0f) }
            shade.shader = android.graphics.RadialGradient(0f, 0f, 1f, intArrayOf(0x9E000000.toInt(), 0), floatArrayOf(0f, 0.7f), android.graphics.Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            shade.alpha = (255 * a).toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)

            val nowDate = Date()
            val is24 = when (clockMode) { 1 -> false; 2 -> true; else -> android.text.format.DateFormat.is24HourFormat(context) }
            val tText = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(nowDate)
            val dText = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(nowDate)
            val weather = SaverData.weather?.invoke()
            val title = shown
            // Heights in design units: time, date, then the weather and the title when known.
            val h = 178f + (if (weather != null) 44f else 0f) + (if (title != null) 138f else 0f)
            val shift = if (still) 0f else Theme.px(8f) * Math.sin(t * 0.05).toFloat()
            val shiftY = if (still) 0f else Theme.px(6f) * Math.cos(t * 0.04).toFloat()
            val x = (if (right) width - Theme.px(120f) else Theme.px(120f)) + shift
            val top = (if (bottom) height - Theme.px(110f + h) else Theme.px(100f)) + shiftY
            val align = if (right) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
            for (p in listOf(time, date, wx, why, name)) { p.textAlign = align; p.alpha = 255 }
            fun fade(p: android.graphics.Paint, base: Float) { p.alpha = (255 * base * a).toInt() }
            fade(time, 1f); fade(date, 0.72f); fade(wx, 0.72f); fade(why, 0.6f); fade(name, 1f)
            var y = top + Theme.px(104f)
            c.drawText(tText, x, y, time)
            y += Theme.px(18f + 40f)
            c.drawText(dText, x, y, date)
            if (weather != null) {
                val (res, tint, line) = weather
                val icon = Theme.px(34f)
                val cy = y + Theme.px(43f)
                val tw = wx.measureText(line)
                val ix = if (right) x - tw - Theme.px(12f) - icon else x
                context.frameIcon(res, tint).apply { alpha = (255 * a).toInt(); setBounds(ix.toInt(), (cy - icon / 2).toInt(), (ix + icon).toInt(), (cy + icon / 2).toInt()); draw(c) }
                c.drawText(line, if (right) x else x + icon + Theme.px(12f), baseline(wx, cy), wx)
                y += Theme.px(44f)
            }
            if (title != null) {
                val maxW = Theme.px(760f)
                val line = title.line.uppercase()
                val shownName = android.text.TextUtils.ellipsize(title.name, name, maxW, android.text.TextUtils.TruncateAt.END).toString()
                val ruleW = minOf(maxW, maxOf(Theme.px(360f), name.measureText(shownName), why.measureText(line)))
                val ry = y + Theme.px(50f)
                rule.alpha = (255 * 0.16f * a).toInt()
                c.drawRect(if (right) x - ruleW else x, ry, if (right) x else x + ruleW, ry + Theme.px(2f), rule)
                c.drawText(line, x, ry + Theme.px(24f + 22f), why)
                c.drawText(shownName, x, ry + Theme.px(24f + 22f + 8f + 42f), name)
            }
        }

        init { setBackgroundColor(scenes[0].base) }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            full.set(0, 0, w, h)
            radius = SMALL_W * 0.61f
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(frame); post(show) }
        override fun onDetachedFromWindow() { removeCallbacks(frame); removeCallbacks(show); small.recycle(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            if (small.isRecycled) return
            val still = motion == 2
            val t = if (still) 0.0 else (android.os.SystemClock.uptimeMillis() - start) / 1000.0
            // The current set and how far it has blended into the next.
            val k = (t / (HOLD + FADE)).toInt()
            val f = ((t - k * (HOLD + FADE) - HOLD) / FADE).coerceIn(0.0, 1.0)
            val ease = (f * f * (3 - 2 * f)).toFloat()
            val a = scenes[k % scenes.size]
            val b = scenes[(k + 1) % scenes.size]
            smallCanvas.drawColor(Theme.mix(a.base, b.base, ease))
            for (i in a.glows.indices) {
                // Each glow follows its own orbit and swells and fades on its own beat.
                val p = a.orbits[i]
                val col = Theme.mix(a.glows[i], b.glows[i], ease)
                val r = radius * (0.85f + 0.3f * Math.sin(t * p[4] + p[5]).toFloat())
                val x = SMALL_W * (0.5 + 0.45 * Math.sin(t * p[0] + p[2]))
                val y = SMALL_H * (0.5 + 0.42 * Math.cos(t * p[1] + p[3]))
                paint.shader = android.graphics.RadialGradient(0f, 0f, r,
                    intArrayOf(Theme.withAlpha(col, 0.75f), Theme.withAlpha(col, 0.4f), Theme.withAlpha(col, 0f)),
                    floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
                smallCanvas.save(); smallCanvas.translate(x.toFloat(), y.toFloat())
                smallCanvas.drawCircle(0f, 0f, r, paint)
                smallCanvas.restore()
            }
            c.drawBitmap(small, null, full, scale)
            // Title art over the glows, cross-fading from the last one.
            val now = android.os.SystemClock.uptimeMillis()
            cur?.let { l ->
                val f = if (still) 1f else ((now - l.at).toFloat() / ART_FADE_MS).coerceIn(0f, 1f)
                if (f < 1f) prev?.let { drawLayer(c, it, ART_ALPHA * (1f - f), now) } else prev = null
                drawLayer(c, l, ART_ALPHA * f, now)
            }
            drawBlock(c, now, t)
        }
    }

    class Face(ctx: Context, accent: Int, private val clockMode: Int, private val motion: Int) : View(ctx) {
        private val time = textPaint(180f, Theme.mix(accent, Theme.TEXT, 0.35f), Theme.BOLD)
        private val date = textPaint(44f, Theme.withAlpha(Theme.TEXT_DIM, 0.8f), Theme.MEDIUM)
        /** Four set spots, a quarter in from each corner, visited in this order. */
        private val spots = listOf(0.36f to 0.36f, 0.64f to 0.36f, 0.64f to 0.64f, 0.36f to 0.64f)
        private var spot = 0
        private val start = android.os.SystemClock.uptimeMillis()
        private val tick = object : Runnable {
            // Redraws every few seconds, so the small shift in place stays smooth enough.
            override fun run() { invalidate(); postDelayed(this, if (motion == 2) 60_000L else 4_000L) }
        }
        private val move = object : Runnable {
            override fun run() { hop(); postDelayed(this, MOVE_MS) }
        }

        init { setBackgroundColor(0xFF050306.toInt()) }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post(tick)
            postDelayed(move, MOVE_MS)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(tick); removeCallbacks(move)
            super.onDetachedFromWindow()
        }

        /** Moves to the next spot, faded through unless motion is off. */
        private fun hop() {
            val apply = { spot = (spot + 1) % spots.size; invalidate() }
            if (motion == 2 || !isAttachedToWindow || width == 0) { apply(); return }
            animate().alpha(0f).setDuration(SPOT_FADE_MS).withEndAction { apply(); animate().alpha(1f).setDuration(SPOT_FADE_MS).start() }.start()
        }

        override fun onDraw(c: Canvas) {
            val now = Date()
            val is24 = when (clockMode) {
                1 -> false
                2 -> true
                else -> android.text.format.DateFormat.is24HourFormat(context)
            }
            val t = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
            val d = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)
            // A few pixels of shift in place between moves, so no spot stays lit.
            val secs = (android.os.SystemClock.uptimeMillis() - start) / 1000.0
            val s = if (motion == 2) 0f else 1f
            val x = width * spots[spot].first + s * Theme.px(8f) * Math.sin(secs * 0.05).toFloat()
            val y = height * spots[spot].second + s * Theme.px(6f) * Math.cos(secs * 0.04).toFloat()
            time.textAlign = android.graphics.Paint.Align.CENTER
            date.textAlign = android.graphics.Paint.Align.CENTER
            c.drawText(t, x, baseline(time, y - Theme.px(30f)), time)
            c.drawText(d, x, baseline(date, y + Theme.px(90f)), date)
        }
    }
}

/**
 * Scene's screensaver drawn over Home, for TVs where Android can't start it. Shows the music
 * screensaver while a song plays. Any key or touch ends it.
 */
class SaverScreen(act: HomeActivity) : OverlayBase(act, 1f) {
    init {
        val cfg = act.cfg
        val view = when {
            NowPlaying.saver(act) && NowPlaying.state == 1 -> MusicSaver(act)
            ClockDream.mode(act) == ClockDream.AMBIENT -> ClockDream.Ambient(act, Theme.accent, cfg.clock, cfg.motion)
            else -> ClockDream.Face(act, Theme.accent, cfg.clock, cfg.motion)
        }
        addView(view, LayoutParams(-1, -1))
        isFocusable = true
        isFocusableInTouchMode = true
        post { requestFocus() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP) (context as HomeActivity).pop()
        return true
    }

    override fun onInterceptTouchEvent(e: android.view.MotionEvent) = true

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) (context as HomeActivity).pop()
        return true
    }
}
