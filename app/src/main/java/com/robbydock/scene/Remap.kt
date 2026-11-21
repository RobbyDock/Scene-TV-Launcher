package com.robbydock.scene

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Remote button remapping. A button can open a chosen app or a part of Scene on a single,
 * double or triple press. Mappings belong to the TV, not to a profile. Buttons with no
 * mapping keep their usual action.
 */
object Remap {
    private const val PREFS = "remote"
    private const val LEARNED = "learned"
    private const val HOME_KEY = "homeOpensScene"

    /** Intent action Scene receives when a button opens one of its own parts. */
    const val ACTION = "com.robbydock.scene.REMOTE_ACTION"
    const val EXTRA = "target"

    /** Targets inside Scene. Any other target is an app package name. */
    const val QUICK = "scene:quick"
    const val SEARCH = "scene:search"
    const val VOICE = "scene:voice"
    const val HOME = "scene:home"
    val SCENE_TARGETS = listOf(HOME, QUICK, SEARCH, VOICE)

    /** Time allowed between presses of a double or triple press. */
    const val PRESS_GAP_MS = 350L

    class Button(val key: Int, val name: String)

    /** Buttons this remote is known to have, with the name printed on them. */
    fun knownButtons(): List<Button> =
        if (Build.MODEL.contains("onn.", true)) listOf(
            Button(KeyEvent.KEYCODE_BUTTON_3, "YouTube"),
            Button(KeyEvent.KEYCODE_BUTTON_4, "Netflix"),
            Button(KeyEvent.KEYCODE_BUTTON_9, "Disney+"),
            Button(KeyEvent.KEYCODE_BUTTON_10, "Paramount+"),
        ) else emptyList()

    /** Known buttons first, then any the user taught Scene. */
    fun buttons(ctx: Context): List<Button> {
        val known = knownButtons()
        val learned = learnedKeys(ctx).filter { k -> known.none { it.key == k } }.map { Button(it, keyName(it)) }
        return known + learned
    }

    fun keyName(key: Int): String = KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_")
        .split('_').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun slot(key: Int, presses: Int) = if (presses == 1) "k$key" else "k$key.$presses"

    /** What a button does after [presses] presses, or null to keep its usual action. */
    fun target(ctx: Context, key: Int, presses: Int = 1): String? = prefs(ctx).getString(slot(key, presses), null)

    fun setTarget(ctx: Context, key: Int, target: String?, presses: Int = 1) {
        prefs(ctx).edit().apply { if (target == null) remove(slot(key, presses)) else putString(slot(key, presses), target) }.apply()
    }

    /** True when the button has a double or triple press set. */
    fun hasMulti(ctx: Context, key: Int) = target(ctx, key, 2) != null || target(ctx, key, 3) != null

    fun isMapped(ctx: Context, key: Int) = target(ctx, key) != null || hasMulti(ctx, key)

    private fun learnedKeys(ctx: Context): List<Int> =
        prefs(ctx).getString(LEARNED, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    fun addLearned(ctx: Context, key: Int) {
        val keys = learnedKeys(ctx)
        if (key !in keys) prefs(ctx).edit().putString(LEARNED, (keys + key).joinToString(",")).apply()
    }

    fun forget(ctx: Context, key: Int) {
        prefs(ctx).edit().putString(LEARNED, learnedKeys(ctx).filter { it != key }.joinToString(","))
            .remove(slot(key, 1)).remove(slot(key, 2)).remove(slot(key, 3)).apply()
    }

    /** When on, the remote's Home button opens Scene instead of the TV's own home screen. */
    fun homeOpensScene(ctx: Context) = prefs(ctx).getBoolean(HOME_KEY, false)

    fun setHomeOpensScene(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(HOME_KEY, on).apply()

    /** Name shown for a target. */
    fun label(target: String, apps: List<AppEntry>): String = when (target) {
        HOME -> "Scene Home"
        QUICK -> "Quick panel"
        SEARCH -> "Search"
        VOICE -> "Voice search"
        else -> apps.firstOrNull { it.pkg == target }?.label ?: target
    }

    fun icon(target: String): Int = when (target) {
        HOME -> R.drawable.ic_home
        QUICK -> R.drawable.ic_dashboard_customize
        SEARCH -> R.drawable.ic_search
        else -> R.drawable.ic_mic
    }

    /** Intent that opens [target], or null when its app is gone. */
    fun intentFor(ctx: Context, target: String): Intent? =
        if (target.startsWith("scene:")) Intent(ctx, HomeActivity::class.java).setAction(ACTION).putExtra(EXTRA, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else Apps.launchIntent(ctx.packageManager, target)

    /** True when the user has turned on Scene's remote buttons service in Accessibility settings. */
    fun isEnabled(ctx: Context): Boolean {
        val own = ComponentName(ctx, RemapService::class.java)
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it) == own }
    }

    /** Set while the user teaches Scene a new button; receives the next unmapped key press. */
    @Volatile var learning: ((Int) -> Unit)? = null

    /** Keys that must keep working for navigation, so they can never be learned or remapped. */
    fun isReserved(key: Int) = key in setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_UNKNOWN,
    )
}

/**
 * Reads remote key presses before apps do. It only acts on buttons the user mapped, on Home
 * when the user chose that, or on the next press while teaching a button. Everything else
 * passes through untouched.
 */
class RemapService : AccessibilityService() {
    /** Key whose down press was consumed, so its up press is consumed too. */
    private var held = -1
    /** Button being counted for a double or triple press, and its presses so far. */
    private var countKey = -1
    private var presses = 0
    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable { finishCount() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_HOME) return onHome(event)
        if (Remap.isReserved(key)) return false
        // Game controllers send the same BUTTON_ codes as some remotes' app buttons; leave them to games.
        if (event.device?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true) return false
        if (event.action == KeyEvent.ACTION_UP && key == held) {
            held = -1
            if (key == countKey && presses > 0) handler.postDelayed(fire, Remap.PRESS_GAP_MS)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        Remap.learning?.let { done ->
            Remap.learning = null
            held = key
            done(key)
            return true
        }
        if (!Remap.isMapped(this, key)) return false
        if (!Remap.hasMulti(this, key)) {
            // A missing app falls back to the button's usual action.
            val intent = Remap.target(this, key)?.let { Remap.intentFor(this, it) } ?: return false
            held = key
            if (event.repeatCount == 0) try { startActivity(intent) } catch (_: Exception) { }
            return true
        }
        held = key
        if (event.repeatCount > 0) return true
        handler.removeCallbacks(fire)
        if (countKey != key) finishCount()
        countKey = key
        presses++
        // No need to wait when no later press is set.
        if (presses >= 3 || (presses == 2 && Remap.target(this, key, 3) == null)) finishCount()
        return true
    }

    /** Runs the action for the presses counted so far. */
    private fun finishCount() {
        handler.removeCallbacks(fire)
        val key = countKey
        val n = presses
        countKey = -1
        presses = 0
        if (key == -1 || n == 0) return
        Remap.target(this, key, n)?.let { open(it) }
    }

    private fun onHome(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (held != KeyEvent.KEYCODE_HOME) return false
            held = -1
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || !Remap.homeOpensScene(this)) return false
        if (event.repeatCount == 0) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(home) } catch (_: Exception) { return false }
            held = KeyEvent.KEYCODE_HOME
        }
        return held == KeyEvent.KEYCODE_HOME
    }

    private fun open(target: String) {
        val intent = Remap.intentFor(this, target) ?: return
        try { startActivity(intent) } catch (_: Exception) { }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
