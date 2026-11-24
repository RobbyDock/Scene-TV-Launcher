package com.robbydock.scene

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Newly popular: films, series and anime released in the last 3, 6 or 12 months. Films and series
 * come from Cinemeta's year catalogues, anime from Kitsu's seasons, each sorted by popularity. Each
 * kind keeps its most popular share and sorts it by rating, unrated last. Then the kinds take turns
 * in the row, so no kind crowds the others out. Kitsu lists each new season as its own title, so
 * sequels stay out and only new anime show, like the year catalogues. Titles in the Stremio library,
 * saved or watched, stay out when the profile asks for unseen titles only. Anime match by id or name,
 * since the library may hold them under Cinemeta ids. It shares one Home row with Coming up.
 */
object NewlyPopular {
    /** [kind] is movie, series or anime. [film] marks an anime film. Anime ratings are Kitsu's, out of 10. [names] are anime title keys. */
    class Title(val id: String, val type: String, val name: String, val poster: String?, val released: Long, val rating: Float, val pop: Double,
                val kind: String = type, val film: Boolean = false, val names: Set<String> = emptySet()) {
        val anime get() = kind == ANIME
        /** Short label for the kind: Film, Series, Anime or Anime film. */
        val label get() = if (anime) (if (film) "Anime film" else "Anime") else if (type == "series") "Series" else "Film"
    }

    val MONTHS = intArrayOf(3, 6, 12)
    val WINDOW_NAMES = MONTHS.map { "Last $it months" }
    val WINDOW_SUBS = listOf("Titles from the last 3 months", "Titles from the last 6 months", "A year of releases")
    const val ANIME = "anime"
    /** Kinds in turn order, with their bit in Config.newKinds. */
    val KINDS = listOf("movie", "series", ANIME)
    val KIND_NAMES = listOf("Films", "Series", "Anime")
    val KIND_SUBS = listOf("Films from Cinemeta", "Series from Cinemeta", "Series and films from Kitsu")
    private val SEASONS = listOf("winter", "spring", "summer", "fall")
    /** Share of each kind's most popular titles that goes on to the rating sort, and the least kept. */
    private const val SHARE = 0.3
    private const val MIN_KEPT = 15
    /** Titles the row shows. */
    const val ROW = 20
    private const val CINEMETA = "https://v3-cinemeta.strem.io/"
    private const val KITSU_API = "https://kitsu.app/api/edge/"
    private const val FRESH = 12 * 60 * 60 * 1000L

    /** The row's list once loaded, before unseen filtering; null while not known. */
    @Volatile var ready: List<Title>? = null
        private set
    private var loading = false
    private var generation = 0
    /** Window and kinds [ready] was loaded for. Another profile's choice loads afresh. */
    private var readyKey = ""
    private val pages = HashMap<String, Pair<Long, List<Title>>>()
    private val lookups = Executors.newFixedThreadPool(3) { r -> Thread(r, "scene-newpop").apply { isDaemon = true } }

    /** Poster link for [t]. Anime asks the Kitsu addon, which may block, so call it off the main thread. */
    fun poster(ctx: android.content.Context, t: Title): String? =
        t.poster ?: if (t.anime) Coming.kitsuMeta(ctx, t.id)?.optString("poster")?.ifEmpty { null }
        else "https://images.metahub.space/poster/medium/${t.id}/img"

    /** Kinds on in [mask], in turn order. */
    fun kinds(mask: Int) = KINDS.filterIndexed { i, _ -> mask and (1 shl i) != 0 }

    /** Value for the Show setting: All, or the kinds on. */
    fun kindsText(mask: Int): String {
        val on = KIND_NAMES.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
        return if (on.size == KINDS.size) "All" else on.joinToString(", ")
    }

    /** True when the profile has the row on. Kids profiles never show it. */
    fun on(act: HomeActivity) = act.cfg.newOn && !Profiles.current.kids

    /** Loads the list once for the profile's window and kind, then calls [done] on the main thread. */
    fun load(act: HomeActivity, done: () -> Unit) {
        if (loading || ready != null) return
        loading = true
        val gen = generation
        val months = MONTHS.getOrElse(act.cfg.newMonths) { 6 }
        val kinds = act.cfg.newKinds
        Net.run({ list(months, kinds) }) { list ->
            if (gen != generation) return@run
            loading = false
            ready = list ?: emptyList()
            done()
        }
    }

