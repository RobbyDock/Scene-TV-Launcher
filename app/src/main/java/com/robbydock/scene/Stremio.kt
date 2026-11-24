package com.robbydock.scene

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One Continue watching card. [newCount] above zero marks a series with episodes released since
 * it was last watched; [videoId] then points at the first new episode.
 */
class CwItem(
    val id: String, val type: String, val name: String, val poster: String?,
    val videoId: String?, val offsetMs: Long, val durationMs: Long, val lastWatched: Long,
    val inLibrary: Boolean = true, val newCount: Int = 0,
    /** Stremio tells about new episodes of this series. */
    val notify: Boolean = true,
    /** Card art from Jellyfin or Plex: the episode's own still, or the film's backdrop. */
    val still: String? = null,
) {
    val season: Int? get() = videoId?.split(":")?.getOrNull(1)?.toIntOrNull()
    val episode: Int? get() = videoId?.split(":")?.getOrNull(2)?.toIntOrNull()
    val progress: Float get() = if (durationMs > 0) (offsetMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val minutesLeft: Int get() = if (durationMs > 0) ((durationMs - offsetMs) / 60000L).toInt().coerceAtLeast(1) else 0
    val isNew get() = newCount > 0
    /** True when there is playback to resume, not just a new episode to start. */
    val started get() = offsetMs > 1000

    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name)
        .put("poster", poster ?: "").put("video", videoId ?: "").put("off", offsetMs)
        .put("dur", durationMs).put("last", lastWatched).put("lib", inLibrary).put("new", newCount).put("notify", notify)
        .put("still", still ?: "")

    fun withNew(count: Int, video: String?, at: Long) =
        CwItem(id, type, name, poster, video ?: videoId, if (video != null && video != videoId) 0 else offsetMs,
            if (video != null && video != videoId) 0 else durationMs, maxOf(lastWatched, at), inLibrary, count, notify, still)

    companion object {
        fun fromJson(o: JSONObject) = CwItem(
            o.getString("id"), o.optString("type", "movie"), o.optString("name"),
            o.optString("poster").ifEmpty { null }, o.optString("video").ifEmpty { null },
            o.optLong("off"), o.optLong("dur"), o.optLong("last"), o.optBoolean("lib", true), o.optInt("new"), o.optBoolean("notify", true),
            o.optString("still").ifEmpty { null },
        )
    }
}

class Video(val id: String, val season: Int, val episode: Int, val title: String, val overview: String, val released: Long = 0L, val thumb: String? = null)

/** A followed series that may have new episodes. */
class Followed(val id: String, val name: String, val poster: String?, val lastWatched: Long, val videoId: String?)

class Meta(
    val id: String, val type: String, val name: String, val background: String?, val logo: String?,
    val poster: String?, val description: String, val year: String, val rating: String,
    val runtime: String, val genres: List<String>, val videos: List<Video>,
    /** Every video, specials included, in the order Stremio uses for its watched bitfield. */
    val all: List<Video> = videos,
    /** YouTube id of the first trailer, when Cinemeta lists one. */
    val trailer: String? = null,
)

class SearchHit(
    val id: String, val type: String, val name: String, val poster: String?, val year: String,
    /** Library screen marks: watched, and how far through. */
    val watched: Boolean = false, val progress: Float = 0f, val added: Long = 0L,
)

class LinkCode(val code: String, val link: String, val qr: String)

/** Stremio account, library and Cinemeta access. */
object Stremio {
    private const val API = "https://api.strem.io/api/"
    private const val LINK = "https://link.stremio.com/api/v2/"
    private const val CINEMETA = "https://v3-cinemeta.strem.io/"
    private const val CW_CACHE = "continue.json"
    /** Saved titles, so the library rows show after a restart while offline. */
    private const val LIB_CACHE = "stremio-library.json"

    private val metaCache = android.util.LruCache<String, Meta>(160)
    private val searchCache = android.util.LruCache<String, List<SearchHit>>(40)

    /** How long a cached series title stays fresh. */
    private const val SERIES_TTL = 12L * 3600_000
    private const val MOVIE_TTL = 7L * 24 * 3600_000

    // ------------------------------------------------------------ session

    fun signedIn(ctx: Context) = Demo.on || Session.authKey(ctx) != null

