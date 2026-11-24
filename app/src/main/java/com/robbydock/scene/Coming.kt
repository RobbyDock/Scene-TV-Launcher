package com.robbydock.scene

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Coming up: the next episode of each followed series, at the time it airs where the TV is.
 * TVmaze gives exact air times and needs no account or key. When TVmaze has no match, Cinemeta's
 * date stands in, marked as not confirmed. The same air times decide when an episode counts as new.
 */
object Coming {
    /** One episode and when it airs. [exact] is false for a Cinemeta date. */
    class Air(val season: Int, val episode: Int, val title: String, val at: Long, val exact: Boolean, val net: String)

    /** A followed series and its next episode. */
    class Upcoming(val id: String, val name: String, val poster: String?, val air: Air)

    /** A series to look up. */
    class Show(val id: String, val name: String, val poster: String?)

    private class Airing(val at: Map<String, Long>, val next: List<Air>)

    val AHEAD_NAMES = listOf("Next 2 weeks", "Next 30 days", "Next 3 months", "Everything announced")
    val AHEAD_SUBS = listOf("Episodes in the next 14 days", "Episodes in the next 30 days", "Episodes in the next 3 months", "Every date that's announced")
    /** Days ahead per choice. 0 means every announced date. */
    private val AHEAD_DAYS = longArrayOf(14, 30, 92, 0)

    private const val TVMAZE = "https://api.tvmaze.com/"
    /** Anime in Stremio uses Kitsu ids. The Kitsu addon's details carry the IMDb id and each episode's air time. */
    private const val KITSU = "kitsu:"
    private const val KITSU_ADDON = "https://anime-kitsu.strem.fun/"
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val FRESH = 12 * 60 * 60 * 1000L
    /** A series TVmaze doesn't know is asked about again after this long. */
    private const val MISS_FRESH = 3 * DAY

    /** The row's list once loaded; null while not known. */
    @Volatile var ready: List<Upcoming>? = null
        private set
    private var loading = false
    private var generation = 0
    private val cache = HashMap<String, Airing>()

    /** Series this profile follows: those watched in the last year, or Continue watching in Demo Mode. */
    fun following(act: HomeActivity): List<Show> {
        val list = when {
            Demo.on -> act.continueItems.filter { it.type == "series" }.map { Show(it.id, it.name, it.poster) }
            Watch.isStremio(act) -> Stremio.watching.map { Show(it.id, it.name, it.poster) }
            else -> emptyList()
        }
        return list.filter { it.id.startsWith("tt") || it.id.startsWith(KITSU) }.distinctBy { it.id }
    }

    /** True when Home shows the row: the spotlight and the row are on, and there are series to follow. */
    fun shown(act: HomeActivity) = act.spotlightOn && act.cfg.comingOn && following(act).isNotEmpty()

    /** Loads the row's list once, then calls [done] on the main thread. */
    fun load(act: HomeActivity, done: () -> Unit) {
        if (loading || ready != null) return
        loading = true
        val gen = generation
        val shows = following(act).filter { it.id !in act.cfg.comingHidden }
        val ahead = act.cfg.comingAhead
        val ctx = act.applicationContext
        Net.run({ upcoming(ctx, shows, ahead) }) { list ->
            if (gen != generation) return@run
            loading = false
            ready = list ?: emptyList()
            done()
        }
    }

    /** Drops the list after the window, the hidden shows or the followed series change. */
    fun reset() {
        generation++
        loading = false
        ready = null
    }

    private val lookups = Executors.newFixedThreadPool(4) { r -> Thread(r, "scene-coming").apply { isDaemon = true } }

    /** The next episode of each series inside the window, soonest first. */
    fun upcoming(ctx: Context, shows: List<Show>, ahead: Int): List<Upcoming> {
        val days = AHEAD_DAYS.getOrElse(ahead) { 30 }
        val until = if (days == 0L) Long.MAX_VALUE else System.currentTimeMillis() + days * DAY
        val jobs = shows.take(60).map { s -> lookups.submit(Callable { airing(ctx, s.id).next.firstOrNull()?.let { Upcoming(s.id, s.name, s.poster, it) } }) }
        // A show saved under both its IMDb and Kitsu ids shows once.
        return jobs.mapNotNull { try { it.get() } catch (e: Exception) { null } }.filter { it.air.at <= until }.sortedBy { it.air.at }
            .distinctBy { Triple(it.air.at, it.air.season, it.air.episode) }
    }

