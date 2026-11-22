package com.robbydock.scene

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class Pin(val pkg: String, val label: String)

/** A row of apps the user made. [sort]: 0 custom, 1 A to Z, 2 most used, 3 last used. */
class AppRow(var name: String, var sort: Int = 0, val apps: ArrayList<String> = ArrayList())

/** Launcher settings, stored as one small JSON file. The Stremio session is stored separately. */
class Config {
    var pinned = ArrayList<Pin>()
    var hidden = LinkedHashSet<String>()
    /** Continue watching entries hidden from Home, as "id|videoId". A new episode shows again. */
    var cwHidden = LinkedHashSet<String>()
    var accent = 0
    var trueDark = false
    var glass = 0.55f
    var clock = 0 // 0 system, 1 twelve hour, 2 twenty four hour
    var clockOn = true
    var showDate = true
    var weatherOn = true
    var weatherDetail = 0 // 0 condition and high/low, 1 high/low, 2 temperature only
    /** Use this TV's location for weather; the saved city is the fallback. */
    var locAuto = true
    var fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")
    var spotlight = true
    /** Motion: 0 full, 1 reduced, 2 off. */
    var motion = 0
    /** Show the Scene splash while Home starts. */
    var splash = true
    /** All apps order: 0 A to Z, 1 recently installed, 2 custom. */
    var appSort = 0
    /** Custom All apps order as package names. Apps not listed follow in A to Z order. */
    var appOrder = ArrayList<String>()
    /** Custom app names by package, set with Rename. */
    var names = LinkedHashMap<String, String>()
    /** Home background: 0 blurred artwork, 1 accent gradient, 2 solid. */
    var background = 0
    /** App rows under the hero. Either, both or neither. */
    var showPinned = true
    var showRecent = false
    /** Apps opened from Scene, newest first, at most [MAX_RECENTS]. */
    var recents = ArrayList<String>()
    /** Apps removed from recents, by package, with the time removed. They return once used again. */
    var recentsRemoved = LinkedHashMap<String, Long>()
    /** The user's own app rows, shown under Pinned apps. */
    var rows = ArrayList<AppRow>()
    /** Locked apps by package, with the hours they lock: an index into [AppLocks.WINDOWS]. */
    var locked = LinkedHashMap<String, Int>()
    /** App opened after the TV starts, or empty for none. */
    var startApp = ""
    /** Opens [startApp] again when the TV wakes from sleep. */
    var startWake = false
    var city = ""
    var lat = Double.NaN
    var lon = Double.NaN

    val hasLocation get() = !lat.isNaN() && !lon.isNaN()

    companion object {
        const val VERSION = 2
        const val MAX_PINS = 20
        const val MAX_RECENTS = 8
        const val MAX_NAME = 24
        const val MAX_NAMES = 200
        const val STREMIO = "com.stremio.one"
        const val STREMIO_BOOT = "com.stremio.tv.boot"
        const val MAX_ROWS = 8
        const val MAX_ROW_APPS = 40
        const val MAX_ROW_NAME = 20
    }

    fun toJson(): JSONObject {
        val pins = JSONArray()
        for (p in pinned) pins.put(JSONObject().put("p", p.pkg).put("l", p.label))
        val o = JSONObject()
            .put("v", VERSION)
            .put("pinned", pins)
            .put("hidden", JSONArray(hidden.toList()))
            .put("cwHidden", JSONArray(cwHidden.toList()))
            .put("accent", accent)
            .put("trueDark", trueDark)
            .put("glass", glass.toDouble())
            .put("clock", clock)
            .put("clockOn", clockOn)
            .put("showDate", showDate)
            .put("weatherOn", weatherOn)
            .put("weatherDetail", weatherDetail)
            .put("locAuto", locAuto)
            .put("fahrenheit", fahrenheit)
            .put("spotlight", spotlight)
            .put("motion", motion)
            .put("splash", splash)
            .put("appSort", appSort)
            .put("appOrder", JSONArray(appOrder))
            .put("showPinned", showPinned)
            .put("showRecent", showRecent)
            .put("background", background)
            .put("names", JSONObject().apply { for ((k, v) in names) put(k, v) })
            .put("recents", JSONArray(recents))
            .put("recentsRemoved", JSONObject().apply { for ((k, v) in recentsRemoved) put(k, v) })
            .put("rows", JSONArray().apply { for (r in rows) put(JSONObject().put("n", r.name).put("s", r.sort).put("a", JSONArray(r.apps))) })
            .put("locked", JSONObject().apply { for ((k, v) in locked) put(k, v) })
            .put("startApp", startApp)
            .put("startWake", startWake)
            .put("city", city)
        if (hasLocation) o.put("lat", lat).put("lon", lon)
        return o
    }