    /** The list for this profile's window and kind, or null while it loads. */
    fun current(act: HomeActivity): List<Title>? {
        val k = "${act.cfg.newMonths}:${act.cfg.newKinds}"
        if (k != readyKey) { reset(); readyKey = k }
        return ready
    }

    /** Drops the list after the window or kind changes. */
    fun reset() {
        generation++
        loading = false
        ready = null
    }

    /** [list] without titles the profile has seen, when it asks for unseen titles only. */
    fun visible(act: HomeActivity, list: List<Title>): List<Title> {
        if (!act.cfg.newUnseen) return list
        val seen = seenIds(act)
        // Filter each kind on its own, then take turns again, so the mix stays even.
        return takeTurns(KINDS.map { k -> list.filter { it.kind == k && it.id !in seen && it.names.none { n -> NAME + n in seen } } })
    }

    /** Merges [lists] one item from each in turn. */
    private fun takeTurns(lists: List<List<Title>>): List<Title> {
        val out = ArrayList<Title>()
        var i = 0
        while (lists.any { i < it.size }) { for (l in lists) if (i < l.size) out.add(l[i]); i++ }
        return out
    }

    /** Prefix for title keys in the seen set. */
    const val NAME = "name:"

    /** Title reduced to letters and digits, for matching across catalogues. */
    fun nameKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Ids and title keys in the library, saved or watched. Demo Mode uses its own titles. */
    private fun seenIds(act: HomeActivity): Set<String> =
        if (Demo.on) (act.continueItems.flatMap { listOf(it.id, NAME + nameKey(it.name)) } +
            Demo.shelf("yours", 0, 0, 1000).flatMap { listOf(it.id, NAME + nameKey(it.name)) }).toSet()
        else Stremio.seenIds

    /** Titles released in the last [months] for the kinds in [mask]: each kind best rated first, then taking turns. */
    fun list(months: Int, mask: Int): List<Title> {
        val now = System.currentTimeMillis()
        val from = Calendar.getInstance().apply { add(Calendar.MONTH, -months) }
        val years = listOf(from.get(Calendar.YEAR), Calendar.getInstance().get(Calendar.YEAR)).distinct()
        val start = from.timeInMillis
        val jobs = kinds(mask).map { k ->
            if (k == ANIME) seasonsSince(from).flatMap { (y, season) -> listOf(0, 20, 40, 60).map { o -> lookups.submit(Callable { animePage(y, season, o) }) } }
            else years.flatMap { y -> listOf(0, 50, 100).map { s -> lookups.submit(Callable { page(k, y, s) }) } }
        }
        val lists = jobs.map { pages ->
            val fresh = pages.flatMap { try { it.get() } catch (e: Exception) { emptyList() } }
                .filter { it.released in start..now }.distinctBy { it.id }.sortedByDescending { it.pop }
            // The most popular share, best rated first.
            fresh.take(maxOf(MIN_KEPT, Math.ceil(fresh.size * SHARE).toInt()))
                .sortedWith(compareByDescending<Title> { it.rating > 0f }.thenByDescending { it.rating }.thenByDescending { it.pop })
        }
        // The kinds take turns, so each gets the same share of the row.
        return takeTurns(lists)
    }

    /** Kitsu seasons that overlap the window from [from] to now, as year and season. */
    private fun seasonsSince(from: Calendar): List<Pair<Int, String>> {
        val d = Calendar.getInstance().apply { clear(); set(from.get(Calendar.YEAR), from.get(Calendar.MONTH) / 3 * 3, 1) }
        val now = Calendar.getInstance()
        val out = ArrayList<Pair<Int, String>>()
        while (!d.after(now)) { out.add(d.get(Calendar.YEAR) to SEASONS[d.get(Calendar.MONTH) / 3]); d.add(Calendar.MONTH, 3) }
        return out
    }