    /** When an episode airs here, from the saved TVmaze times only. Null when not known. */
    fun airTime(ctx: Context, id: String, season: Int, episode: Int): Long? {
        val a = synchronized(cache) { cache[id] } ?: readCache(ctx, id, ignoreAge = true)?.first ?: return null
        return a.at["$season:$episode"]
    }

    // ---------------------------------------------------------------- lookups

    private fun cacheFile(ctx: Context, id: String) = java.io.File(java.io.File(ctx.cacheDir, "tvmaze").apply { mkdirs() }, "$id.json")

    /** Saved times and whether they are still fresh. A saved miss reads as empty times. */
    private fun readCache(ctx: Context, id: String, ignoreAge: Boolean = false): Pair<Airing, Boolean>? = try {
        val f = cacheFile(ctx, id)
        if (!f.isFile) null else {
            val o = JSONObject(f.readText())
            val age = System.currentTimeMillis() - o.optLong("t")
            if (o.optBoolean("none")) {
                if (age < MISS_FRESH || ignoreAge) Airing(emptyMap(), emptyList()) to true else null
            } else parse(o) to (age < FRESH)
        }
    } catch (e: Exception) { null }

    private fun parse(o: JSONObject): Airing {
        val net = o.optString("net")
        val eps = o.getJSONArray("eps")
        val now = System.currentTimeMillis()
        val at = HashMap<String, Long>()
        val next = ArrayList<Air>()
        for (i in 0 until eps.length()) {
            val e = eps.getJSONArray(i)
            val s = e.getInt(0); val n = e.getInt(1); val t = e.getLong(3)
            at["$s:$n"] = t
            if (t > now) next.add(Air(s, n, e.optString(2), t, true, net))
        }
        next.sortBy { it.at }
        return Airing(at, next)
    }

    /** Air times for one series: TVmaze first, then the Kitsu addon's or Cinemeta's dates. Never throws. */
    private fun airing(ctx: Context, id: String): Airing {
        synchronized(cache) { cache[id] }?.let { return it }
        val kitsu = if (id.startsWith(KITSU)) kitsuMeta(ctx, id) else null
        val imdb = if (kitsu != null) kitsu.optString("imdb_id").takeIf { it.startsWith("tt") } else id.takeIf { it.startsWith("tt") }
        val saved = imdb?.let { readCache(ctx, it) }
        val tv = if (imdb == null) null else if (saved != null && saved.second) saved.first else fetch(ctx, imdb) ?: saved?.first
        val a = if (tv != null && tv.at.isNotEmpty()) tv else if (kitsu != null) kitsuFallback(kitsu) else fallback(ctx, id)
        synchronized(cache) { cache[id] = a }
        return a
    }

