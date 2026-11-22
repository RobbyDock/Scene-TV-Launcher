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
import kotlin.random.Random

/**
 * Screensaver with slow ambient colour and a small clock, or a large clock on near-black. The
 * clock moves to a new spot every [MOVE_MS]. Any key ends it.
 */
class ClockDream : DreamService() {

    companion object {
        private const val MOVE_MS = 50_000L

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

        /** True when Android lets Scene choose the system screensaver. Granted once through adb. */
        fun canSelect(ctx: Context) =
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /** True when Android will start Scene's screensaver. */
        fun isSelected(ctx: Context): Boolean {
            val r = ctx.contentResolver
            val list = android.provider.Settings.Secure.getString(r, COMPONENTS).orEmpty()
            val enabled = android.provider.Settings.Secure.getInt(r, ENABLED, 1) == 1
            return enabled && list.split(',').firstOrNull() == component(ctx).flattenToString()
        }

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
     * Soft fields of colour that drift on slow, unrelated orbits, with a small clock that moves
     * every [MOVE_MS]. Drawn live.
     */
    private class Ambient(ctx: Context, accent: Int, private val clockMode: Int, private val motion: Int) : View(ctx) {
        private val scene = Scenery.pick(accent)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val scale = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG)
        private var shaders: List<android.graphics.RadialGradient> = emptyList()
        private var radius = 0f
        // The colour is drawn small and scaled up.
        private val small = android.graphics.Bitmap.createBitmap(SMALL_W, SMALL_H, android.graphics.Bitmap.Config.ARGB_8888)
        private val smallCanvas = Canvas(small)
        private val full = android.graphics.Rect()
        private val time = textPaint(64f, Theme.withAlpha(Theme.TEXT, 0.85f), Theme.SEMIBOLD)
        private val date = textPaint(26f, Theme.withAlpha(Theme.TEXT, 0.6f), Theme.MEDIUM)
        private var clockX = 0.1f
        private var clockY = 0.85f
        private val start = android.os.SystemClock.uptimeMillis()
        private val frame = object : Runnable {
            // Redraws about 30 times a second, or once a minute when motion is off.
            override fun run() { invalidate(); postDelayed(this, if (motion == 2) 60_000L else 33L) }
        }
        private val move = object : Runnable {
            override fun run() {
                clockX = 0.08f + Random.nextFloat() * 0.6f
                clockY = if (Random.nextBoolean()) 0.16f else 0.86f
                postDelayed(this, MOVE_MS)
            }
        }

        private companion object { const val SMALL_W = 320; const val SMALL_H = 180 }

        init { setBackgroundColor(scene.base) }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            full.set(0, 0, w, h)
            radius = SMALL_W * 0.55f
            shaders = scene.glows.map { c ->
                android.graphics.RadialGradient(0f, 0f, radius, intArrayOf(c, Theme.withAlpha(c, 0.35f), Theme.withAlpha(c, 0f)),
                    floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(frame); post(move) }
        override fun onDetachedFromWindow() { removeCallbacks(frame); removeCallbacks(move); small.recycle(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            if (small.isRecycled) return
            val t = (android.os.SystemClock.uptimeMillis() - start) / 1000.0
            smallCanvas.drawColor(scene.base)
            for ((i, s) in shaders.withIndex()) {
                // Each glow follows its own slow orbit.
                val p = scene.orbits[i]
                val x = SMALL_W * (0.5 + 0.42 * Math.sin(t * p[0] + p[2]))
                val y = SMALL_H * (0.5 + 0.40 * Math.cos(t * p[1] + p[3]))
                paint.shader = s
                smallCanvas.save(); smallCanvas.translate(x.toFloat(), y.toFloat())
                smallCanvas.drawCircle(0f, 0f, radius, paint)
                smallCanvas.restore()
            }
            c.drawBitmap(small, null, full, scale)
            val now = Date()
            val is24 = when (clockMode) { 1 -> false; 2 -> true; else -> android.text.format.DateFormat.is24HourFormat(context) }
            val x = width * clockX
            val y = height * clockY
            c.drawText(SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now), x, y, time)
            c.drawText(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), x, y + Theme.px(44f), date)
        }
    }

    private class Face(ctx: Context, accent: Int, private val clockMode: Int, private val motion: Int) : View(ctx) {
        private val time = textPaint(180f, Theme.mix(accent, Theme.TEXT, 0.35f), Theme.BOLD)
        private val date = textPaint(44f, Theme.withAlpha(Theme.TEXT_DIM, 0.8f), Theme.MEDIUM)
        private var cx = 0.5f
        private var cy = 0.5f
        private val tick = object : Runnable {
            override fun run() { invalidate(); postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50) }
        }
        private val move = object : Runnable {
            override fun run() { hop(); postDelayed(this, MOVE_MS) }
        }

        init { setBackgroundColor(0xFF050306.toInt()) }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            hop()
            post(tick)
            postDelayed(move, MOVE_MS)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(tick); removeCallbacks(move)
            super.onDetachedFromWindow()
        }

        /** A new random spot inside the safe area, faded through unless motion is off. */
        private fun hop() {
            val apply = { cx = 0.3f + Random.nextFloat() * 0.4f; cy = 0.3f + Random.nextFloat() * 0.4f; invalidate() }
            if (motion == 2 || !isAttachedToWindow || width == 0) { apply(); return }
            animate().alpha(0f).setDuration(900).withEndAction { apply(); animate().alpha(1f).setDuration(900).start() }.start()
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
            val x = width * cx
            val y = height * cy
            time.textAlign = android.graphics.Paint.Align.CENTER
            date.textAlign = android.graphics.Paint.Align.CENTER
            c.drawText(t, x, baseline(time, y - Theme.px(30f)), time)
            c.drawText(d, x, baseline(date, y + Theme.px(90f)), date)
        }
    }
}
