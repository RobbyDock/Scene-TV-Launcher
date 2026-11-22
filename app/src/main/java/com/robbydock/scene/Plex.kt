package com.robbydock.scene

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * A Plex server, signed in with a code entered at plex.tv/link. Scene reads what is being
 * watched and the library; it changes only watched marks, when asked.
 */
object Plex {
    const val PREFIX = "px:"
    const val CW_CACHE = "continue_px.json"
    private const val TV = "https://plex.tv"

    class Pin(val id: Long, val code: String)
    class Conn(val uri: String, val local: Boolean, val relay: Boolean)
    class Server(val name: String, val machineId: String, val token: String, val owned: Boolean, val conns: List<Conn>)

    private val metaCache = android.util.LruCache<String, Meta>(120)

    // ---------------------------------------------------------------- stored state

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("plex", Context.MODE_PRIVATE)
    private fun base(ctx: Context) = prefs(ctx).getString("server", null)
    private fun serverToken(ctx: Context) = Vault.get(ctx, "plexServer")
    private fun accountToken(ctx: Context) = Vault.get(ctx, "plex")

    fun signedIn(ctx: Context) = base(ctx) != null && serverToken(ctx) != null

    fun account(ctx: Context): String? {
        val p = prefs(ctx)
        val user = p.getString("userName", null) ?: return null
        return "$user on ${p.getString("serverName", "Plex")}"
    }

    fun serverName(ctx: Context) = prefs(ctx).getString("serverName", null)

    fun signOut(ctx: Context) {
        prefs(ctx).edit().remove("server").remove("serverName").remove("machine").remove("userName").apply()
        Vault.remove(ctx, "plex")
        Vault.remove(ctx, "plexServer")
        Store.delete(ctx, CW_CACHE)
        metaCache.evictAll()
    }