    fun email(ctx: Context): String? = if (Demo.on) Demo.EMAIL else Session.prefs(ctx).getString("email", null)

    fun signOut(ctx: Context) {
        Session.clear(ctx)
        Store.delete(ctx, CW_CACHE)
        Store.delete(ctx, LIB_CACHE)
        savedTitles = emptyList()
        libraryLoaded = false
    }

    // ------------------------------------------------------------ link code sign-in

    fun createLink(): LinkCode {
        val o = Net.getJson(LINK + "create?type=Create")
        val r = o.optJSONObject("result") ?: o
        return LinkCode(r.getString("code"), r.getString("link"), r.getString("qrcode"))
    }

    /** Returns the auth key once the code is approved, or null while it is still pending. */
    fun readLink(code: String): String? {
        val o = Net.getJson(LINK + "read?type=Read&code=" + enc(code))
        val r = o.optJSONObject("result") ?: o
        return r.optString("authKey").ifEmpty { null }
    }

    /** Stores the key and fetches the account email. */
    fun completeSignIn(ctx: Context, authKey: String) {
        Session.save(ctx, authKey)
        val user = Net.postJson(API + "getUser", JSONObject().put("type", "GetUser").put("authKey", authKey))
        val email = (user.optJSONObject("result") ?: user).optString("email")
        if (email.isNotEmpty()) Session.prefs(ctx).edit().putString("email", email).apply()
    }

    // ------------------------------------------------------------ continue watching

    /** Milliseconds since Continue watching was last saved, or null with no cache. */
    fun continueAge(ctx: Context): Long? {
        val f = java.io.File(ctx.filesDir, CW_CACHE)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else null
    }

