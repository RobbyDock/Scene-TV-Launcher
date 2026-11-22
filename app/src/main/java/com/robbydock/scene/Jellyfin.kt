package com.robbydock.scene

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLEncoder

/**
 * A Jellyfin server on the home network: found by broadcast or typed in, signed in with Quick
 * Connect. Scene reads what is being watched and the library; it changes only watched marks
 * and favourites, when asked.
 */
object Jellyfin {
    const val PREFIX = "jf:"
    const val CW_CACHE = "continue_jf.json"
    private const val APP = "org.jellyfin.androidtv"

    class Server(val name: String, val address: String, val version: String)
    class QuickCode(val code: String, val secret: String)
    class Refused(msg: String) : Exception(msg)

    private val metaCache = android.util.LruCache<String, Meta>(120)

    // ---------------------------------------------------------------- stored state

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
    private fun base(ctx: Context) = prefs(ctx).getString("server", null)
    private fun userId(ctx: Context) = prefs(ctx).getString("user", null)
    private fun token(ctx: Context) = Vault.get(ctx, "jellyfin")

    fun signedIn(ctx: Context) = base(ctx) != null && userId(ctx) != null && token(ctx) != null

    fun account(ctx: Context): String? {
        val p = prefs(ctx)
        val user = p.getString("userName", null) ?: return null
        return "$user on ${p.getString("serverName", "Jellyfin")}"
    }

