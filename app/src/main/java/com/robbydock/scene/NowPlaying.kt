package com.robbydock.scene

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import java.net.URLEncoder

/**
 * Lets Scene read what media apps are playing. Android only shares active media sessions with an
 * enabled notification listener. Scene reads no notifications.
 */
class MediaListener : NotificationListenerService()

/** What is playing now, from the most active media session. Device-wide. */
object NowPlaying {
    class Song(
        val pkg: String, val app: String, val title: String, val artist: String, val album: String,
        var art: Bitmap?, val duration: Long,
    )

    var song: Song? = null
        private set
    /** 0 nothing, 1 playing, 2 paused. */
    var state = 0
        private set
    private var position = 0L
    private var positionAt = 0L
    private var speed = 1f
    private var controller: MediaController? = null
    private var manager: MediaSessionManager? = null
    private var appCtx: Context? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    // ---------------------------------------------------------------- settings

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
    fun showOnHome(ctx: Context) = prefs(ctx).getBoolean("npHome", true)
    fun saver(ctx: Context) = prefs(ctx).getBoolean("npSaver", true)
    fun lyrics(ctx: Context) = prefs(ctx).getBoolean("npLyrics", true)
    fun viz(ctx: Context) = prefs(ctx).getBoolean("npViz", true)
    fun set(ctx: Context, key: String, on: Boolean) { prefs(ctx).edit().putBoolean(key, on).apply(); changed() }

    fun component(ctx: Context) = ComponentName(ctx, MediaListener::class.java)

    /** True when notification access is allowed, which Android needs before sharing media sessions. */
    fun hasAccess(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
    }

    // ---------------------------------------------------------------- sessions

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) = read()
        override fun onMetadataChanged(m: MediaMetadata?) = read()
        override fun onSessionDestroyed() { controller = null; manager?.let { m -> appCtx?.let { pick(sessions(m, it)) } } }
    }

    private fun sessions(m: MediaSessionManager, ctx: Context): List<MediaController> =
        try { m.getActiveSessions(component(ctx)) } catch (e: SecurityException) { emptyList() }

    /** Starts watching media sessions. Safe to call again, for example after access is granted. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (!hasAccess(app)) { drop(); return }
        val m = manager ?: app.getSystemService(MediaSessionManager::class.java) ?: return
        if (manager == null) {
            try { m.addOnActiveSessionsChangedListener(sessionsChanged, component(app), Net.main) } catch (e: SecurityException) { return }
            manager = m
        }
        pick(sessions(m, app))
    }

    private fun drop() {
        controller?.unregisterCallback(callback)
        controller = null
        if (song != null || state != 0) { song = null; state = 0; changed() }
    }

    /** Follows the playing session, or the first paused one. */
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, Net.main)
        }
        read()
    }

    private fun read() {
        val c = controller
        val m = c?.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c == null || m == null || title.isNullOrBlank()) { drop(); return }
        val ps = c.playbackState
        state = when (ps?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> 1
            PlaybackState.STATE_PAUSED -> 2
            else -> 0
        }
        position = ps?.position ?: 0L
        positionAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val app = appCtx?.let { Apps.label(it.packageManager, c.packageName) } ?: c.packageName
        val old = song
        val same = old != null && old.pkg == c.packageName && old.title == title && old.artist == artist
        val s = Song(c.packageName, app, title, artist, m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "", art ?: old?.art?.takeIf { same },
            m.getLong(MediaMetadata.METADATA_KEY_DURATION))
        song = s
        // Some players share the cover only as a web address.
        if (art == null && !same) {
            val uri = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                .firstNotNullOfOrNull { k -> m.getString(k)?.takeIf { it.startsWith("https://") } }
            if (uri != null) Images.load(uri, 512, opaque = false) { b -> if (b != null && song === s) { s.art = b; changed() } }
        }
        changed()
    }

    /** Playback position in milliseconds, moving while playing. */
    fun positionMs(): Long {
        val p = if (state == 1) position + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong() else position
        val d = song?.duration ?: 0L
        return if (d > 0) p.coerceIn(0L, d) else p.coerceAtLeast(0L)
    }

    fun playPause() { controller?.transportControls?.let { if (state == 1) it.pause() else it.play() } }
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    /** Opens the player: the session's own screen, or the app. */
    fun open(act: HomeActivity) {
        val c = controller ?: return
        try { c.sessionActivity?.send(); return } catch (e: Exception) { }
        act.launch(c.packageName)
    }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners.toList()) l() }
}