    /** The saved Continue watching row, filtered by [CacheAge]. A damaged file is deleted. */
    /** Fills the saved titles from the last fetch, when this run has none yet. Off the main thread. */
    fun loadSaved(ctx: Context) {
        if (savedTitles.isNotEmpty() || libraryLoaded) return
        val text = Store.readText(ctx, LIB_CACHE) ?: return
        val read = try {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                SearchHit(id, o.optString("t"), o.optString("n", id), o.optString("p").ifEmpty { null }, o.optString("y"), added = o.optLong("a"))
            }
        } catch (e: Exception) { Store.delete(ctx, LIB_CACHE); emptyList() }
        // A fetch that finished meanwhile is newer, so it wins.
        if (savedTitles.isEmpty() && !libraryLoaded) savedTitles = read
    }

    fun cachedContinue(ctx: Context, online: Boolean): List<CwItem> {
        val age = continueAge(ctx) ?: return emptyList()
        val text = Store.readText(ctx, CW_CACHE) ?: return emptyList()
        val all = try {
            val a = JSONArray(text)
            (0 until a.length()).map { CwItem.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            Store.delete(ctx, CW_CACHE)
            return emptyList()
        }
        return all.filter { CacheAge.keepCard(age, online, it.started) }
    }

    class SessionExpired : Exception()

    /** Fetches the library and derives Continue watching. */
    fun fetchContinue(ctx: Context): List<CwItem> {
        val key = Session.authKey(ctx) ?: return emptyList()
        val body = JSONObject().put("authKey", key).put("collection", "libraryItem")
            .put("ids", JSONArray()).put("all", true)
        val o = Net.postJson(API + "datastoreGet", body)
        o.optJSONObject("error")?.let { err ->
            if (err.optInt("code") == 1) { signOut(ctx); throw SessionExpired() }
            throw IllegalStateException(err.optString("message"))
        }
        val arr = o.optJSONArray("result") ?: JSONArray()
        val out = ArrayList<CwItem>()
        val follow = ArrayList<Followed>()
        val seen = ArrayList<Followed>()
        val keep = ArrayList<SearchHit>()
        val known = HashSet<String>()
        val yearAgo = System.currentTimeMillis() - 365L * 24 * 3600_000
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val type = it.optString("type")
            if (type != "movie" && type != "series") continue
            val removed = it.optBoolean("removed")
            val temp = it.optBoolean("temp")
            val st = it.optJSONObject("state") ?: continue
            val id = it.optString("_id")
            // Saved, or watched even once: Newly popular counts it as seen.
            if (!(removed && !temp) || st.optInt("timesWatched") > 0 || parseTime(st.opt("lastWatched")) > 0) {
                known.add(id)
                known.add(NewlyPopular.NAME + NewlyPopular.nameKey(it.optString("name")))
            }
            // Coming up follows series actually watched in the last year, anime included. Removed ones stay out.
            val watchedAt = parseTime(st.opt("lastWatched"))
            if (type == "series" && (id.startsWith("tt") || id.startsWith("kitsu:")) && (watchedAt > 0 || st.optInt("timesWatched") > 0) &&
                !(removed && !temp)) {
                val at = if (watchedAt > 0) watchedAt else parseTime(it.opt("_mtime"))
                if (at > yearAgo) seen.add(Followed(id, it.optString("name", id), it.optString("poster").ifEmpty { null }, at, null))
            }
            if (!id.startsWith("tt")) continue
            val last = parseTime(st.opt("lastWatched")).takeIf { t -> t > 0 } ?: parseTime(it.opt("_mtime"))
            val poster = it.optString("poster").ifEmpty { null }
            val video = st.optString("video_id").ifEmpty { null }
            if (!removed && !temp) keep.add(SearchHit(id, type, it.optString("name", id), poster, it.optString("year"), added = parseTime(it.opt("_ctime"))))
            // Series kept in the library get new-episode notifications.
            if (type == "series" && !removed && !temp && !st.optBoolean("noNotif") && last > yearAgo) {
                follow.add(Followed(id, it.optString("name", id), poster, last, video))
            }
            val offset = st.optLong("timeOffset")
            if (offset <= 0 || (removed && !temp)) continue
            out.add(CwItem(id, type, it.optString("name", id), poster, video, offset, st.optLong("duration"), last, !removed && !temp, notify = !st.optBoolean("noNotif")))
        }
        out.sortByDescending { it.lastWatched }
        followed = follow
        watching = seen.sortedByDescending { it.lastWatched }
        seenIds = known
        savedTitles = keep.sortedByDescending { it.added }
        libraryLoaded = true
        Store.writeText(ctx, LIB_CACHE, JSONArray(savedTitles.map { h ->
            JSONObject().put("id", h.id).put("t", h.type).put("n", h.name).put("p", h.poster ?: "").put("y", h.year).put("a", h.added)
        }).toString())
        Store.writeText(ctx, CW_CACHE, JSONArray(out.map { it.toJson() }).toString())
        prefetch(ctx, out)
        return out
    }

    /**
     * A fingerprint of the library: item ids with their change times. It changes whenever any
     * item changes.
     */
    fun libraryStamp(ctx: Context): Int? {
        val key = Session.authKey(ctx) ?: return null
        val o = Net.postJson(API + "datastoreMeta", JSONObject().put("authKey", key).put("collection", "libraryItem"))
        if (o.has("error")) return null
        val r = o.opt("result") ?: return null
        return r.toString().hashCode()
    }

    /** Titles saved in the library, newest first, from the last library fetch. */
    @Volatile private var savedTitles: List<SearchHit> = emptyList()
    /** True once this process has fetched the library, even when it holds no titles. */
    @Volatile var libraryLoaded = false
        private set

    /** Saved titles for the library row ("yours") or the Library tabs ("films", "series"). */
    fun saved(key: String, sort: Int, start: Int, count: Int): List<SearchHit> {
        var list = when (key) {
            "films" -> savedTitles.filter { it.type == "movie" }
            "series" -> savedTitles.filter { it.type == "series" }
            else -> savedTitles
        }
        if (sort == 1) list = list.sortedBy { it.name.lowercase() }
        return list.drop(start).take(count)
    }

    /** Library ids and title keys saved or watched, from the last library fetch: what Newly popular leaves out. */
    @Volatile var seenIds: Set<String> = emptySet()
        private set

    /** Series watched in the last year, anime included, newest first: what Coming up follows. */
    @Volatile var watching: List<Followed> = emptyList()
        private set

    /** Library series checked for new episodes, from the last library fetch. */
    @Volatile var followed: List<Followed> = emptyList()
        private set

    /**
     * Adds series with episodes released since they were last watched. In-progress cards gain a
     * count; others join as new cards pointing at the first new episode.
     */
    fun withNewEpisodes(ctx: Context, items: List<CwItem>): List<CwItem> {
        val now = System.currentTimeMillis()
        val byId = items.associateBy { it.id }.toMutableMap()
        for (f in followed.sortedByDescending { it.lastWatched }.take(60)) {
            val m = try { meta(ctx, "series", f.id) } catch (e: Exception) { null } ?: continue
            // An episode is new once it has aired here: TVmaze's time when saved, else Cinemeta's date.
            fun aired(v: Video) = Coming.airTime(ctx, f.id, v.season, v.episode) ?: v.released
            val fresh = m.videos.filter { aired(it) in (f.lastWatched + 1)..now }
            if (fresh.isEmpty()) continue
            val first = fresh.first()
            val newest = fresh.maxOf { aired(it) }
            val have = byId[f.id]
            byId[f.id] = if (have != null) {
                // Keeps an episode in progress; otherwise points at the first new one.
                have.withNew(fresh.size, if (have.started) null else first.id, newest)
            } else {
                CwItem(f.id, "series", f.name, f.poster, first.id, 0, 0, newest, true, fresh.size)
            }
        }
        val merged = byId.values.sortedByDescending { it.lastWatched }
        Store.writeText(ctx, CW_CACHE, JSONArray(merged.map { it.toJson() }).toString())
        return merged
    }

    /** Warms the cache for the first titles. */
    private fun prefetch(ctx: Context, items: List<CwItem>) {
        for (item in items.take(24)) {
            Net.low {
                val m = meta(ctx, item.type, item.id)
                Images.prefetch(m?.background ?: item.poster)
            }
        }
    }

    // ------------------------------------------------------------ Cinemeta

    fun cachedMeta(id: String): Meta? = metaCache.get(id)

    private fun metaDir(ctx: Context) = java.io.File(ctx.cacheDir, "meta").apply { mkdirs() }

    /**
     * Uses the cached copy while fresh. When stale it refetches, and falls back to the stale
     * copy if the network fails.
     */
    fun meta(ctx: Context, type: String, id: String): Meta? {
        cachedMeta(id)?.let { return it }
        val f = java.io.File(metaDir(ctx), "${id.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")
        val ttl = if (type == "series") SERIES_TTL else MOVIE_TTL
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < ttl
        // A damaged title file is deleted and fetched again.
        val saved = if (f.exists()) try { JSONObject(f.readText()) } catch (e: Exception) { f.delete(); null } else null
        val json = if (fresh && saved != null) saved else try {
            val o = Net.getJson("${CINEMETA}meta/$type/$id.json")
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
            o
        } catch (e: Exception) {
            saved ?: throw e
        }
        val m = json.optJSONObject("meta") ?: return null
        val all = ArrayList<Video>()
        val seen = HashSet<String>()
        m.optJSONArray("videos")?.let { a ->
            for (i in 0 until a.length()) {
                val v = a.optJSONObject(i) ?: continue
                val vid = v.optString("id")
                if (vid.isEmpty() || !seen.add(vid)) continue
                all.add(
                    Video(
                        vid, if (v.has("season")) v.optInt("season") else -1,
                        if (v.has("episode") || v.has("number")) v.optInt("episode", v.optInt("number")) else -1,
                        v.optString("name", v.optString("title")),
                        v.optString("overview", v.optString("description")),
                        parseTime(v.opt("released")),
                    ),
                )
            }
        }
        // Bitfield order: season, then episode, then release date, missing values first.
        all.sortWith(compareBy({ if (it.season < 0) Long.MIN_VALUE else it.season.toLong() },
            { if (it.episode < 0) Long.MIN_VALUE else it.episode.toLong() },
            { if (it.released <= 0) Long.MIN_VALUE else it.released }))
        val videos = all.filter { it.season > 0 && it.episode >= 0 }
        val genres = ArrayList<String>()
        (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.let { a ->
            for (i in 0 until minOf(a.length(), 3)) genres.add(a.optString(i))
        }
        val meta = Meta(
            id, type, m.optString("name", id), m.optString("background").ifEmpty { null },
            m.optString("logo").ifEmpty { null }, m.optString("poster").ifEmpty { null },
            m.optString("description"), m.optString("releaseInfo", m.optString("year")).take(9),
            m.optString("imdbRating"), m.optString("runtime"), genres, videos, all, trailerId(m),
        )
        metaCache.put(id, meta)
        return meta
    }

    /** Removes title files older than a month and the old copies kept in filesDir. */
    fun pruneMeta(ctx: Context) {
        Net.low {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 3600_000
            metaDir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            ctx.filesDir.listFiles()?.forEach { if (it.name.startsWith("meta_")) it.delete() }
        }
    }

    /** Searches movies and series in Cinemeta. */
    fun search(query: String): List<SearchHit> {
        val norm = query.trim().lowercase()
        searchCache.get(norm)?.let { return it }
        val result = searchUncached(query)
        if (result.isNotEmpty()) searchCache.put(norm, result)
        return result
    }

    private var popularCache: Pair<Long, List<SearchHit>>? = null

    /** Cinemeta lists its top titles in pages of this many per type. */
    const val POPULAR_PAGE = 50

    /** One page of Cinemeta's top films and series, mixed. The first page is cached for six hours. */
    fun popular(page: Int = 0): List<SearchHit> {
        if (page == 0) popularCache?.let { (t, list) -> if (System.currentTimeMillis() - t < 6 * 3600_000L) return list }
        val list = searchUncached(null, page * POPULAR_PAGE)
        if (page == 0 && list.isNotEmpty()) popularCache = System.currentTimeMillis() to list
        return list
    }

    /** Searches Cinemeta, or lists its top titles from [skip] when [query] is null. */
    private fun searchUncached(query: String?, skip: Int = 0): List<SearchHit> {
        val path = when {
            query != null -> "/search=" + enc(query.trim())
            skip > 0 -> "/skip=$skip"
            else -> ""
        }
        val cap = if (query == null) POPULAR_PAGE else 10
        val out = ArrayList<SearchHit>()
        for (type in arrayOf("series", "movie")) {
            try {
                val o = Net.getJson("${CINEMETA}catalog/$type/top$path.json")
                val a = o.optJSONArray("metas") ?: continue
                for (i in 0 until minOf(a.length(), cap)) {
                    val m = a.optJSONObject(i) ?: continue
                    out.add(
                        SearchHit(
                            m.optString("id"), type, m.optString("name"),
                            m.optString("poster").ifEmpty { null }, m.optString("releaseInfo"),
                        ),
                    )
                }
            } catch (e: Exception) {
                // One failed catalog still leaves the other.
            }
        }
        // Interleaves results so the strongest match of each type shows first.
        val series = out.filter { it.type == "series" }
        val movies = out.filter { it.type == "movie" }
        val merged = ArrayList<SearchHit>()
        for (i in 0 until maxOf(series.size, movies.size)) {
            series.getOrNull(i)?.let { merged.add(it) }
            movies.getOrNull(i)?.let { merged.add(it) }
        }
        return merged
    }

    fun nextVideo(meta: Meta, item: CwItem): Video? {
        val s = item.season ?: return null
        val e = item.episode ?: return null
        return meta.videos.firstOrNull { it.season > s || (it.season == s && it.episode > e) }
    }

    // ------------------------------------------------------------ library writes

    /**
     * Each library write reads the latest copy of the item, changes only the fields that action
     * changes, and keeps every other field.
     */
    class WriteFailed(msg: String) : Exception(msg)

    private fun fetchItem(ctx: Context, id: String): JSONObject {
        val key = Session.authKey(ctx) ?: throw WriteFailed("Not signed in")
        val body = JSONObject().put("authKey", key).put("collection", "libraryItem")
            .put("ids", JSONArray().put(id)).put("all", false)
        val o = Net.postJson(API + "datastoreGet", body)
        o.optJSONObject("error")?.let { throw WriteFailed(it.optString("message", "Stremio refused the request")) }
        val a = o.optJSONArray("result") ?: throw WriteFailed("No reply from Stremio")
        for (i in 0 until a.length()) {
            val it = a.optJSONObject(i) ?: continue
            if (it.optString("_id") == id) return it
        }
        throw WriteFailed("This title isn't in your Stremio library")
    }

    private fun isoNow(): String = java.time.Instant.now().toString()

    private fun validIso(v: Any?): Boolean =
        v is String && v.length >= 20 && try { java.time.Instant.parse(v); true } catch (e: Exception) { false }

    private fun put(ctx: Context, item: JSONObject) {
        val key = Session.authKey(ctx) ?: throw WriteFailed("Not signed in")
        item.put("_mtime", isoNow())
        // Replaces an invalid creation time with the modified time.
        if (!validIso(item.opt("_ctime"))) item.put("_ctime", item.getString("_mtime"))
        if (item.optString("name").isEmpty() || item.optString("_id").isEmpty() || item.optJSONObject("state") == null) {
            throw WriteFailed("Library item looks incomplete, nothing was changed")
        }
        val body = JSONObject().put("authKey", key).put("collection", "libraryItem")
            .put("changes", JSONArray().put(item))
        val o = Net.postJson(API + "datastorePut", body)
        o.optJSONObject("error")?.let { throw WriteFailed(it.optString("message", "Stremio refused the change")) }
    }

    /** Turns new-episode notifications for a series on or off. */
    fun setNotify(ctx: Context, id: String, on: Boolean) {
        val item = fetchItem(ctx, id)
        item.getJSONObject("state").put("noNotif", !on)
        put(ctx, item)
    }

    /** Adds the title to the library, or removes it. Removing also takes it off Continue watching. */
    fun setInLibrary(ctx: Context, id: String, add: Boolean) {
        val item = fetchItem(ctx, id)
        item.put("removed", !add)
        if (add) item.put("temp", false)
        put(ctx, item)
    }

    /**
     * Marks a film watched, or the current episode of a series watched and moves the series on to
     * the next released episode. Returns a short summary.
     */
    fun markWatched(ctx: Context, cw: CwItem): String {
        val item = fetchItem(ctx, cw.id)
        val st = item.getJSONObject("state")
        if (cw.type != "series") {
            st.put("timesWatched", st.optInt("timesWatched") + 1)
            st.put("lastWatched", isoNow())
            st.put("timeOffset", 0)
            put(ctx, item)
            return "Marked as watched"
        }
        val videoId = cw.videoId ?: (st.opt("video_id") as? String)?.ifEmpty { null } ?: throw WriteFailed("No episode to mark")
        metaCache.remove(cw.id)
        val meta = meta(ctx, "series", cw.id) ?: throw WriteFailed("Couldn't load the episode list")
        val ids = meta.all.map { it.id }
        val bits = Watched.parse((st.opt("watched") as? String)?.ifEmpty { null }, ids)
        Watched.set(bits, ids, videoId)
        st.put("watched", Watched.serialize(bits, ids))
        val video = meta.all.firstOrNull { it.id == videoId }
        val released = video?.released ?: 0L
        val last = parseTime(st.opt("lastWatched"))
        if (released > 0 && (last <= 0 || last < released)) st.put("lastWatched", java.time.Instant.ofEpochMilli(released).toString())
        // Move on past episodes already watched to the first released one that isn't.
        val now = System.currentTimeMillis()
        val order = meta.videos
        val pointer = (st.opt("video_id") as? String)?.ifEmpty { null } ?: videoId
        var at = order.indexOfFirst { it.id == pointer }.let { if (it < 0) order.indexOfFirst { v -> v.id == videoId } else it }
        var summary = "Marked as watched"
        while (Watched.get(bits, ids, pointer)) {
            val next = order.getOrNull(at + 1)
            if (next == null || next.released <= 0 || next.released > now) {
                st.put("timeOffset", 0)
                break
            }
            if (Watched.get(bits, ids, next.id)) { at++; continue }
            st.put("video_id", next.id)
            st.put("overallTimeWatched", st.optLong("overallTimeWatched") + st.optLong("timeWatched"))
            st.put("timeWatched", 0)
            st.put("flaggedWatched", 0)
            st.put("timeOffset", 1)
            summary = "Marked as watched. Up next: S${next.season} E${next.episode}"
            break
        }
        put(ctx, item)
        return summary
    }

    /** Stremio's watched bitfield: "{anchor video}:{anchor length}:{base64 zlib bytes}". */
    private object Watched {
        fun parse(s: String?, ids: List<String>): ByteArray {
            val out = ByteArray((ids.size + 7) / 8)
            if (s == null) return out
            val parts = s.split(':')
            if (parts.size < 3) return out
            val buf = parts.last()
            val len = parts[parts.size - 2].toIntOrNull() ?: return out
            val anchor = parts.subList(0, parts.size - 2).joinToString(":")
            val anchorIdx = ids.indexOf(anchor)
            if (anchorIdx < 0) return out
            val old = inflate(Base64.decode(buf, Base64.DEFAULT))
            val offset = len - anchorIdx - 1
            for (i in ids.indices) {
                val prev = i + offset
                if (prev >= 0 && prev < old.size * 8 && (old[prev / 8].toInt() shr (prev % 8)) and 1 != 0) {
                    out[i / 8] = (out[i / 8].toInt() or (1 shl (i % 8))).toByte()
                }
            }
            return out
        }

        fun get(bits: ByteArray, ids: List<String>, id: String): Boolean {
            val i = ids.indexOf(id)
            return i >= 0 && (bits[i / 8].toInt() shr (i % 8)) and 1 != 0
        }

        fun set(bits: ByteArray, ids: List<String>, id: String) {
            val i = ids.indexOf(id)
            if (i >= 0) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }

        fun serialize(bits: ByteArray, ids: List<String>): String {
            var last = 0
            for (i in ids.indices.reversed()) if ((bits[i / 8].toInt() shr (i % 8)) and 1 != 0) { last = i; break }
            val anchor = ids.getOrNull(last) ?: "undefined"
            val packed = Base64.encodeToString(deflate(bits), Base64.NO_WRAP)
            return "$anchor:${last + 1}:$packed"
        }

        private fun deflate(b: ByteArray): ByteArray {
            val d = java.util.zip.Deflater(6)
            d.setInput(b)
            d.finish()
            val out = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(512)
            while (!d.finished()) out.write(tmp, 0, d.deflate(tmp))
            d.end()
            return out.toByteArray()
        }

        private fun inflate(b: ByteArray): ByteArray {
            val inf = java.util.zip.Inflater()
            inf.setInput(b)
            val out = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(512)
            while (!inf.finished()) {
                val n = inf.inflate(tmp)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(tmp, 0, n)
            }
            inf.end()
            return out.toByteArray()
        }
    }

    // ------------------------------------------------------------ deep links

    /** First trailer's YouTube id, from trailerStreams or the older trailers list. */
    private fun trailerId(m: JSONObject): String? {
        m.optJSONArray("trailerStreams")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("ytId")?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        m.optJSONArray("trailers")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("source")?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /**
     * Plays a trailer in Stremio's own player. The route carries the stream as zlib-deflated JSON
     * in base64, the same form Stremio's Trailer button uses.
     */
    fun trailerIntent(ytId: String, title: String): Intent {
        val json = JSONObject().put("ytId", ytId).put("description", title).toString().toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(json) }
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        return Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///player/" + Uri.encode(b64)))
    }

    fun detailIntent(type: String, id: String, videoId: String? = null): Intent {
        val path = StringBuilder("stremio:///detail/").append(enc(type)).append('/').append(enc(id))
        if (videoId != null) path.append('/').append(enc(videoId))
        return Intent(Intent.ACTION_VIEW, Uri.parse(path.toString()))
            .setPackage(Config.STREMIO)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun parseTime(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            f.parse(v.take(19))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
        else -> 0L
    }

    /** The auth key is encrypted with an Android Keystore AES key before it touches disk. */
    private object Session {
        fun prefs(ctx: Context) = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)

        fun save(ctx: Context, key: String) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secret())
            val data = c.doFinal(key.toByteArray(Charsets.UTF_8))
            prefs(ctx).edit()
                .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .putString("key", Base64.encodeToString(data, Base64.NO_WRAP))
                .apply()
        }

        fun authKey(ctx: Context): String? {
            val p = prefs(ctx)
            val iv = p.getString("iv", null) ?: return null
            val data = p.getString("key", null) ?: return null
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }

        fun clear(ctx: Context) {
            prefs(ctx).edit().clear().apply()
        }

        private fun secret(): SecretKey = KeyBox.secret()
    }
}
