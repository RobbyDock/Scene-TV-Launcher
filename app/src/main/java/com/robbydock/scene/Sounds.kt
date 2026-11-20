package com.robbydock.scene

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Optional interface sounds, off by default and shared by all profiles. Plays the system key
 * click on moves and the return sound on OK.
 */
object Sounds {
    private const val PREFS = "state"
    private const val KEY = "sounds"
    /** Holding the D-pad gives at most one click in this many milliseconds. */
    private const val GAP_MS = 60L
    /** Effect volumes. */
    private const val MOVE_VOL = 0.3f
    /** -1 plays OK at the system's own effect level. */
    private const val OK_VOL = -1f

    var on = false
        private set
    private var last = 0L
    private var audio: AudioManager? = null

    fun load(ctx: Context) {
        on = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        audio = ctx.getSystemService(AudioManager::class.java)
    }

    fun set(ctx: Context, value: Boolean) {
        on = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    fun move() = play(AudioManager.FX_KEY_CLICK, MOVE_VOL)

    /** Plays a sound for the Settings demo, even while Sounds is off. */
    fun demo(ok: Boolean) {
        audio?.playSoundEffect(if (ok) AudioManager.FX_KEYPRESS_RETURN else AudioManager.FX_KEY_CLICK, if (ok) OK_VOL else MOVE_VOL)
    }

    /** Skipped when Android's touch sounds are on. */
    fun ok(ctx: Context) {
        if (Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) == 1) return
        play(AudioManager.FX_KEYPRESS_RETURN, OK_VOL)
    }

    private fun play(fx: Int, volume: Float) {
        if (!on || FocusView.quiet) return
        val now = SystemClock.uptimeMillis()
        if (now - last < GAP_MS) return
        last = now
        audio?.playSoundEffect(fx, volume)
    }
}