    /** One page of a Kitsu season, most watched first, kept for 12 hours. Adult titles and sequels stay out. */
    private fun animePage(year: Int, season: String, offset: Int): List<Title> {
        val key = "kitsu:$year:$season:$offset"
        synchronized(pages) { pages[key]?.let { if (System.currentTimeMillis() - it.first < FRESH) return it.second } }
        val url = KITSU_API + "anime?filter%5BseasonYear%5D=$year&filter%5Bseason%5D=$season&sort=-userCount" +
            "&page%5Blimit%5D=20&page%5Boffset%5D=$offset" +
            "&fields%5Banime%5D=canonicalTitle,titles,startDate,averageRating,userCount,subtype,ageRating,nsfw,mediaRelationships" +
            "&include=mediaRelationships&fields%5BmediaRelationships%5D=role"
        val json = Net.getJson(url)
        val arr = json.optJSONArray("data") ?: return emptyList()
        // A title with a prequel or a parent story is a later season or a side story.
        val later = HashSet<String>()
        json.optJSONArray("included")?.let { inc ->
            for (i in 0 until inc.length()) inc.optJSONObject(i)?.let { r ->
                if (r.optJSONObject("attributes")?.optString("role") in setOf("prequel", "parent_story")) later.add(r.optString("id"))
            }
        }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = ArrayList<Title>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val a = x.optJSONObject("attributes") ?: continue
            if (a.optBoolean("nsfw") || a.optString("ageRating") == "R18") continue
            val links = x.optJSONObject("relationships")?.optJSONObject("mediaRelationships")?.optJSONArray("data")
            if (links != null && (0 until links.length()).any { links.optJSONObject(it)?.optString("id") in later }) continue
            val released = try { day.parse(a.optString("startDate"))?.time ?: 0L } catch (e: Exception) { 0L }
            if (released <= 0) continue
            val film = a.optString("subtype") == "movie"
            val titles = a.optJSONObject("titles")
            val name = titles?.optString("en")?.ifEmpty { null } ?: a.optString("canonicalTitle")
            val names = (listOf(name, a.optString("canonicalTitle")) + (titles?.keys()?.asSequence()?.map { titles.optString(it) }?.toList() ?: emptyList()))
                .map { nameKey(it) }.filter { it.isNotEmpty() }.toSet()
            val rating = a.optString("averageRating").toFloatOrNull()?.let { Math.round(it) / 10f } ?: 0f
            // Kitsu's own poster links expire, so Home asks the Kitsu addon for one.
            out.add(Title("kitsu:" + x.optString("id"), if (film) "movie" else "series", name, null, released, rating,
                a.optDouble("userCount", 0.0), ANIME, film, names))
        }
        synchronized(pages) { pages[key] = System.currentTimeMillis() to out }
        return out
    }

    /** One page of a year catalogue, kept for 12 hours. Shorts and trailers stay out. */
    private fun page(type: String, year: Int, skip: Int): List<Title> {
        val key = "$type:$year:$skip"
        synchronized(pages) { pages[key]?.let { if (System.currentTimeMillis() - it.first < FRESH) return it.second } }
        val url = CINEMETA + "catalog/$type/year/genre=$year" + (if (skip > 0) "&skip=$skip" else "") + ".json"
        val arr = Net.getJson(url).optJSONArray("metas") ?: return emptyList()
        val out = ArrayList<Title>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id").ifEmpty { m.optString("imdb_id") }
            if (!id.startsWith("tt")) continue
            val genres = m.optJSONArray("genres")
            if (genres != null && (0 until genres.length()).any { genres.optString(it) == "Short" }) continue
            val released = Stremio.parseTime(m.opt("released"))
            if (released <= 0) continue
            val p = m.optJSONObject("popularities")
            val pop = p?.optDouble("moviedb")?.takeIf { !it.isNaN() } ?: p?.optDouble("stremio")?.takeIf { !it.isNaN() } ?: 0.0
            out.add(Title(id, type, m.optString("name", id), m.optString("poster").ifEmpty { null }, released,
                m.optString("imdbRating").toFloatOrNull() ?: 0f, pop))
        }
        synchronized(pages) { pages[key] = System.currentTimeMillis() to out }
        return out
    }
}