    fun nextUp(ctx: Context) = prefs(ctx).getBoolean("nextUp", true)
    fun setNextUp(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("nextUp", on).apply()

    fun signOut(ctx: Context) {
        val b = base(ctx)
        val t = token(ctx)
        if (b != null && t != null) Net.background { Net.request("$b/Sessions/Logout", "POST", "", headers(ctx, t), 4_000) }
        prefs(ctx).edit().remove("server").remove("user").remove("userName").remove("serverName").apply()
        Vault.remove(ctx, "jellyfin")
        Store.delete(ctx, CW_CACHE)
        metaCache.evictAll()
    }

    /** A random id for this TV, kept for good, so the server lists one device. */
    private fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("device", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").also { p.edit().putString("device", it).apply() }
    }

    private fun headers(ctx: Context, token: String?): Map<String, String> {
        val v = "MediaBrowser Client=\"Scene\", Device=\"${android.os.Build.MODEL.replace("\"", "")}\", DeviceId=\"${deviceId(ctx)}\", Version=\"1.0\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")
        return mapOf("Authorization" to v, "X-Emby-Authorization" to v)
    }

    private fun get(ctx: Context, path: String): String =
        Net.request(base(ctx) + path, headers = headers(ctx, token(ctx)))

    private fun obj(ctx: Context, path: String) = JSONObject(get(ctx, path))

    /** Tries the current path, then the older one that servers before 10.9 use. */
    private fun objOr(ctx: Context, path: String, old: String): JSONObject = try {
        obj(ctx, path)
    } catch (e: Net.HttpError) {
        if (e.code == 404) obj(ctx, old) else throw e
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------- finding a server

    /** Asks the network who runs Jellyfin. Replies arrive for [waitMs]. */
    fun discover(waitMs: Int = 2_000): List<Server> {
        val out = LinkedHashMap<String, Server>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 300
            val msg = "who is JellyfinServer?".toByteArray()
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), 7359))
            val until = System.currentTimeMillis() + waitMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < until) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }
                try {
                    val o = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                    val addr = o.optString("Address").trimEnd('/')
                    if (addr.isNotEmpty() && Net.isLocal(java.net.URL(addr).host)) out[addr] = Server(o.optString("Name", "Jellyfin"), addr, "")
                } catch (e: Exception) { }
            }
        }
        return out.values.toList()
    }

    /** Adds http:// and the usual port when left out. */
    fun normalise(text: String): String {
        var t = text.trim().trimEnd('/')
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "http://$t"
        val u = java.net.URL(t)
        if (u.port == -1 && u.protocol == "http") t = "${u.protocol}://${u.host}:8096${u.path}"
        return t
    }

    /** Checks [address] answers as a Jellyfin server and returns its name and version. */
    fun check(address: String): Server {
        val o = JSONObject(Net.request("$address/System/Info/Public", timeoutMs = 6_000))
        if (!o.optString("ProductName").contains("Jellyfin", true) && o.optString("Id").isEmpty()) throw Refused("That address isn't a Jellyfin server")
        return Server(o.optString("ServerName", "Jellyfin"), address, o.optString("Version"))
    }

    // ---------------------------------------------------------------- Quick Connect

    fun quickEnabled(address: String): Boolean =
        Net.request("$address/QuickConnect/Enabled", timeoutMs = 6_000).trim().equals("true", true)

    fun quickStart(ctx: Context, address: String): QuickCode {
        val text = try {
            Net.request("$address/QuickConnect/Initiate", "POST", "", headers(ctx, null))
        } catch (e: Net.HttpError) {
            if (e.code == 404 || e.code == 405) Net.request("$address/QuickConnect/Initiate", headers = headers(ctx, null)) else throw e
        }
        val o = JSONObject(text)
        return QuickCode(o.getString("Code"), o.getString("Secret"))
    }

    /** True once the code was approved in another Jellyfin app. */
    fun quickApproved(ctx: Context, address: String, secret: String): Boolean =
        JSONObject(Net.request("$address/QuickConnect/Connect?secret=${enc(secret)}", headers = headers(ctx, null))).optBoolean("Authenticated")

    /** Trades the approved secret for a token, and keeps the token encrypted. */
    fun quickFinish(ctx: Context, server: Server, secret: String) {
        val r = JSONObject(Net.request("${server.address}/Users/AuthenticateWithQuickConnect", "POST",
            JSONObject().put("Secret", secret).toString(), headers(ctx, null)))
        val token = r.getString("AccessToken")
        val user = r.getJSONObject("User")
        Vault.put(ctx, "jellyfin", token)
        prefs(ctx).edit().putString("server", server.address).putString("user", user.getString("Id"))
            .putString("userName", user.optString("Name")).putString("serverName", server.name).apply()
    }

    // ---------------------------------------------------------------- images

    private fun img(ctx: Context, id: String, kind: String, width: Int) = "${base(ctx)}/Items/$id/Images/$kind?maxWidth=$width&quality=90"

    private fun has(o: JSONObject, kind: String) = o.optJSONObject("ImageTags")?.has(kind) == true

    private fun poster(ctx: Context, o: JSONObject): String? {
        val id = o.optString("Id")
        return when {
            o.optString("Type") == "Episode" -> o.optString("SeriesId").takeIf { it.isNotEmpty() }?.let { img(ctx, it, "Primary", 400) }
            has(o, "Primary") -> img(ctx, id, "Primary", 400)
            else -> null
        }
    }

    // ---------------------------------------------------------------- continue watching

    private const val FIELDS = "Fields=Overview,ProductionYear,DateCreated&EnableImageTypes=Primary,Backdrop,Thumb,Logo"

    private fun items(o: JSONObject) = o.optJSONArray("Items") ?: JSONArray()

    fun fetchContinue(ctx: Context): List<CwItem> {
        val uid = userId(ctx) ?: return emptyList()
        val resume = items(objOr(ctx, "/UserItems/Resume?userId=$uid&Limit=30&MediaTypes=Video&$FIELDS", "/Users/$uid/Items/Resume?Limit=30&MediaTypes=Video&$FIELDS"))
        val out = ArrayList<CwItem>()
        for (i in 0 until resume.length()) card(ctx, resume.optJSONObject(i) ?: continue)?.let { out.add(it) }
        if (nextUp(ctx)) {
            val next = try { items(obj(ctx, "/Shows/NextUp?userId=$uid&Limit=24&EnableResumable=false&$FIELDS")) } catch (e: Exception) { JSONArray() }
            for (i in 0 until next.length()) {
                val c = card(ctx, next.optJSONObject(i) ?: continue) ?: continue
                if (out.none { it.id == c.id }) out.add(c)
            }
        }
        out.sortByDescending { it.lastWatched }
        return out
    }

    /** One card. An episode stands for its series, and shows the episode's own still. */
    private fun card(ctx: Context, o: JSONObject): CwItem? {
        val id = o.optString("Id").ifEmpty { return null }
        val ud = o.optJSONObject("UserData") ?: JSONObject()
        val off = ud.optLong("PlaybackPositionTicks") / 10_000
        val dur = o.optLong("RunTimeTicks") / 10_000
        val last = time(ud.optString("LastPlayedDate")).takeIf { it > 0 } ?: time(o.optString("DateCreated"))
        return when (o.optString("Type")) {
            "Episode" -> {
                val series = o.optString("SeriesId").ifEmpty { return null }
                val still = if (has(o, "Primary")) img(ctx, id, "Primary", 640) else img(ctx, series, "Backdrop", 640)
                CwItem(PREFIX + series, "series", o.optString("SeriesName", o.optString("Name")), img(ctx, series, "Primary", 400),
                    "$id:${o.optInt("ParentIndexNumber", 1)}:${o.optInt("IndexNumber", 1)}", off, dur, last, ud.optBoolean("IsFavorite"), still = still)
            }
            "Movie", "Video" -> {
                val backdrop = (o.optJSONArray("BackdropImageTags")?.length() ?: 0) > 0
                CwItem(PREFIX + id, "movie", o.optString("Name"), poster(ctx, o), null, off, dur, last, ud.optBoolean("IsFavorite"),
                    still = if (backdrop) img(ctx, id, "Backdrop", 640) else poster(ctx, o))
            }
            else -> null
        }
    }

    private fun time(s: String): Long = try {
        if (s.isEmpty()) 0L else java.time.Instant.parse(if (s.endsWith("Z") || s.contains('+')) s else s + "Z").toEpochMilli()
    } catch (e: Exception) {
        try { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e2: Exception) { 0L }
    }

    // ---------------------------------------------------------------- details

    fun cachedMeta(id: String): Meta? = metaCache.get(id)

    fun meta(ctx: Context, type: String, id: String): Meta? {
        cachedMeta(id)?.let { return it }
        val uid = userId(ctx) ?: return null
        val raw = id.removePrefix(PREFIX)
        val o = objOr(ctx, "/Items/$raw?userId=$uid", "/Users/$uid/Items/$raw")
        val videos = ArrayList<Video>()
        if (o.optString("Type") == "Series") {
            val eps = items(obj(ctx, "/Shows/$raw/Episodes?userId=$uid&Fields=Overview&EnableImageTypes=Primary"))
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val s = e.optInt("ParentIndexNumber", -1)
                val n = e.optInt("IndexNumber", -1)
                if (s <= 0 || n < 0) continue
                val eid = e.optString("Id")
                videos.add(Video("$eid:$s:$n", s, n, e.optString("Name"), e.optString("Overview"), time(e.optString("PremiereDate")),
                    if (has(e, "Primary")) img(ctx, eid, "Primary", 640) else null))
            }
        }
        val genres = ArrayList<String>()
        o.optJSONArray("Genres")?.let { a -> for (i in 0 until minOf(a.length(), 3)) genres.add(a.optString(i)) }
        val ticks = o.optLong("RunTimeTicks")
        val backdrop = (o.optJSONArray("BackdropImageTags")?.length() ?: 0) > 0
        val m = Meta(
            id, type, o.optString("Name"), if (backdrop) img(ctx, raw, "Backdrop", 1920) else null,
            if (has(o, "Logo")) img(ctx, raw, "Logo", 800) else null, if (has(o, "Primary")) img(ctx, raw, "Primary", 400) else null,
            o.optString("Overview"), o.optInt("ProductionYear").takeIf { it > 0 }?.toString() ?: "", "",
            if (ticks > 0 && type == "movie") "${ticks / 600_000_000} min" else "", genres, videos,
        )
        metaCache.put(id, m)
        return m
    }

    // ---------------------------------------------------------------- search and library

    private fun hit(ctx: Context, o: JSONObject): SearchHit? {
        val type = when (o.optString("Type")) { "Movie" -> "movie"; "Series" -> "series"; else -> return null }
        val ud = o.optJSONObject("UserData") ?: JSONObject()
        return SearchHit(PREFIX + o.optString("Id"), type, o.optString("Name"), poster(ctx, o),
            o.optInt("ProductionYear").takeIf { it > 0 }?.toString() ?: "", ud.optBoolean("Played"),
            (ud.optDouble("PlayedPercentage", 0.0) / 100.0).toFloat(), time(o.optString("DateCreated")))
    }

    private fun hits(ctx: Context, a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> hit(ctx, o) } }

    fun search(ctx: Context, query: String): List<SearchHit> {
        val uid = userId(ctx) ?: return emptyList()
        return hits(ctx, items(obj(ctx, "/Items?userId=$uid&searchTerm=${enc(query.trim())}&IncludeItemTypes=Movie,Series&Recursive=true&Limit=24&Fields=ProductionYear")))
    }

    /** [key] is recent, movies or shows. [sort]: recently added, A to Z, release year, unwatched. */
    fun shelf(ctx: Context, key: String, sort: Int, start: Int, count: Int): List<SearchHit> {
        val uid = userId(ctx) ?: return emptyList()
        if (key == "recent") {
            if (start > 0) return emptyList()
            return hits(ctx, JSONArray(get(ctx, "/Items/Latest?userId=$uid&Limit=$count&IncludeItemTypes=Movie,Series&Fields=ProductionYear,DateCreated")))
        }
        val kind = if (key == "shows") "Series" else "Movie"
        val order = when (sort) {
            1 -> "SortBy=SortName&SortOrder=Ascending"
            2 -> "SortBy=PremiereDate,SortName&SortOrder=Descending"
            3 -> "SortBy=DateCreated&SortOrder=Descending&Filters=IsUnplayed"
            else -> "SortBy=DateCreated&SortOrder=Descending"
        }
        return hits(ctx, items(obj(ctx, "/Items?userId=$uid&IncludeItemTypes=$kind&Recursive=true&$order&StartIndex=$start&Limit=$count&Fields=ProductionYear,DateCreated")))
    }

    // ---------------------------------------------------------------- changes

    private fun write(ctx: Context, method: String, path: String, old: String) {
        try {
            Net.request(base(ctx) + path, method, if (method == "POST") "" else null, headers(ctx, token(ctx)))
        } catch (e: Net.HttpError) {
            if (e.code == 404) Net.request(base(ctx) + old, method, if (method == "POST") "" else null, headers(ctx, token(ctx)))
            else throw Watch.WriteFailed("Jellyfin refused the change")
        }
    }

    /** Marks the film, or the card's episode, as played. */
    fun markWatched(ctx: Context, item: CwItem): String {
        val uid = userId(ctx) ?: throw Watch.WriteFailed("Not signed in")
        val target = item.videoId?.substringBefore(':') ?: item.id.removePrefix(PREFIX)
        write(ctx, "POST", "/UserPlayedItems/$target?userId=$uid", "/Users/$uid/PlayedItems/$target")
        return "Marked as watched"
    }

    fun setFavourite(ctx: Context, id: String, on: Boolean) {
        val uid = userId(ctx) ?: throw Watch.WriteFailed("Not signed in")
        val raw = id.removePrefix(PREFIX)
        write(ctx, if (on) "POST" else "DELETE", "/UserFavoriteItems/$raw?userId=$uid", "/Users/$uid/FavoriteItems/$raw")
    }

    /** Opens the episode, or the title, in the Jellyfin app's details page. */
    fun openIntent(id: String, videoId: String?): Intent =
        Intent(Intent.ACTION_VIEW).setClassName(APP, "$APP.ui.startup.StartupActivity")
            .putExtra("ItemId", uuid(videoId?.substringBefore(':') ?: id.removePrefix(PREFIX)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Jellyfin ids come without dashes; the app reads them as UUIDs. */
    private fun uuid(id: String): String =
        if (id.length == 32 && !id.contains('-')) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id
}
