package com.robbydock.scene

import android.os.SystemClock
import android.provider.Settings
import java.util.Calendar

/**
 * App locks. A locked app asks for a passcode during its hours, then stays open for 15 minutes.
 * The passcode is the first admin's, or any locked profile's when no admin has one.
 */
object AppLocks {
    class Window(val name: String, val sub: String, val icon: Int, val on: (Int) -> Boolean)

    val WINDOWS = listOf(
        Window("Always", "Every time it opens", R.drawable.ic_lock) { true },
        Window("Evenings, 8 pm to 7 am", "Opens freely outside these hours", R.drawable.ic_schedule) { it >= 20 || it < 7 },
        Window("School hours, 8 am to 3 pm", "Opens freely outside these hours", R.drawable.ic_schedule) { it in 8..14 },
    )
    private const val GRACE_MS = 15 * 60_000L
    private val opened = HashMap<String, Long>()

    fun owner(): Profile? = Profiles.list.firstOrNull { it.admin && it.locked } ?: Profiles.list.firstOrNull { it.locked }

    /** True when [pkg] needs the passcode now. */
    fun needsCode(cfg: Config, pkg: String): Boolean {
        val w = cfg.locked[pkg] ?: return false
        if (owner() == null) return false
        if (!WINDOWS[w].on(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) return false
        val at = opened[pkg] ?: return true
        return SystemClock.elapsedRealtime() - at >= GRACE_MS
    }

    fun unlocked(pkg: String) { opened[pkg] = SystemClock.elapsedRealtime() }
}

/** "1 app" or "n apps". */
fun appCount(n: Int) = if (n == 1) "1 app" else "$n apps"

/** Sort names for app rows. */
object RowSort {
    val NAMES = arrayOf("Custom", "A to Z", "Most used", "Last used")
    val SUBS = arrayOf("Your own order", "By app name", "Most opened first", "Last opened first")
    val ICONS = intArrayOf(R.drawable.ic_drag_indicator, R.drawable.ic_sort_by_alpha, R.drawable.ic_favorite, R.drawable.ic_history)
}

/**
 * Installed, visible apps of [r] in its order. Most used and last used fall back to the apps last
 * opened from Scene, then to the name, so the order holds without usage access and between ties.
 */
fun HomeActivity.rowApps(r: AppRow): List<String> {
    val source = if (r.auto.isEmpty()) r.apps
        else apps.map { it.pkg }.filter { it !in r.apps && AppCategory.of(this, it) == r.auto }
    val list = source.filter { it !in cfg.hidden && (apps.isEmpty() || app(it) != null) }
    val byName = compareBy<String> { (app(it)?.label ?: it).lowercase() }
    val rank = cfg.recents.withIndex().associate { it.value to it.index }
    val byRecent = compareBy<String> { rank[it] ?: Int.MAX_VALUE }
    return when (r.sort) {
        1 -> list.sortedWith(byName)
        2 -> { val use = Apps.usageTotals(this); list.sortedWith(compareByDescending<String> { use[it] ?: 0L }.then(byRecent).then(byName)) }
        3 -> { val used = Apps.recentlyUsed(this).toMap(); list.sortedWith(compareByDescending<String> { used[it] ?: 0L }.then(byRecent).then(byName)) }
        else -> if (r.auto.isEmpty()) list else list.sortedWith(byName)
    }
}

/** Ready-made rows, sorted into by Android's app category, the games flag and well-known apps. */
object AppCategory {
    class Kind(val key: String, val name: String, val icon: Int, val sub: String)
    val KINDS = listOf(
        Kind("watch", "Watch", R.drawable.ic_movie, "Streaming, video and live TV"),
        Kind("listen", "Listen", R.drawable.ic_music_note, "Music, radio and podcasts"),
        Kind("games", "Games", R.drawable.ic_sports_esports, "Games and game streaming"),
        Kind("tools", "Tools", R.drawable.ic_settings, "Settings, files and utilities"),
    )
    val KEYS = KINDS.map { it.key }.toSet()

    /** Apps that often leave their category unset. */
    private val KNOWN = mapOf(
        "com.netflix.ninja" to "watch", "com.netflix.mediaclient" to "watch", "com.google.android.youtube.tv" to "watch",
        "com.google.android.youtube" to "watch", "com.disney.disneyplus" to "watch", "com.amazon.amazonvideo.livingroom" to "watch",
        "com.amazon.avod.thirdpartyclient" to "watch", "com.hbo.hbonow" to "watch", "com.wbd.stream" to "watch",
        "com.hulu.livingroomplus" to "watch", "com.apple.atve.androidtv.appletv" to "watch", "com.plexapp.android" to "watch",
        "org.jellyfin.androidtv" to "watch", "org.xbmc.kodi" to "watch", "com.stremio.one" to "watch", "tv.twitch.android.app" to "watch",
        "tv.pluto.android" to "watch", "com.espn.score_center" to "watch", "com.nuvio.tv" to "watch", "com.crunchyroll.crunchyroid" to "watch",
        "com.spotify.tv.android" to "listen", "com.spotify.music" to "listen", "com.google.android.youtube.tvmusic" to "listen",
        "com.pandora.android.atv" to "listen", "com.soundcloud.android" to "listen", "com.amazon.music.tv" to "listen",
        "com.valvesoftware.steamlink" to "games", "com.nvidia.geforcenow" to "games", "com.microsoft.xcloud" to "games",
        "com.android.vending" to "tools", "com.android.tv.settings" to "tools",
    )
    private val cache = HashMap<String, String>()