    /** Asks TVmaze and saves the answer. Null when the network failed. */
    private fun fetch(ctx: Context, id: String): Airing? {
        val file = cacheFile(ctx, id)
        val show = try {
            Net.getJson(TVMAZE + "lookup/shows?imdb=" + id)
        } catch (e: Exception) {
            // Not found: saved as a miss so it isn't asked again for a while.
            if (e.message?.contains("404") == true) {
                Store.writeAtomic(file, JSONObject().put("t", System.currentTimeMillis()).put("none", true).toString())
                return Airing(emptyMap(), emptyList())
            }
            return null
        }
        return try {
            val net = (show.optJSONObject("network") ?: show.optJSONObject("webChannel"))?.optString("name").orEmpty()
            val list = JSONArray(String(Net.getBytes(TVMAZE + "shows/" + show.getInt("id") + "/episodes"), Charsets.UTF_8))
            val eps = JSONArray()
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                val stamp = e.optString("airstamp")
                val s = e.optInt("season"); val n = e.optInt("number")
                if (stamp.isEmpty() || s <= 0 || n <= 0) continue
                val t = try { java.time.OffsetDateTime.parse(stamp).toInstant().toEpochMilli() } catch (x: Exception) { continue }
                eps.put(JSONArray().put(s).put(n).put(e.optString("name")).put(t))
            }
            val o = JSONObject().put("t", System.currentTimeMillis()).put("net", net).put("eps", eps)
            Store.writeAtomic(file, o.toString())
            parse(o)
        } catch (e: Exception) { null }
    }

    private fun fallback(ctx: Context, id: String): Airing {
        val now = System.currentTimeMillis()
        val m = try { Watch.meta(ctx, "series", id) } catch (e: Exception) { null }
        val next = m?.videos.orEmpty().filter { it.released > now }.map { Air(it.season, it.episode, it.title, it.released, hasTime(it.released), "") }
        return Airing(emptyMap(), next.sortedBy { it.at })
    }

    /** A time that isn't midnight UTC is an air time, not just a date. */
    private fun hasTime(t: Long) = t % DAY != 0L

    /** The Kitsu addon's details for an anime, saved for 12 hours. Null when unknown. */
    fun kitsuMeta(ctx: Context, id: String): JSONObject? {
        val f = java.io.File(java.io.File(ctx.cacheDir, "kitsu").apply { mkdirs() }, id.replace(':', '_') + ".json")
        val old = try { if (f.isFile) JSONObject(f.readText()) else null } catch (e: Exception) { null }
        if (old != null && System.currentTimeMillis() - old.optLong("t") < FRESH) return old.optJSONObject("meta")
        return try {
            val meta = Net.getJson(KITSU_ADDON + "meta/series/" + id + ".json").getJSONObject("meta")
            Store.writeAtomic(f, JSONObject().put("t", System.currentTimeMillis()).put("meta", meta).toString())
            meta
        } catch (e: Exception) { old?.optJSONObject("meta") }
    }

    private fun kitsuFallback(meta: JSONObject): Airing {
        val now = System.currentTimeMillis()
        val vids = meta.optJSONArray("videos") ?: JSONArray()
        val next = ArrayList<Air>()
        for (i in 0 until vids.length()) {
            val v = vids.optJSONObject(i) ?: continue
            val t = try { java.time.Instant.parse(v.optString("released")).toEpochMilli() } catch (e: Exception) { continue }
            if (t <= now) continue
            val s = v.optInt("imdbSeason", v.optInt("season", 1)).coerceAtLeast(1)
            val n = v.optInt("imdbEpisode", v.optInt("episode", 0))
            if (n > 0) next.add(Air(s, n, v.optString("title"), t, hasTime(t), ""))
        }
        return Airing(emptyMap(), next.sortedBy { it.at })
    }

    // ---------------------------------------------------------------- words

    fun clock(ctx: Context, clockMode: Int, t: Long): String {
        val is24 = when (clockMode) { 1 -> false; 2 -> true; else -> android.text.format.DateFormat.is24HourFormat(ctx) }
        val c = Calendar.getInstance().apply { timeInMillis = t }
        if (is24) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(c.time)
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        return "${if (h % 12 == 0) 12 else h % 12}${if (m > 0) ":%02d".format(m) else ""} ${if (h < 12) "am" else "pm"}"
    }

    /** The TV's time zone as on the date [t], short ("CST") or long ("Central Standard Time"). */
    fun zone(t: Long, long: Boolean = false): String {
        val z = TimeZone.getDefault()
        return z.getDisplayName(z.inDaylightTime(java.util.Date(t)), if (long) TimeZone.LONG else TimeZone.SHORT, Locale.getDefault())
    }

    private fun dayGap(t: Long): Int {
        fun start(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        return Math.round((start(t) - start(System.currentTimeMillis())) / DAY.toDouble()).toInt()
    }

    /** The card's chip, and whether it is soon enough for the accent. */
    fun chip(ctx: Context, clockMode: Int, a: Air): Pair<String, Boolean> {
        val gap = dayGap(a.at)
        val time = if (a.exact) " " + clock(ctx, clockMode, a.at).uppercase() else ""
        val hour = Calendar.getInstance().apply { timeInMillis = a.at }.get(Calendar.HOUR_OF_DAY)
        return when {
            gap <= 0 -> (if (hour >= 17) "TONIGHT" else "TODAY") + time to true
            gap == 1 -> "TOMORROW$time" to true
            gap < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(a.at).uppercase() + time to false
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(a.at).uppercase() to false
        }
    }

    /** The card's second line. */
    fun line(ctx: Context, clockMode: Int, a: Air): String {
        val ep = "S${a.season} E${a.episode}"
        val gap = dayGap(a.at)
        return when {
            !a.exact -> "$ep · Date not confirmed"
            a.episode == 1 -> "$ep · Season premiere"
            gap <= 1 -> "$ep · ${clock(ctx, clockMode, a.at)}"
            else -> "$ep · in $gap days"
        }
    }

    /** The full date, time, zone and network, for the sheet. */
    fun long(ctx: Context, clockMode: Int, a: Air): String {
        val d = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(a.at)
        return if (a.exact) "$d at ${clock(ctx, clockMode, a.at)} ${zone(a.at)}" + (if (a.net.isNotEmpty()) " on ${a.net}" else "") else "Expected $d"
    }

    fun weekday(a: Air): String = SimpleDateFormat("EEEE", Locale.getDefault()).format(a.at)
}