/** Synced lyrics from LRCLIB. Sends only the song name, artist, album and length. */
object Lyrics {
    class Line(val ms: Long, val text: String)

    private val cache = LinkedHashMap<String, List<Line>?>()
    private val pending = HashSet<String>()

    private fun key(s: NowPlaying.Song) = "${s.artist}|${s.title}"

    /** Cached lines, or null while loading or when there are none. [done] runs once loaded. */
    fun get(s: NowPlaying.Song, done: () -> Unit): List<Line>? {
        val k = key(s)
        if (cache.containsKey(k)) return cache[k]
        if (pending.add(k)) Net.run({ fetch(s) }) { lines ->
            pending.remove(k)
            cache[k] = lines
            while (cache.size > 20) cache.remove(cache.keys.first())
            done()
        }
        return null
    }

    /** True once the lookup finished. */
    fun known(s: NowPlaying.Song) = cache.containsKey(key(s))

    private fun fetch(s: NowPlaying.Song): List<Line>? {
        fun q(v: String) = URLEncoder.encode(v, "UTF-8")
        var url = "https://lrclib.net/api/get?track_name=${q(s.title)}&artist_name=${q(s.artist)}"
        if (s.album.isNotEmpty()) url += "&album_name=${q(s.album)}"
        if (s.duration > 0) url += "&duration=${s.duration / 1000}"
        val text = Net.getJson(url).optString("syncedLyrics").ifEmpty { return null }
        val re = Regex("""^\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)$""")
        return text.lineSequence().mapNotNull { l ->
            val m = re.find(l.trim()) ?: return@mapNotNull null
            val words = m.groupValues[3].trim()
            if (words.isEmpty()) null else Line((m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toDouble() * 1000).toLong(), words)
        }.take(400).toList().takeIf { it.isNotEmpty() }
    }

    /** Index of the line at [ms], or -1 before the first. */
    fun at(lines: List<Line>, ms: Long): Int {
        var i = -1
        for ((k, l) in lines.withIndex()) if (l.ms <= ms) i = k else break
        return i
    }
}

/**
 * Bar heights from the TV's sound output, through Android's Visualizer. Needs the microphone
 * permission. [heights] stays flat when the Visualizer is not available.
 */
class SoundBars(val count: Int) {
    val heights = FloatArray(count)
    private val target = FloatArray(count)
    private var viz: Visualizer? = null
    var available = false
        private set

    fun start(ctx: Context) {
        if (viz != null) return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) bands(fft) }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            viz = v
            available = true
        } catch (e: Exception) {
            available = false
        }
    }

    fun stop() {
        try { viz?.enabled = false; viz?.release() } catch (e: Exception) { }
        viz = null
    }

    /** Groups the spectrum into [count] bands, low notes wider apart. */
    private fun bands(fft: ByteArray) {
        val n = fft.size / 2
        for (b in 0 until count) {
            val from = (Math.pow(b.toDouble() / count, 2.0) * (n - 2)).toInt() + 1
            val to = (Math.pow((b + 1.0) / count, 2.0) * (n - 2)).toInt().coerceAtLeast(from + 1)
            var peak = 0.0
            for (i in from until to) {
                val re = fft[2 * i].toDouble()
                val im = fft[2 * i + 1].toDouble()
                peak = maxOf(peak, Math.hypot(re, im))
            }
            target[b] = (Math.log10(1 + peak) / 2.2).toFloat().coerceIn(0f, 1f)
        }
    }

    /** Eases each bar towards its level. Call once per frame. */
    fun step(playing: Boolean) {
        for (k in 0 until count) {
            val t = if (playing && available) target[k] else 0.04f
            heights[k] += (t - heights[k]) * 0.25f
        }
    }
}