    /** The row an app belongs in, or "" for none. */
    fun of(ctx: android.content.Context, pkg: String): String = cache.getOrPut(pkg) {
        KNOWN[pkg] ?: try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            val cat = info.category
            @Suppress("DEPRECATION")
            val game = info.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0
            val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            when {
                cat == android.content.pm.ApplicationInfo.CATEGORY_GAME || game -> "games"
                cat == android.content.pm.ApplicationInfo.CATEGORY_VIDEO || cat == android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "watch"
                cat == android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "listen"
                cat == android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY || cat == android.content.pm.ApplicationInfo.CATEGORY_MAPS || system -> "tools"
                else -> ""
            }
        } catch (e: Exception) { "" }
    }

    /** Forgets the sorting after apps are installed or updated. */
    fun clear() = cache.clear()
}

/** Opens the start app after the TV starts, once per boot. */
fun HomeActivity.openOnBoot() {
    val pkg = cfg.startApp.ifEmpty { return }
    val boot = try { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) } catch (e: Exception) { -1 }
    val prefs = statePrefs()
    if (boot < 0 || prefs.getInt("startBoot", -1) == boot) return
    prefs.edit().putInt("startBoot", boot).apply()
    // Only soon after a start, not when Scene is first installed or restarted later.
    if (SystemClock.elapsedRealtime() > 10 * 60_000L) return
    if (Apps.isInstalled(packageManager, pkg)) launch(pkg)
}

// ---------------------------------------------------------------- sheets

/** Adds [pkg] to a row, or to a new one. */
fun HomeActivity.rowPicker(pkg: String, label: String) {
    val a = cfg.rows.filter { it.auto.isEmpty() }.map { r ->
        val has = pkg in r.apps
        Sheet.Action(r.name, R.drawable.ic_view_agenda, if (has) "Already in this row" else appCount(rowApps(r).size)) {
            if (!has) {
                if (r.apps.size >= Config.MAX_ROW_APPS) { toast("${r.name} is full"); return@Action }
                r.apps.add(pkg); save(); home.rebuild(); toast("Added to ${r.name}")
            }
        }
    }.toMutableList()
    if (cfg.rows.size < Config.MAX_ROWS) a.add(Sheet.Action("New Row", R.drawable.ic_add, "Name it, then add more apps from their options") {
        newRow(pkg)
    })
    push(Sheet(this, "Add $label to a row", "Rows sit under Pinned apps", app(pkg)?.icon, a))
}

/** Asks for a name and makes a row, holding [pkg] when given. */
fun HomeActivity.newRow(pkg: String? = null, after: () -> Unit = {}) {
    push(NameEntry(this, "New row", "", help = "Type a name, then press Done. Up to ${Config.MAX_ROW_NAME} characters.",
        hint = "Row name", icon = R.drawable.ic_view_agenda, maxLen = Config.MAX_ROW_NAME) { v ->
        val name = Guard.cleanText(v, Config.MAX_ROW_NAME)
        if (name.isEmpty()) return@NameEntry
        cfg.rows.add(AppRow(name, 0, ArrayList(listOfNotNull(pkg))))
        save(); home.rebuild(); toast("Made $name"); after()
    })
}

