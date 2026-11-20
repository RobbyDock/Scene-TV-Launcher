package com.robbydock.scene

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/**
 * Wrong-try state for one profile. Times are milliseconds since boot.
 */
class LockState(var fails: Int = 0, var until: Long = 0L, var boot: Int = -1) {

    fun remaining(now: Long) = (until - now).coerceAtLeast(0L)

    /** Counts a wrong try and returns the wait it causes, 0 when none. */
    fun wrong(now: Long): Long {
        fails = (fails + 1).coerceAtMost(LockPolicy.MAX_FAILS)
        val w = LockPolicy.waitAfter(fails)
        if (w > 0) until = now + w
        return w
    }

    fun right() { fails = 0; until = 0L }

    /**
     * Restarts the full wait for a locked-out profile after a reboot.
     */
    fun adopt(bootCount: Int, now: Long) {
        if (bootCount == boot) return
        boot = bootCount
        until = if (fails >= LockPolicy.FREE_TRIES + 1) now + LockPolicy.waitAfter(fails) else 0L
    }
}

object LockPolicy {
    /** Wrong tries allowed before the first wait. */
    const val FREE_TRIES = 4
    const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 15 * 60_000L
    /** Caps the counter. */
    const val MAX_FAILS = 64

    /** No wait for the first 4 wrong tries, then 30 s doubling with each miss, up to 15 min. */
    fun waitAfter(fails: Int): Long {
        if (fails <= FREE_TRIES) return 0L
        val doublings = (fails - FREE_TRIES - 1).coerceAtMost(10)
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
    }
}

/** Per-profile lockouts stored in locks.json. Not part of a backup. */
object Locks {
    private const val FILE = "locks.json"
    private val states = HashMap<String, LockState>()
    private var loaded = false

    private fun now() = SystemClock.elapsedRealtime()

    private fun bootCount(ctx: Context) = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        -1
    }

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val text = Store.readText(ctx, FILE) ?: return
        try {
            val o = JSONObject(text)
            for (id in o.keys()) {
                if (states.size >= Limits.PROFILES * 2) break
                val s = o.optJSONObject(id) ?: continue
                states[id] = LockState(
                    s.optInt("fails").coerceIn(0, LockPolicy.MAX_FAILS),
                    s.optLong("until").coerceAtLeast(0L),
                    s.optInt("boot", -1),
                )
            }
        } catch (e: Exception) {
            // A damaged file starts clean.
            states.clear()
        }
    }

    private fun save(ctx: Context) {
        val o = JSONObject()
        for ((id, s) in states) {
            if (s.fails == 0 && s.until == 0L) continue
            o.put(id, JSONObject().put("fails", s.fails).put("until", s.until).put("boot", s.boot))
        }
        Store.writeText(ctx, FILE, o.toString())
    }

    private fun state(ctx: Context, id: String): LockState {
        load(ctx)
        return states.getOrPut(id) { LockState() }.also { it.adopt(bootCount(ctx), now()) }
    }

    /** Milliseconds left before [id] may try again. */
    fun remaining(ctx: Context, id: String) = state(ctx, id).remaining(now())

    /** The full length of the current wait. */
    fun currentWait(ctx: Context, id: String) = LockPolicy.waitAfter(state(ctx, id).fails)

    /** Records a wrong try and returns the wait it starts, 0 when none. */
    fun wrong(ctx: Context, id: String): Long = state(ctx, id).wrong(now()).also { save(ctx) }

    fun right(ctx: Context, id: String) {
        state(ctx, id).right()
        save(ctx)
    }

    fun forget(ctx: Context, id: String) {
        load(ctx)
        states.remove(id)
        save(ctx)
    }

    /** Clears every lockout. */
    fun clearAll(ctx: Context) {
        load(ctx)
        states.clear()
        Store.delete(ctx, FILE)
    }
}