    /**
     * Unknown keys are ignored and missing keys keep defaults. Every list is capped, package names are checked and strings are clamped.
     */
    fun applyJson(o: JSONObject) {
        val pins = ArrayList<Pin>()
        o.optJSONArray("pinned")?.let { a ->
            for (i in 0 until minOf(a.length(), Limits.APP_ORDER)) {
                val p = a.optJSONObject(i) ?: continue
                val pkg = p.optString("p")
                if (Guard.isPackageName(pkg) && pins.size < MAX_PINS && pins.none { it.pkg == pkg }) {
                    val label = Guard.cleanText(p.optString("l", pkg), Limits.PIN_LABEL).ifEmpty { pkg }
                    pins.add(Pin(pkg, label))
                }
            }
        }
        pinned = pins
        hidden = packages(o.optJSONArray("hidden"), Limits.HIDDEN).toCollection(LinkedHashSet())
        val cw = LinkedHashSet<String>()
        o.optJSONArray("cwHidden")?.let { a ->
            for (i in 0 until minOf(a.length(), Limits.CW_HIDDEN)) {
                a.optString(i).takeIf { it.isNotEmpty() && it.length <= Limits.ID_TEXT }?.let { cw.add(it) }
            }
        }
        cwHidden = cw
        accent = o.optInt("accent", 0).coerceIn(0, Theme.ACCENTS.size - 1)
        trueDark = o.optBoolean("trueDark", false)
        glass = o.optDouble("glass", 0.55).toFloat().coerceIn(Theme.GLASS_MIN, 1f)
        clock = o.optInt("clock", 0).coerceIn(0, 2)
        clockOn = o.optBoolean("clockOn", true)
        showDate = o.optBoolean("showDate", true)
        weatherOn = o.optBoolean("weatherOn", true)
        weatherDetail = o.optInt("weatherDetail", 0).coerceIn(0, 2)
        locAuto = o.optBoolean("locAuto", true)
        fahrenheit = o.optBoolean("fahrenheit", fahrenheit)
        spotlight = o.optBoolean("spotlight", true)
        motion = o.optInt("motion", 0).coerceIn(0, 2)
        splash = o.optBoolean("splash", true)
        appSort = o.optInt("appSort", 0).coerceIn(0, 2)
        appOrder = ArrayList(packages(o.optJSONArray("appOrder"), Limits.APP_ORDER))
        // Older files keep one row choice: 0 pinned, 1 recent, 2 none.
        val row = o.optInt("homeRow", 0).coerceIn(0, 2)
        showPinned = o.optBoolean("showPinned", row == 0)
        showRecent = o.optBoolean("showRecent", row == 1)
        background = o.optInt("background", 0).coerceIn(0, 2)
        val n = LinkedHashMap<String, String>()
        o.optJSONObject("names")?.let { m ->
            for (k in m.keys()) {
                if (n.size >= MAX_NAMES) break
                val v = Guard.cleanText(m.optString(k), MAX_NAME)
                if (Guard.isPackageName(k) && v.isNotEmpty()) n[k] = v
            }
        }
        names = n
        recents = ArrayList(packages(o.optJSONArray("recents"), MAX_RECENTS))
        val gone = LinkedHashMap<String, Long>()
        o.optJSONObject("recentsRemoved")?.let { m ->
            for (k in m.keys()) {
                if (gone.size >= Limits.HIDDEN) break
                if (Guard.isPackageName(k)) gone[k] = m.optLong(k, 0L)
            }
        }
        recentsRemoved = gone
        val rs = ArrayList<AppRow>()
        o.optJSONArray("rows")?.let { a ->
            for (i in 0 until minOf(a.length(), MAX_ROWS)) {
                val r = a.optJSONObject(i) ?: continue
                val name = Guard.cleanText(r.optString("n"), MAX_ROW_NAME)
                if (name.isNotEmpty()) rs.add(AppRow(name, r.optInt("s", 0).coerceIn(0, 3), ArrayList(packages(r.optJSONArray("a"), MAX_ROW_APPS))))
            }
        }
        rows = rs
        val lk = LinkedHashMap<String, Int>()
        o.optJSONObject("locked")?.let { m ->
            for (k in m.keys()) {
                if (lk.size >= Limits.HIDDEN) break
                if (Guard.isPackageName(k)) lk[k] = m.optInt(k, 0).coerceIn(0, AppLocks.WINDOWS.size - 1)
            }
        }
        locked = lk
        startApp = o.optString("startApp", "").takeIf { Guard.isPackageName(it) } ?: ""
        startWake = o.optBoolean("startWake", false)
        city = Guard.cleanText(o.optString("city", ""), Limits.CITY)
        val la = o.optDouble("lat", Double.NaN)
        val lo = o.optDouble("lon", Double.NaN)
        // A location is kept only as a valid pair.
        if (Guard.latOk(la) && Guard.lonOk(lo)) { lat = la; lon = lo } else { lat = Double.NaN; lon = Double.NaN }
    }