/** Options for row [ri]: rename, sort, move and delete. */
fun HomeActivity.rowMenu(ri: Int, after: () -> Unit = {}) {
    val r = cfg.rows.getOrNull(ri) ?: return
    val done = { save(); home.rebuild(); after() }
    val a = listOf(
        Sheet.Action("Rename", R.drawable.ic_edit) {
            push(NameEntry(this, "Rename ${r.name}", r.name, hint = "Row name", icon = R.drawable.ic_view_agenda, maxLen = Config.MAX_ROW_NAME) { v ->
                Guard.cleanText(v, Config.MAX_ROW_NAME).takeIf { it.isNotEmpty() }?.let { r.name = it; done() }
            })
        },
        Sheet.Action("Sort", R.drawable.ic_sort, RowSort.NAMES[r.sort]) {
            push(Sheet(this, "Sort ${r.name}", "Only this row", null, RowSort.NAMES.indices.map { i ->
                Sheet.Action(RowSort.NAMES[i], RowSort.ICONS[i], RowSort.SUBS[i], checked = r.sort == i) { r.sort = i; done() }
            }))
        },
        Sheet.Action("Move Up", R.drawable.ic_expand_less, if (ri > 0) "Above ${cfg.rows[ri - 1].name}" else "Already first") {
            if (ri > 0) { cfg.rows.add(ri - 1, cfg.rows.removeAt(ri)); done() }
        },
        Sheet.Action("Move Down", R.drawable.ic_expand_more, if (ri < cfg.rows.size - 1) "Below ${cfg.rows[ri + 1].name}" else "Already last") {
            if (ri < cfg.rows.size - 1) { cfg.rows.add(ri + 1, cfg.rows.removeAt(ri)); done() }
        },
        Sheet.Action("Delete Row", R.drawable.ic_delete, "The apps stay in All apps") {
            push(Dialog(this, R.drawable.ic_delete, "Delete ${r.name}?", "Only the row goes. The apps stay in All apps.", listOf(
                Dialog.Button("Cancel", focus = true),
                Dialog.Button("Delete", primary = true) { cfg.rows.remove(r); done() },
            )))
        },
    )
    push(Sheet(this, r.name, "${appCount(rowApps(r).size)}  ·  ${RowSort.NAMES[r.sort]}", null, a))
}

/** Chooses when [pkg] locks. */
fun HomeActivity.lockPicker(pkg: String, label: String, after: () -> Unit = {}) {
    val owner = AppLocks.owner()
    if (owner == null) {
        val me = Profiles.current
        // Only an admin may set the passcode that guards locked apps, and only their own.
        if (!me.admin) {
            // Names no one: the person reading is often the admin on another profile.
            push(Dialog(this, R.drawable.ic_lock, "Locked apps need a passcode", "Set one on an admin profile in Settings, Profiles.",
                listOf(Dialog.Button("OK", primary = true, focus = true))))
            return
        }
        push(Dialog(this, R.drawable.ic_lock, "Set a passcode first", "Locked apps ask for your passcode before they open.", listOf(
            Dialog.Button("Not now"),
            Dialog.Button("Set Passcode", primary = true, focus = true) {
                push(PasscodeScreen(this, me, PasscodeScreen.Mode.SET) { code ->
                    pop(); Profiles.setCode(this, me, code); home.rebuild(); toast("Passcode set")
                    val next = { lockPicker(pkg, label, after) }
                    if (!me.hasRecovery) createRecovery(me) { next() } else next()
                })
            },
        )))
        return
    }
    val a = AppLocks.WINDOWS.mapIndexed { i, w ->
        Sheet.Action(w.name, w.icon, w.sub, checked = cfg.locked[pkg] == i) {
            cfg.locked[pkg] = i; save(); home.rebuild(); toast("$label locked"); after()
        }
    }
    push(Sheet(this, "Lock $label", "Asks for ${owner.name}'s passcode", app(pkg)?.icon, a))
}

/** App menu entries for rows, locks and Open on start. [row] is the row the tile is in, or -1. */
fun HomeActivity.appControlActions(pkg: String, label: String, row: Int): List<Sheet.Action> {
    val out = ArrayList<Sheet.Action>()
    out.add(Sheet.Action("Add to Row", R.drawable.ic_view_agenda,
        cfg.rows.filter { it.auto.isEmpty() }.joinToString(", ") { it.name }.ifEmpty { "Make your own row" }) { rowPicker(pkg, label) })
    cfg.rows.getOrNull(row)?.let { r ->
        out.add(Sheet.Action("Remove from ${r.name}", R.drawable.ic_close, "It stays in All apps") {
            // A ready-made row keeps a list of the apps taken out of it.
            if (r.auto.isEmpty()) r.apps.remove(pkg) else if (pkg !in r.apps) r.apps.add(pkg)
            save(); home.rebuild()
        })
        out.add(Sheet.Action("Row Options", R.drawable.ic_view_agenda, "${r.name}  ·  ${RowSort.NAMES[r.sort]}") { rowMenu(row) })
    }
    val w = cfg.locked[pkg]
    out.add(if (w != null) Sheet.Action("Unlock", R.drawable.ic_lock, AppLocks.WINDOWS[w].name) {
        cfg.locked.remove(pkg); save(); home.rebuild(); toast("$label unlocked")
    } else Sheet.Action("Lock", R.drawable.ic_lock, "Ask for a passcode before it opens") { lockPicker(pkg, label) })
    val starts = cfg.startApp == pkg
    out.add(Sheet.Action("Open When TV Starts", R.drawable.ic_power_settings_new,
        if (starts) "On. Choose again to turn off." else "Opens after the TV starts. Scene stays Home.", checked = starts) {
        cfg.startApp = if (starts) "" else pkg; save()
        toast(if (starts) "Nothing opens when the TV starts" else "$label opens when the TV starts")
    })
    return out
}
