package com.robbydock.scene

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Optional interface sounds, off by default and shared by all profiles. A soft tick on moves
 * and a short tone on OK, made in code so they match the HTML mock.
 */
object Sounds {
    private const val PREFS = "state"
    private const val KEY = "sounds"
    /** Holding the D-pad gives at most one tick in this many milliseconds. */
    private const val GAP_MS = 60L
    private const val RATE = 48000

    var on = false
        private set
    private var last = 0L
    private var tick: AudioTrack? = null
    private var tone: AudioTrack? = null

    fun load(ctx: Context) {
        on = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        if (tick == null) tick = track(tickPcm())
        if (tone == null) tone = track(tonePcm(660.0))
    }

    fun set(ctx: Context, value: Boolean) {
        on = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    fun move() {
        if (!on || FocusView.quiet) return
        val now = SystemClock.uptimeMillis()
        if (now - last < GAP_MS) return
        last = now
        play(tick)
    }

    /** Plays a sound for the Settings demo, even while Sounds is off. */
    fun demo(ok: Boolean) = play(if (ok) tone else tick)

    /** Skipped when Android's touch sounds are on, so OK never sounds twice. */
    fun ok(ctx: Context) {
        if (!on || FocusView.quiet) return
        if (Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) == 1) return
        play(tone)
    }

    private fun play(t: AudioTrack?) {
        t ?: return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: IllegalStateException) {
        }
    }

    private fun track(pcm: ShortArray): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    } catch (_: Exception) {
        null
    }

    /** Triangle wave that falls from 1800 to 900 Hz in 30 ms and fades out in 50 ms. */
    private fun tickPcm(): ShortArray {
        val n = RATE * 60 / 1000
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val f = if (t < 0.03) 1800.0 * 0.5.pow(t / 0.03) else 900.0
            phase = (phase + f / RATE) % 1.0
            val wave = 4 * abs(phase - 0.5) - 1
            val gain = if (t < 0.05) 0.035 * (0.0005 / 0.035).pow(t / 0.05) else 0.0
            (wave * gain * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Sine tone that fades out in 120 ms. */
    private fun tonePcm(freq: Double): ShortArray {
        val n = RATE * 130 / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val gain = if (t < 0.12) 0.08 * (0.001 / 0.08).pow(t / 0.12) else 0.0
            (sin(2 * PI * freq * t) * gain * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