    /** A random id for this TV, kept for good, so the account lists one device. */
    private fun clientId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("client", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("client", it).apply() }
    }

    private fun headers(ctx: Context, token: String?) = buildMap {
        put("X-Plex-Product", "Scene")
        put("X-Plex-Version", "1.0")
        put("X-Plex-Client-Identifier", clientId(ctx))
        put("X-Plex-Platform", "Android")
        put("X-Plex-Device-Name", android.os.Build.MODEL)
        if (token != null) put("X-Plex-Token", token)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** A server reply's MediaContainer. */
    private fun mc(ctx: Context, path: String): JSONObject =
        JSONObject(Net.request(base(ctx) + path, headers = headers(ctx, serverToken(ctx)))).optJSONObject("MediaContainer") ?: JSONObject()

    private fun list(o: JSONObject, key: String = "Metadata") = o.optJSONArray(key) ?: JSONArray()

    // ---------------------------------------------------------------- sign-in

    fun pinStart(ctx: Context): Pin {
        val o = JSONObject(Net.request("$TV/api/v2/pins", "POST", "", headers(ctx, null) + ("Content-Type" to "application/x-www-form-urlencoded")))
        return Pin(o.getLong("id"), o.getString("code"))
    }

    /** The account token once the code was entered at plex.tv/link, or null while waiting. */
    fun pinCheck(ctx: Context, pin: Pin): String? =
        JSONObject(Net.request("$TV/api/v2/pins/${pin.id}", headers = headers(ctx, null))).optString("authToken").ifEmpty { null }

    /** Servers the account can use, the user's own first. */
    fun servers(ctx: Context, account: String): List<Server> {
        val a = JSONArray(Net.request("$TV/api/v2/resources?includeHttps=1&includeRelay=1", headers = headers(ctx, account)))
        val out = ArrayList<Server>()
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            if (!r.optString("provides").contains("server")) continue
            val conns = ArrayList<Conn>()
            r.optJSONArray("connections")?.let { c ->
                for (k in 0 until c.length()) c.optJSONObject(k)?.let { conns.add(Conn(it.optString("uri"), it.optBoolean("local"), it.optBoolean("relay"))) }
            }
            out.add(Server(r.optString("name"), r.optString("clientIdentifier"), r.optString("accessToken", account), r.optBoolean("owned"), conns))
        }
        return out.sortedByDescending { it.owned }
    }

    /**
     * Finds an address that answers, home network first, then remote, then Plex's relay, and
     * keeps it with the tokens.
     */
    fun connect(ctx: Context, account: String, s: Server): Boolean {
        val order = s.conns.filter { it.local && !it.relay } + s.conns.filter { !it.local && !it.relay } + s.conns.filter { it.relay }
        for (c in order) {
            try {
                Net.request("${c.uri}/identity", headers = headers(ctx, s.token), timeoutMs = 4_000)
            } catch (e: Exception) {
                continue
            }
            val user = try {
                JSONObject(Net.request("$TV/api/v2/user", headers = headers(ctx, account))).optString("username").ifEmpty { null }
            } catch (e: Exception) { null }
            Vault.put(ctx, "plex", account)
            Vault.put(ctx, "plexServer", s.token)
            prefs(ctx).edit().putString("server", c.uri).putString("serverName", s.name).putString("machine", s.machineId)
                .putString("userName", user ?: "You").apply()
            Store.delete(ctx, CW_CACHE)
            metaCache.evictAll()
            return true
        }
        return false
    }

    /** Other servers on the same account, for the Server choice in Settings. */
    fun otherServers(ctx: Context): List<Server> {
        val account = accountToken(ctx) ?: return emptyList()
        return servers(ctx, account)
    }

    fun reconnect(ctx: Context, s: Server): Boolean {
        val account = accountToken(ctx) ?: return false
        return connect(ctx, account, s)
    }

    // ---------------------------------------------------------------- images

    private fun photo(ctx: Context, path: String?, w: Int, h: Int): String? {
        if (path.isNullOrEmpty()) return null
        return "${base(ctx)}/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1&url=${enc(path)}&X-Plex-Token=${serverToken(ctx)}"
    }

    // ---------------------------------------------------------------- continue watching

    fun fetchContinue(ctx: Context): List<CwItem> {
        val out = ArrayList<CwItem>()
        val hubs = try { list(mc(ctx, "/hubs/continueWatching"), "Hub") } catch (e: Exception) { JSONArray() }
        val items = ArrayList<JSONObject>()
        for (i in 0 until hubs.length()) { val m = list(hubs.optJSONObject(i) ?: continue); for (k in 0 until m.length()) m.optJSONObject(k)?.let { items.add(it) } }
        if (items.isEmpty()) { val m = list(mc(ctx, "/library/onDeck")); for (k in 0 until m.length()) m.optJSONObject(k)?.let { items.add(it) } }
        for (o in items) card(ctx, o)?.let { c -> if (out.none { it.id == c.id }) out.add(c) }
        out.sortByDescending { it.lastWatched }
        return out
    }

    /** One card. An episode stands for its show, and shows the episode's own still. */
    private fun card(ctx: Context, o: JSONObject): CwItem? {
        val rk = o.optString("ratingKey").ifEmpty { return null }
        val last = (o.optLong("lastViewedAt").takeIf { it > 0 } ?: o.optLong("addedAt")) * 1000
        return when (o.optString("type")) {
            "episode" -> {
                val show = o.optString("grandparentRatingKey").ifEmpty { return null }
                CwItem(PREFIX + show, "series", o.optString("grandparentTitle"), photo(ctx, o.optString("grandparentThumb"), 400, 600),
                    "$rk:${o.optInt("parentIndex", 1)}:${o.optInt("index", 1)}", o.optLong("viewOffset"), o.optLong("duration"), last,
                    still = photo(ctx, o.optString("thumb"), 640, 360) ?: photo(ctx, o.optString("grandparentArt"), 640, 360))
            }
            "movie" -> CwItem(PREFIX + rk, "movie", o.optString("title"), photo(ctx, o.optString("thumb"), 400, 600), null,
                o.optLong("viewOffset"), o.optLong("duration"), last, still = photo(ctx, o.optString("art"), 640, 360))
            else -> null
        }
    }

    // ---------------------------------------------------------------- details

    fun cachedMeta(id: String): Meta? = metaCache.get(id)

    fun meta(ctx: Context, type: String, id: String): Meta? {
        cachedMeta(id)?.let { return it }
        val rk = id.removePrefix(PREFIX)
        val o = list(mc(ctx, "/library/metadata/$rk")).optJSONObject(0) ?: return null
        val videos = ArrayList<Video>()
        if (o.optString("type") == "show") {
            val eps = list(mc(ctx, "/library/metadata/$rk/allLeaves"))
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val s = e.optInt("parentIndex", -1)
                val n = e.optInt("index", -1)
                if (s <= 0 || n < 0) continue
                val released = try { java.time.LocalDate.parse(e.optString("originallyAvailableAt")).toEpochDay() * 86_400_000L } catch (x: Exception) { 0L }
                videos.add(Video("${e.optString("ratingKey")}:$s:$n", s, n, e.optString("title"), e.optString("summary"), released,
                    photo(ctx, e.optString("thumb"), 640, 360)))
            }
        }
        val genres = ArrayList<String>()
        o.optJSONArray("Genre")?.let { a -> for (i in 0 until minOf(a.length(), 3)) a.optJSONObject(i)?.optString("tag")?.let { genres.add(it) } }
        var logo: String? = null
        o.optJSONArray("Image")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("type") == "clearLogo" }?.let { logo = photo(ctx, it.optString("url"), 800, 400) } }
        val dur = o.optLong("duration")
        val m = Meta(
            id, type, o.optString("title"), photo(ctx, o.optString("art"), 1920, 1080), logo, photo(ctx, o.optString("thumb"), 400, 600),
            o.optString("summary"), o.optInt("year").takeIf { it > 0 }?.toString() ?: "", "",
            if (dur > 0 && type == "movie") "${dur / 60_000} min" else "", genres, videos,
        )
        metaCache.put(id, m)
        return m
    }

    // ---------------------------------------------------------------- search and library

    private fun hit(ctx: Context, o: JSONObject): SearchHit? {
        // Episodes and seasons stand for their show.
        val (rk, type, title, thumb) = when (o.optString("type")) {
            "movie" -> listOf(o.optString("ratingKey"), "movie", o.optString("title"), o.optString("thumb"))
            "show" -> listOf(o.optString("ratingKey"), "series", o.optString("title"), o.optString("thumb"))
            "season" -> listOf(o.optString("parentRatingKey"), "series", o.optString("parentTitle"), o.optString("parentThumb"))
            "episode" -> listOf(o.optString("grandparentRatingKey"), "series", o.optString("grandparentTitle"), o.optString("grandparentThumb"))
            else -> return null
        }
        if (rk.isEmpty()) return null
        val leaves = o.optInt("leafCount")
        val watched = if (type == "movie") o.optInt("viewCount") > 0 else leaves > 0 && o.optInt("viewedLeafCount") >= leaves
        val dur = o.optLong("duration")
        val progress = if (type == "movie" && dur > 0) (o.optLong("viewOffset").toFloat() / dur) else 0f
        return SearchHit(PREFIX + rk, type, title, photo(ctx, thumb, 400, 600), o.optInt("year").takeIf { it > 0 }?.toString() ?: "",
            watched, progress, o.optLong("addedAt") * 1000)
    }

    private fun hits(ctx: Context, a: JSONArray): List<SearchHit> {
        val out = LinkedHashMap<String, SearchHit>()
        for (i in 0 until a.length()) hit(ctx, a.optJSONObject(i) ?: continue)?.let { if (!out.containsKey(it.id)) out[it.id] = it }
        return out.values.toList()
    }

    fun search(ctx: Context, query: String): List<SearchHit> {
        val hubs = list(mc(ctx, "/hubs/search?query=${enc(query.trim())}&limit=12"), "Hub")
        val all = JSONArray()
        for (i in 0 until hubs.length()) {
            val h = hubs.optJSONObject(i) ?: continue
            if (h.optString("type") !in setOf("movie", "show")) continue
            val m = list(h)
            for (k in 0 until m.length()) all.put(m.opt(k))
        }
        return hits(ctx, all)
    }

    /** The first library section of [type]: "movie" or "show". */
    private fun section(ctx: Context, type: String): String? {
        val d = list(mc(ctx, "/library/sections"), "Directory")
        for (i in 0 until d.length()) d.optJSONObject(i)?.takeIf { it.optString("type") == type }?.let { return it.optString("key") }
        return null
    }

    /** [key] is recent, movies or shows. [sort]: recently added, A to Z, release year, unwatched. */
    fun shelf(ctx: Context, key: String, sort: Int, start: Int, count: Int): List<SearchHit> {
        val page = "X-Plex-Container-Start=$start&X-Plex-Container-Size=$count"
        if (key == "recent") return hits(ctx, list(mc(ctx, "/library/recentlyAdded?$page")))
        val sec = section(ctx, if (key == "shows") "show" else "movie") ?: return emptyList()
        val order = when (sort) {
            1 -> "sort=titleSort"
            2 -> "sort=originallyAvailableAt:desc"
            3 -> "sort=addedAt:desc&unwatched=1"
            else -> "sort=addedAt:desc"
        }
        return hits(ctx, list(mc(ctx, "/library/sections/$sec/all?$order&$page")))
    }

    // ---------------------------------------------------------------- changes

    /** Marks the film, or the card's episode, as watched. */
    fun markWatched(ctx: Context, item: CwItem): String {
        val rk = item.videoId?.substringBefore(':') ?: item.id.removePrefix(PREFIX)
        try {
            Net.request(base(ctx) + "/:/scrobble?key=$rk&identifier=com.plexapp.plugins.library", headers = headers(ctx, serverToken(ctx)))
        } catch (e: Exception) {
            throw Watch.WriteFailed("Plex refused the change")
        }
        return "Marked as watched"
    }

    /** Opens the episode, or the title, in the Plex app. */
    fun openIntent(ctx: Context, id: String, videoId: String?): Intent {
        val rk = videoId?.substringBefore(':') ?: id.removePrefix(PREFIX)
        val machine = prefs(ctx).getString("machine", "") ?: ""
        return Intent(Intent.ACTION_VIEW, Uri.parse("plex://server://$machine/com.plexapp.plugins.library/library/metadata/$rk"))
            .setPackage("com.plexapp.android").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
