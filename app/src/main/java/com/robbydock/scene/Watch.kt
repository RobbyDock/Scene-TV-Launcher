package com.robbydock.scene

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The app Scene watches with. Only one is used at a time. */
enum class Source(val key: String, val label: String, val pkg: String, val icon: Int) {
    STREMIO("stremio", "Stremio", Config.STREMIO, R.drawable.ic_stremio),
    JELLYFIN("jellyfin", "Jellyfin", "org.jellyfin.androidtv", R.drawable.ic_jellyfin),
    PLEX("plex", "Plex", "com.plexapp.android", R.drawable.ic_plex),
}

/** A row of the library on Home, or a tab of the Library screen. */
class Shelf(val key: String, val title: String)

/** The Android Keystore AES key that encrypts every sign-in token before it touches disk. */
internal object KeyBox {
    private const val ALIAS = "scene_stremio_session"

    fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}

/** Encrypted tokens for Jellyfin and Plex, one entry per [name]. */
internal object Vault {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun put(ctx: Context, name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, KeyBox.secret())
        val data = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit()
            .putString("$name.iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("$name.data", Base64.encodeToString(data, Base64.NO_WRAP))
            .apply()
    }

    fun get(ctx: Context, name: String): String? {
        val p = prefs(ctx)
        val iv = p.getString("$name.iv", null) ?: return null
        val data = p.getString("$name.data", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, KeyBox.secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(ctx: Context, name: String) {
        prefs(ctx).edit().remove("$name.iv").remove("$name.data").apply()
    }
}

/**
 * Sends each call to the app chosen in Watch With. Continue watching, the Spotlight, Search and
 * the library all come from it. Demo Mode always uses Stremio's sample data.
 */
object Watch {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun source(ctx: Context): Source {
        if (Demo.on) return Source.STREMIO
        val k = prefs(ctx).getString("source", Source.STREMIO.key)
        return Source.values().firstOrNull { it.key == k } ?: Source.STREMIO
    }

    fun name(ctx: Context) = source(ctx).label
    fun isStremio(ctx: Context) = source(ctx) == Source.STREMIO
    fun appInstalled(ctx: Context) = Apps.isInstalled(ctx.packageManager, source(ctx).pkg)

    /**
     * Makes [to] the app Scene watches with, and signs out of the others. Called once [to] is
     * signed in, so a cancelled sign-in keeps the old app.
     */
    fun switchTo(ctx: Context, to: Source) {
        prefs(ctx).edit().putString("source", to.key).apply()
        if (to != Source.STREMIO) Stremio.signOut(ctx)
        if (to != Source.JELLYFIN) Jellyfin.signOut(ctx)
        if (to != Source.PLEX) Plex.signOut(ctx)
    }

    // ---------------------------------------------------------------- account

    fun signedIn(ctx: Context) = when (source(ctx)) {
        Source.STREMIO -> Stremio.signedIn(ctx)
        Source.JELLYFIN -> Jellyfin.signedIn(ctx)
        Source.PLEX -> Plex.signedIn(ctx)
    }

    /** The account line: Stremio's email, or the user and server. */
    fun account(ctx: Context): String? = when (source(ctx)) {
        Source.STREMIO -> Stremio.email(ctx)
        Source.JELLYFIN -> Jellyfin.account(ctx)
        Source.PLEX -> Plex.account(ctx)
    }

    /** Signs out of Jellyfin or Plex and goes back to Stremio; signs out of Stremio otherwise. */
    fun signOut(ctx: Context) {
        setSignedOutByApp(ctx, false)
        when (source(ctx)) {
            Source.STREMIO -> Stremio.signOut(ctx)
            Source.JELLYFIN -> { Jellyfin.signOut(ctx); prefs(ctx).edit().putString("source", Source.STREMIO.key).apply() }
            Source.PLEX -> { Plex.signOut(ctx); prefs(ctx).edit().putString("source", Source.STREMIO.key).apply() }
        }
    }

    // ---------------------------------------------------------------- continue watching

    fun cachedContinue(ctx: Context, online: Boolean): List<CwItem> = when (source(ctx)) {
        Source.STREMIO -> Stremio.cachedContinue(ctx, online)
        Source.JELLYFIN -> readContinue(ctx, Jellyfin.CW_CACHE, online)
        Source.PLEX -> readContinue(ctx, Plex.CW_CACHE, online)
    }

    /**
     * Fetches Continue watching. A server that turns Scene's sign-in down marks [signedOutByApp],
     * so Home can say so and offer to reconnect instead of "couldn't reach". A good fetch clears it.
     */
    fun fetchContinue(ctx: Context): List<CwItem> = try {
        when (source(ctx)) {
            Source.STREMIO -> Stremio.fetchContinue(ctx)
            Source.JELLYFIN -> Jellyfin.fetchContinue(ctx).also { saveContinue(ctx, Jellyfin.CW_CACHE, it) }
            Source.PLEX -> Plex.fetchContinue(ctx).also { saveContinue(ctx, Plex.CW_CACHE, it) }
        }.also { setSignedOutByApp(ctx, false) }
    } catch (e: Stremio.SessionExpired) {
        setSignedOutByApp(ctx, true); throw e
    } catch (e: Net.HttpError) {
        if (e.code == 401 || e.code == 403) setSignedOutByApp(ctx, true)
        throw e
    }

    /** True when the app ended Scene's sign-in, rather than the user signing out. */
    fun signedOutByApp(ctx: Context) = prefs(ctx).getBoolean("signedOutByApp", false)
    fun setSignedOutByApp(ctx: Context, on: Boolean) { if (signedOutByApp(ctx) != on) prefs(ctx).edit().putBoolean("signedOutByApp", on).apply() }

    /** Stremio has a cheap change check; the others refresh on a timer instead. */
    fun libraryStamp(ctx: Context): Int? = if (isStremio(ctx)) Stremio.libraryStamp(ctx) else null

    fun withNewEpisodes(ctx: Context, items: List<CwItem>): List<CwItem> =
        if (isStremio(ctx)) Stremio.withNewEpisodes(ctx, items) else items

    private fun readContinue(ctx: Context, file: String, online: Boolean): List<CwItem> {
        val f = java.io.File(ctx.filesDir, file)
        if (!f.exists()) return emptyList()
        val age = System.currentTimeMillis() - f.lastModified()
        val text = Store.readText(ctx, file) ?: return emptyList()
        return try {
            val a = JSONArray(text)
            (0 until a.length()).map { CwItem.fromJson(a.getJSONObject(it)) }.filter { CacheAge.keepCard(age, online, it.started) }
        } catch (e: Exception) {
            Store.delete(ctx, file); emptyList()
        }
    }

    private fun saveContinue(ctx: Context, file: String, list: List<CwItem>) {
        Store.writeText(ctx, file, JSONArray(list.map { it.toJson() }).toString())
    }

    // ---------------------------------------------------------------- titles

    private fun of(id: String) = when {
        id.startsWith(Jellyfin.PREFIX) -> Source.JELLYFIN
        id.startsWith(Plex.PREFIX) -> Source.PLEX
        else -> Source.STREMIO
    }

    fun cachedMeta(id: String): Meta? = when (of(id)) {
        Source.STREMIO -> Stremio.cachedMeta(id)
        Source.JELLYFIN -> Jellyfin.cachedMeta(id)
        Source.PLEX -> Plex.cachedMeta(id)
    }

    fun meta(ctx: Context, type: String, id: String): Meta? = when (of(id)) {
        Source.STREMIO -> Stremio.meta(ctx, type, id)
        Source.JELLYFIN -> Jellyfin.meta(ctx, type, id)
        Source.PLEX -> Plex.meta(ctx, type, id)
    }

    fun nextVideo(meta: Meta, item: CwItem) = Stremio.nextVideo(meta, item)

    fun search(ctx: Context, query: String): List<SearchHit> = when (source(ctx)) {
        Source.STREMIO -> Stremio.search(query)
        Source.JELLYFIN -> Jellyfin.search(ctx, query)
        Source.PLEX -> Plex.search(ctx, query)
    }

    /** Stremio's popular titles, or what was added to the server last. */
    fun popular(ctx: Context, page: Int = 0): List<SearchHit> = when (source(ctx)) {
        Source.STREMIO -> Stremio.popular(page)
        Source.JELLYFIN -> if (page == 0) Jellyfin.shelf(ctx, "recent", 0, 0, 40) else emptyList()
        Source.PLEX -> if (page == 0) Plex.shelf(ctx, "recent", 0, 0, 40) else emptyList()
    }

    fun popularTitle(ctx: Context) = if (isStremio(ctx)) "Popular on Stremio" else "Recently added to ${name(ctx)}"

    // ---------------------------------------------------------------- library

    /** The Home rows this app offers. Stremio has one: the titles saved in its library. */
    fun shelves(ctx: Context): List<Shelf> =
        if (isStremio(ctx)) listOf(Shelf("yours", "Your library"))
        else listOf(Shelf("recent", "Recently added"), Shelf("movies", "Movies"), Shelf("shows", "Shows"))

    /** The Library screen's tabs. */
    fun tabs(ctx: Context): List<Shelf> =
        if (isStremio(ctx)) listOf(Shelf("films", "Films"), Shelf("series", "Series"))
        else listOf(Shelf("movies", "Movies"), Shelf("shows", "Shows"))

    fun sorts(ctx: Context): List<String> =
        if (isStremio(ctx)) listOf("Recently added", "A to Z") else listOf("Recently added", "A to Z", "Release year", "Unwatched")

    /** Titles on [key], sorted by [sort] (an index into [sorts]), from [start]. */
    fun shelf(ctx: Context, key: String, sort: Int, start: Int, count: Int): List<SearchHit> = when (source(ctx)) {
        Source.STREMIO -> { Stremio.loadSaved(ctx); Stremio.saved(key, sort, start, count) }
        Source.JELLYFIN -> Jellyfin.shelf(ctx, key, sort, start, count)
        Source.PLEX -> Plex.shelf(ctx, key, sort, start, count)
    }

    // ---------------------------------------------------------------- changes

    class WriteFailed(msg: String) : Exception(msg)

    fun markWatched(ctx: Context, item: CwItem): String = when (of(item.id)) {
        Source.STREMIO -> Stremio.markWatched(ctx, item)
        Source.JELLYFIN -> Jellyfin.markWatched(ctx, item)
        Source.PLEX -> Plex.markWatched(ctx, item)
    }

    /** False for Plex, whose Watchlist lives outside the server. */
    fun hasLibrary(ctx: Context) = source(ctx) != Source.PLEX

    fun setInLibrary(ctx: Context, item: CwItem, add: Boolean) = when (of(item.id)) {
        Source.STREMIO -> Stremio.setInLibrary(ctx, item.id, add)
        Source.JELLYFIN -> Jellyfin.setFavourite(ctx, item.id, add)
        Source.PLEX -> throw WriteFailed("Plex keeps its Watchlist online")
    }

    /** What "library" is called in the app: Stremio's library, or Jellyfin's favourites. */
    fun libraryWords(ctx: Context): Triple<String, String, String> = when (source(ctx)) {
        Source.JELLYFIN -> Triple("Add to favourites", "Remove from favourites", "your Jellyfin favourites")
        Source.PLEX -> Triple("Add to Watchlist", "Remove from Watchlist", "your Plex Watchlist")
        Source.STREMIO -> Triple("Add to library", "Remove from library", "your Stremio library")
    }

    /** Opens the title in its app: details, or the episode given. */
    fun openIntent(ctx: Context, type: String, id: String, videoId: String?): Intent = when (of(id)) {
        Source.STREMIO -> Stremio.detailIntent(type, id, videoId)
        Source.JELLYFIN -> Jellyfin.openIntent(id, videoId)
        Source.PLEX -> Plex.openIntent(ctx, id, videoId)
    }
}