    /** Distinct valid package names from [a], at most [cap]. */
    private fun packages(a: JSONArray?, cap: Int): List<String> {
        if (a == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until minOf(a.length(), cap)) a.optString(i).takeIf { Guard.isPackageName(it) }?.let { out.add(it) }
        return out.toList()
    }

    fun copy(): Config = Config().also { it.applyJson(toJson()) }
}

object Store {
    /** Settings file of the current profile. */
    var file = "settings.json"
    const val BACKUP = "scene-backup.json"

    /** Why the last backup restore failed, for the Backup pane. */
    var restoreProblem: String? = null
        private set

    /** The current profile's settings, or null. A damaged file is kept aside before defaults replace it. */
    fun load(ctx: Context): Config? {
        val f = File(ctx.filesDir, file)
        return readConfig(f) ?: run {
            if (f.exists()) keepAside(ctx, file, file.removeSuffix(".json") + ".bad.json")
            null
        }
    }

    /** False when the write failed, such as when storage is full. The old file is kept. */
    fun save(ctx: Context, c: Config): Boolean = Demo.on || writeAtomic(File(ctx.filesDir, file), c.toJson().toString())

    fun backupPath(ctx: Context) = "Android/data/${ctx.packageName}/files/$BACKUP"

    private fun backupFile(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, BACKUP) }

    /** True when the backup file exists. */
    fun hasBackup(ctx: Context) = backupFile(ctx)?.exists() == true

    /** Writes the backup file without the Stremio session. */
    fun export(ctx: Context, c: Config): Boolean {
        if (Demo.on) return false
        val f = backupFile(ctx) ?: return false
        return writeAtomic(f, c.toJson().toString(2))
    }

    /**
     * Reads the backup file. Returns null, with [restoreProblem] set, when it is missing, too
     * large, too deeply nested or not valid.
     */
    fun restore(ctx: Context): Config? {
        if (Demo.on) { restoreProblem = "Not available in Demo Mode."; return null }
        val f = backupFile(ctx)
        if (f == null || !f.exists()) { restoreProblem = "No backup file on this TV."; return null }
        val len = f.length()
        val text = if (len > Limits.BACKUP_BYTES) null else readOrNull(f)
        restoreProblem = Guard.backupProblem(len, text)
        if (restoreProblem != null) return null
        return parseConfig(text!!).also { if (it == null) restoreProblem = "The backup file isn't a Scene backup." }
    }

    fun readText(ctx: Context, name: String): String? = File(ctx.filesDir, name).takeIf { it.exists() }?.let { readOrNull(it) }

    fun writeText(ctx: Context, name: String, text: String): Boolean = writeAtomic(File(ctx.filesDir, name), text)

    fun delete(ctx: Context, name: String) {
        File(ctx.filesDir, name).delete()
    }

    /** Renames a damaged file aside. */
    fun keepAside(ctx: Context, name: String, aside: String) {
        val f = File(ctx.filesDir, name)
        if (f.exists()) f.renameTo(File(ctx.filesDir, aside))
    }

    private fun readOrNull(f: File): String? = try { f.readText() } catch (e: Exception) { null }

    private fun readConfig(f: File): Config? {
        if (!f.exists() || f.length() > Limits.BACKUP_BYTES) return null
        val text = readOrNull(f) ?: return null
        return if (Guard.depthOk(text)) parseConfig(text) else null
    }

    private fun parseConfig(text: String): Config? = try {
        Config().also { it.applyJson(JSONObject(text)) }
    } catch (e: Exception) {
        null
    }

    /**
     * Writes a temp file, syncs it to disk, then renames it over [target]. On any failure the old
     * file stays and this returns false.
     */
    fun writeAtomic(target: File, text: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            tmp.renameTo(target) || run { tmp.delete(); false }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
