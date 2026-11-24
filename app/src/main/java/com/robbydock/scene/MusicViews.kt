package com.robbydock.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Real songs with their covers, shipped with Scene, for the Settings preview when nothing is playing. */
object SampleSongs {
    private class Entry(val title: String, val artist: String, val album: String, val ms: Long) {
        /** Cover address from the iTunes search, kept once found. */
        var cover: String? = null
    }

    private val list = listOf(
        Entry("Midnight City", "M83", "Hurry Up, We're Dreaming", 243_960L),
        Entry("Get Lucky", "Daft Punk", "Random Access Memories", 369_629L),
        Entry("Dreams", "Fleetwood Mac", "Rumours", 257_800L),
        Entry("Electric Feel", "MGMT", "Oracular Spectacular", 229_640L),
        Entry("Heat Waves", "Glass Animals", "Dreamland", 238_805L),
        Entry("Redbone", "Childish Gambino", "\"Awaken, My Love!\"", 379_227L),
        Entry("Mr. Brightside", "The Killers", "Hot Fuss", 222_973L),
    )
    private var last = -1

    /**
     * A different song from the last one picked. The cover is not shipped with the app: it is
     * looked up online, and [artReady] runs when it arrives. Offline, the song keeps the plain
     * gradient cover.
     */
    fun pick(artReady: (NowPlaying.Song) -> Unit): NowPlaying.Song {
        var i: Int
        do i = (list.indices).random() while (i == last && list.size > 1)
        last = i
        val e = list[i]
        val song = NowPlaying.Song("", "Sample", e.title, e.artist, e.album, null, e.ms)
        val show = { url: String ->
            Images.load(url, Theme.ipx(320f)) { b -> if (b != null) { song.art = b; artReady(song) } }
        }
        val known = e.cover
        if (known != null) show(known)
        else Net.run({ coverUrl(e) }) { url -> if (url != null) { e.cover = url; show(url) } }
        return song
    }

    private fun coverUrl(e: Entry): String? {
        val term = java.net.URLEncoder.encode("${e.title} ${e.artist}", "UTF-8")
        val r = Net.getJson("https://itunes.apple.com/search?entity=song&limit=1&term=$term").optJSONArray("results")?.optJSONObject(0)
        return r?.optString("artworkUrl100")?.takeIf { it.isNotEmpty() }?.replace("100x100bb", "320x320bb")
    }
}

/**
 * Music screensaver: blurred cover behind, the cover and song on the left, synced lyrics on the
 * right and a visualizer along the foot. Drawn on one canvas at 1920 by 1080 design units.
 * [compact] draws the small Settings preview instead.
 */
class MusicSaver(ctx: Context, private val compact: Boolean = false) : View(ctx) {
    private val bars = SoundBars(if (compact) 36 else 40)
    private var lines: List<Lyrics.Line>? = null
    private var lyricsFor: NowPlaying.Song? = null
    private var art: Bitmap? = null
    private var blur: Bitmap? = null
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val box = RectF()
    private val white = Color.WHITE
    private fun p(size: Float, a: Float, face: android.graphics.Typeface) = textPaint(size, Theme.withAlpha(white, a), face)
    private val appP = p(if (compact) 18f else 22f, 0.7f, Theme.SEMIBOLD).apply { letterSpacing = 0.12f }
    private val titleP = p(if (compact) 32f else 44f, 1f, Theme.BOLD)
    private val artistP = p(if (compact) 22f else 26f, 0.7f, Theme.REGULAR)
    private val timeP = p(20f, 0.6f, Theme.REGULAR).apply { fontFeatureSettings = "tnum" }
    private val clockP = p(56f, 0.85f, Theme.LIGHT).apply { fontFeatureSettings = "tnum" }
    private val lineOn = p(if (compact) 24f else 58f, 1f, Theme.SEMIBOLD)
    private val emptyT = p(if (compact) 26f else 36f, 0.9f, Theme.SEMIBOLD)
    private val emptyS = p(if (compact) 20f else 24f, 0.55f, Theme.REGULAR)
    private val chipT = p(22f, 0.7f, Theme.MEDIUM)
    private val note by lazy { ctx.icon(R.drawable.ic_music_note, Theme.withAlpha(white, 0.7f)) }
    private val frame = object : Runnable {
        override fun run() {
            invalidate()
            // The preview holds still while it waits for notification access.
            if (compact && !NowPlaying.hasAccess(context)) return
            postDelayed(this, if (Theme.motion == 2) 1000L else 33L)
        }
    }
    private val changed = { refresh() }
    /** The lyric line shown now and the one before it, for the 400 ms move between them. */
    private var lyricIdx = -1
    private var lyricFrom = -1
    private var lyricAt = 0L
    private val lyricP = p(40f, 1f, Theme.SEMIBOLD)
    private val born = android.os.SystemClock.uptimeMillis()
    /** Shown in the Settings preview while nothing plays. Declared before init, which reads it. */
    private val sample: NowPlaying.Song by lazy { SampleSongs.pick { if (song() === it) post { refresh() } } }
    private val sampleAt = System.currentTimeMillis()

    init { if (!compact) setBackgroundColor(Color.BLACK); refresh() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NowPlaying.listen(changed)
        if (NowPlaying.viz(context)) bars.start(context)
        post(frame)
        if (!compact && Theme.motion != 2) { alpha = 0f; animate().alpha(1f).setDuration(600L).start() }
    }

    override fun onDetachedFromWindow() {
        NowPlaying.unlisten(changed)
        removeCallbacks(frame)
        bars.stop()
        super.onDetachedFromWindow()
    }

    private fun song(): NowPlaying.Song? = NowPlaying.song ?: if (compact) sample else null
    private fun isSample(s: NowPlaying.Song?) = compact && s != null && s.pkg.isEmpty()

    /** Picks up a new song: its art, a small blurred copy, and its lyrics. */
    private fun refresh() {
        val s = song()
        if (s?.art !== srcArt || (art != null && blur == null)) {
            srcArt = s?.art
            art = srcArt?.let { squareArt(it) }
            blur = art?.let { Bitmap.createScaledBitmap(it, 12, 12, true) }
        }
        if (s != lyricsFor) {
            lyricsFor = s
            lines = if (s != null && NowPlaying.lyrics(context)) Lyrics.get(s) { if (lyricsFor === s) { lines = Lyrics.get(s) {}; invalidate() } } else null
        }
        invalidate()
    }

    private fun x(v: Float) = Theme.px(v)

    /** The art as sent, before [squareArt]. */
    private var srcArt: Bitmap? = null

    /**
     * Video apps send a 16:9 frame, often padded with black bars. Trims the bars, then crops the
     * middle to a square so the cover is never stretched.
     */
    private fun squareArt(src: Bitmap): Bitmap = try {
        val b = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val w = b.width
        val h = b.height
        fun dark(px: Int) = maxOf(Color.red(px), Color.green(px), Color.blue(px)) < 24
        fun rowDark(y: Int) = (0 until 16).all { dark(b.getPixel(it * (w - 1) / 15, y)) }
        fun colDark(x: Int) = (0 until 16).all { dark(b.getPixel(x, it * (h - 1) / 15)) }
        var top = 0; while (top < h / 3 && rowDark(top)) top++
        var bottom = h - 1; while (bottom > h * 2 / 3 && rowDark(bottom)) bottom--
        var left = 0; while (left < w / 3 && colDark(left)) left++
        var right = w - 1; while (right > w * 2 / 3 && colDark(right)) right--
        val cw = right - left + 1
        val ch = bottom - top + 1
        val side = minOf(cw, ch)
        if (side == w && side == h) b
        else Bitmap.createBitmap(b, left + (cw - side) / 2, top + (ch - side) / 2, side, side)
    } catch (e: Exception) { src }

    override fun onDraw(c: Canvas) {
        val s = song()
        val w = width.toFloat()
        val h = height.toFloat()
        val demo = isSample(s)
        val playing = NowPlaying.state == 1 || demo
        // The sample starts a little way in, so a lyric line shows at once.
        val pos = if (demo) (40_000L + System.currentTimeMillis() - sampleAt) % s!!.duration else NowPlaying.positionMs()
        if (compact) { clip.reset(); clip.addRoundRect(0f, 0f, w, h, x(48f), x(48f), Path.Direction.CW); c.save(); c.clipPath(clip); c.drawColor(Color.BLACK) }
        // Blurred cover, then shade so the text reads.
        blur?.let { b ->
            bmp.alpha = 205
            val side = maxOf(w, h) + x(160f)
            box.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
            // Drifts over 40 s and back: up to 115% and a little down and left.
            val d = if (compact || Theme.motion != 0) 0f else {
                val t = ((android.os.SystemClock.uptimeMillis() - born) % 80_000L) / 40_000f
                val k = if (t > 1f) 2f - t else t
                (0.5f - 0.5f * kotlin.math.cos(Math.PI * k)).toFloat()
            }
            c.save()
            c.translate(-0.03f * w * d, 0.02f * h * d)
            c.scale(1f + 0.15f * d, 1f + 0.15f * d, w / 2, h / 2)
            c.drawBitmap(b, null, box, bmp)
            c.restore()
            bmp.alpha = 255
        } ?: run {
            fill.shader = LinearGradient(0f, 0f, w, h, Theme.mix(Theme.accent, Color.BLACK, 0.55f), Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        }
        fill.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x8C000000.toInt(), 0x40000000, 0x80000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        if (s == null) {
            empty(c, w / 2, h / 2, R.drawable.ic_music_note, "Nothing is playing", "Play something in Spotify or another app")
        } else if (compact) drawCompact(c, s, w, h, playing, pos) else {
            // The 16:9 layout stays centred on wider or taller screens.
            c.save()
            c.translate(((w - x(1920f)) / 2).coerceAtLeast(0f), ((h - x(1080f)) / 2).coerceAtLeast(0f))
            drawFull(c, s, x(1920f), x(1080f), playing, pos)
            c.restore()
        }
        if (compact) {
            val note = when {
                !NowPlaying.hasAccess(context) -> Triple(R.drawable.ic_shield, "Waiting for notification access", "Allow it below to see what's playing")
                !NowPlaying.saver(context) -> Triple(R.drawable.ic_lyrics, "Your screensaver plays instead", "Turn on Music Screensaver to use this")
                else -> null
            }
            if (note != null) {
                fill.color = 0xB3000000.toInt(); c.drawRect(0f, 0f, w, h, fill)
                empty(c, w / 2, h / 2, note.first, note.second, note.third)
            }
            c.restore()
        }
    }

    /** Empty state: an icon in a soft glass circle, a short title and one quiet line, centred on [cx], [cy]. */
    private fun empty(c: Canvas, cx: Float, cy: Float, icon: Int, title: String, sub: String) {
        val d = x(if (compact) 72f else 120f)
        val top = cy - (d + x(if (compact) 76f else 112f)) / 2
        fill.color = Theme.withAlpha(white, 0.10f)
        c.drawCircle(cx, top + d / 2, d / 2, fill)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = x(1.5f); fill.color = Theme.withAlpha(white, 0.14f)
        c.drawCircle(cx, top + d / 2, d / 2, fill)
        fill.style = Paint.Style.FILL
        val g = context.frameIcon(icon, Theme.withAlpha(white, 0.85f))
        val gs = (d * 0.44f).toInt()
        g.setBounds((cx - gs / 2).toInt(), (top + d / 2 - gs / 2).toInt(), (cx + gs / 2).toInt(), (top + d / 2 + gs / 2).toInt())
        g.draw(c)
        val ty = top + d + x(if (compact) 18f else 32f)
        c.drawText(title, cx - emptyT.measureText(title) / 2, ty - emptyT.ascent(), emptyT)
        val sy = ty + x(if (compact) 36f else 52f)
        c.drawText(sub, cx - emptyS.measureText(sub) / 2, sy - emptyS.ascent(), emptyS)
    }

    /** Quiet pill with the lyrics icon, for a note above the visualizer. */
    private fun chip(c: Canvas, l: Float, t: Float, text: String) {
        val h = x(48f)
        val gs = x(26f)
        val w = x(20f) + gs + x(12f) + chipT.measureText(text) + x(24f)
        fill.color = Theme.withAlpha(white, 0.10f)
        c.drawRoundRect(l, t, l + w, t + h, h / 2, h / 2, fill)
        val g = context.frameIcon(R.drawable.ic_lyrics, Theme.withAlpha(white, 0.7f))
        g.setBounds((l + x(20f)).toInt(), (t + (h - gs) / 2).toInt(), (l + x(20f) + gs).toInt(), (t + (h + gs) / 2).toInt())
        g.draw(c)
        c.drawText(text, l + x(20f) + gs + x(12f), baseline(chipT, t + h / 2), chipT)
    }

    private fun cover(c: Canvas, l: Float, t: Float, size: Float, r: Float) {
        box.set(l, t, l + size, t + size)
        if (!compact) {
            fill.color = Color.BLACK
            fill.setShadowLayer(x(100f), 0f, x(40f), Theme.withAlpha(Color.BLACK, 0.6f))
            c.drawRoundRect(box, r, r, fill)
            fill.clearShadowLayer()
        }
        val a = art
        c.save()
        clip.reset(); clip.addRoundRect(box, r, r, Path.Direction.CW); c.clipPath(clip)
        if (a != null) c.drawBitmap(a, null, box, bmp) else {
            fill.shader = LinearGradient(l, t, l + size, t + size, Theme.accent, Theme.duo(Theme.accent), Shader.TileMode.CLAMP)
            c.drawRect(box, fill); fill.shader = null
            val g = context.frameIcon(R.drawable.ic_music_note, Theme.withAlpha(white, 0.8f))
            val gs = (size * 0.4f).toInt()
            g.setBounds((box.centerX() - gs / 2).toInt(), (box.centerY() - gs / 2).toInt(), (box.centerX() + gs / 2).toInt(), (box.centerY() + gs / 2).toInt())
            g.draw(c)
        }
        c.restore()
        if (!compact) {
            fill.style = Paint.Style.STROKE; fill.strokeWidth = x(1f); fill.color = Theme.withAlpha(white, 0.08f)
            c.drawRoundRect(box, r, r, fill)
            fill.style = Paint.Style.FILL
        }
    }

    /** Top of the lyric slot [o] lines from the current one: the current line is taller. */
    private fun slotY(o: Float): Float {
        val k = o.coerceIn(-3f, 3f)
        return x(444f) + if (k <= 0f) k * x(72f) else x(96f) + (k - 1f) * x(72f)
    }

    /** Size, brightness and glow for a line [o] slots from the current one. */
    private fun lineLook(o: Float, out: FloatArray) {
        val a = kotlin.math.abs(o).coerceAtMost(3f)
        out[0] = if (a < 1f) 58f + (40f - 58f) * a else 40f
        out[1] = when {
            a < 1f -> 1f + (0.32f - 1f) * a
            a < 2f -> 0.32f + (0.16f - 0.32f) * (a - 1f)
            else -> 0.16f * (3f - a)
        }
        out[2] = (1f - a).coerceAtLeast(0f)
    }
    private val look = FloatArray(3)

    /** Five lines around the current one. A new line slides up and grows over 400 ms. */
    private fun drawLyrics(c: Canvas, ls: List<Lyrics.Line>, pos: Long, lx: Float, lw: Float) {
        val i = Lyrics.at(ls, pos)
        val now = android.os.SystemClock.uptimeMillis()
        if (i != lyricIdx) {
            // Only a step to the next line moves; a seek jumps.
            lyricFrom = if (i == lyricIdx + 1 && Theme.motion != 2) lyricIdx else i
            lyricIdx = i
            lyricAt = now
        }
        val t = Theme.EASE.getInterpolation(((now - lyricAt) / 400f).coerceIn(0f, 1f))
        fun offset(k: Int) = (k - lyricFrom) + ((k - i) - (k - lyricFrom)) * t
        // Space below a line: wider while it is the large one, so lines never crowd it.
        fun pitch(o: Float) = x(72f) + x(24f) * (1f - kotlin.math.abs(o)).coerceAtLeast(0f)
        // The current line rides between slots; the others stack from it.
        val ys = FloatArray(7)
        ys[3] = slotY(offset(i))
        for (j in 4..6) ys[j] = ys[j - 1] + pitch(offset(i + j - 4))
        for (j in 2 downTo 0) ys[j] = ys[j + 1] - pitch(offset(i + j - 3))
        for (j in 0..6) {
            val k = i + j - 3
            val l = ls.getOrNull(k) ?: continue
            lineLook(offset(k), look)
            if (look[1] <= 0.01f) continue
            lyricP.textSize = x(look[0])
            lyricP.color = Theme.withAlpha(white, look[1])
            if (look[2] > 0f) lyricP.setShadowLayer(x(30f), 0f, x(4f), Theme.withAlpha(Color.BLACK, 0.4f * look[2])) else lyricP.clearShadowLayer()
            c.drawText(lyricP.fit(l.text, lw), lx, ys[j] - lyricP.ascent(), lyricP)
        }
    }

    /** The part of [title] that fits [width], broken at a space, and what is left. */
    private fun splitTitle(title: String, width: Float): Pair<String, String> {
        if (titleP.measureText(title) <= width) return title to ""
        val n = titleP.breakText(title, true, width, null)
        val cut = title.lastIndexOf(' ', n).takeIf { it > 0 } ?: n
        return title.substring(0, cut).trimEnd() to title.substring(cut).trimStart()
    }

    private fun drawFull(c: Canvas, s: NowPlaying.Song, w: Float, h: Float, playing: Boolean, pos: Long) {
        cover(c, x(160f), x(200f), x(480f), x(32f))
        val tx = x(160f)
        val tw = x(480f)
        var y = x(720f)
        val ns = x(26f).toInt()
        note.setBounds(tx.toInt(), (y + x(2f)).toInt(), tx.toInt() + ns, (y + x(2f)).toInt() + ns)
        note.draw(c)
        c.drawText(s.app.uppercase(), tx + ns + x(10f), y - appP.ascent(), appP)
        y += x(44f)
        // A long title wraps onto a second line at a word break.
        val (first, rest) = splitTitle(s.title, tw)
        c.drawText(first, tx, y - titleP.ascent(), titleP)
        if (rest.isNotEmpty()) { y += x(54f); c.drawText(titleP.fit(rest, tw), tx, y - titleP.ascent(), titleP) }
        y += x(58f)
        c.drawText(artistP.fit(listOf(s.artist, s.album).filter { it.isNotEmpty() }.joinToString("  ·  "), tw), tx, y - artistP.ascent(), artistP)
        y += x(56f)
        if (s.duration > 0) {
            fill.color = Theme.withAlpha(white, 0.18f)
            c.drawRoundRect(tx, y, tx + tw, y + x(6f), x(3f), x(3f), fill)
            fill.color = white
            c.drawRoundRect(tx, y, tx + tw * (pos.toFloat() / s.duration).coerceIn(0f, 1f), y + x(6f), x(3f), x(3f), fill)
            y += x(16f)
            c.drawText(time(pos), tx, y - timeP.ascent(), timeP)
            val left = "-" + time(s.duration - pos)
            c.drawText(left, tx + tw - timeP.measureText(left), y - timeP.ascent(), timeP)
        }
        // Lyrics: the current line large, two either side fading out.
        val lx = x(780f)
        val lw = x(980f)
        val ls = lines
        val showLyrics = NowPlaying.lyrics(context) && ls != null
        val viz = NowPlaying.viz(context)
        val noneFound = NowPlaying.lyrics(context) && ls == null && lyricsFor?.let { Lyrics.known(it) } == true
        if (showLyrics && ls != null) drawLyrics(c, ls, pos, lx, lw)
        if (viz) {
            bars.step(playing)
            // Without lyrics the visualizer grows into their space.
            if (showLyrics) drawBars(c, lx, x(780f), lw, x(160f), x(8f)) else drawBars(c, lx, x(300f), lw, x(600f), x(12f))
        }
        if (noneFound) {
            if (viz) chip(c, lx, x(220f), "No synced lyrics for this song")
            else empty(c, lx + lw / 2, x(480f), R.drawable.ic_lyrics, "No lyrics for this song", "LRCLIB has no synced lyrics for it yet")
        }
        val t = clockText()
        c.drawText(t, w - x(96f) - clockP.measureText(t), x(72f) - clockP.ascent(), clockP)
    }

    // Drawn every frame while the screensaver moves, so the format is kept.
    private var clockFmt: SimpleDateFormat? = null
    private var clockFmt24 = false
    private var clockLocale: Locale? = null
    private fun clockText(): String {
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        val loc = Locale.getDefault()
        if (clockFmt == null || is24 != clockFmt24 || loc != clockLocale) {
            clockFmt = SimpleDateFormat(if (is24) "H:mm" else "h:mm", loc); clockFmt24 = is24; clockLocale = loc
        }
        // Follows a time zone change, as a new format would.
        return clockFmt!!.apply { timeZone = java.util.TimeZone.getDefault() }.format(Date())
    }

    private fun drawCompact(c: Canvas, s: NowPlaying.Song, w: Float, h: Float, playing: Boolean, pos: Long) {
        val cs = x(150f)
        val cx = x(40f)
        val cy = x(36f)
        cover(c, cx, cy, cs, x(20f))
        val tx = cx + cs + x(32f)
        val tw = w - tx - x(40f)
        c.drawText(s.app.uppercase(), tx, cy + x(18f), appP)
        c.drawText(titleP.fit(s.title, tw), tx, cy + x(56f), titleP)
        c.drawText(artistP.fit(if (s.album.isNotEmpty()) "${s.artist}  ·  ${s.album}" else s.artist, tw), tx, cy + x(86f), artistP)
        val ls = lines
        if (NowPlaying.lyrics(context)) {
            val text = if (ls != null) ls.getOrNull(Lyrics.at(ls, pos))?.text else null
            if (text != null) c.drawText(lineOn.fit(text, tw), tx, cy + x(134f), lineOn)
        }
        if (NowPlaying.viz(context)) {
            if (isSample(s) || !bars.available) simulate(pos) else bars.step(playing)
            drawBars(c, cx - x(4f), cy + cs + x(20f), w - 2 * cx + x(8f), h - cy - cs - x(44f), x(8f))
        }
    }

    /** A gentle stand-in beat for the sample song, as in the HTML mock. */
    private fun simulate(posMs: Long) {
        val p = posMs / 1000.0
        val beat = Math.pow(1 - (p * 2 % 1), 3.0)
        for (k in 0 until bars.count) {
            val band = k.toDouble() / bars.count
            val t = 0.18 + 0.5 * Math.abs(Math.sin(p * 3.1 + k * 0.7)) * (1 - band * 0.6) + beat * (if (band < 0.2) 0.7 else 0.25)
            bars.heights[k] += ((t - bars.heights[k]) * 0.25).toFloat()
        }
    }

    private fun drawBars(c: Canvas, l: Float, t: Float, w: Float, h: Float, gap: Float) {
        val n = bars.count
        val bw = w / n
        val acc = Theme.accent
        val duo = Theme.duo(acc)
        for (k in 0 until n) {
            val bh = maxOf(gap * 0.75f, bars.heights[k].coerceIn(0f, 1.2f) * h * 0.94f)
            fill.shader = LinearGradient(0f, t + h - bh, 0f, t + h, acc, Theme.withAlpha(duo, 0.2f), Shader.TileMode.CLAMP)
            c.drawRoundRect(l + k * bw + gap / 2, t + h - bh, l + (k + 1) * bw - gap / 2, t + h, gap / 2, gap / 2, fill)
        }
        fill.shader = null
    }

    private fun time(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
    }
}

/** Full-screen music screensaver over Home. Any key closes it. */
class MusicSaverScreen(act: HomeActivity) : OverlayBase(act, 1f) {
    init {
        addView(MusicSaver(act), LayoutParams(-1, -1))
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP) (context as HomeActivity).pop()
        return true
    }
}

/**
 * Now Playing in Home's top bar: the cover turning, the song and a play button. OK plays or
 * pauses; holding OK opens the options.
 */
class NowPlayingPill(ctx: Context) : FocusView(ctx) {
    private val title = textPaint(26f, Theme.TEXT, Theme.SEMIBOLD)
    private val artist = textPaint(21f, Theme.TEXT_DIM)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val disc = Path()
    private val artBox = RectF()
    private var btnShader: android.graphics.Shader? = null
    private var btnX = 0f
    private var btnD = 0f
    private var btnH = 0f
    private var btnAccent = 0
    private val pause = ctx.icon(R.drawable.ic_pause, Theme.onAccent)
    private val play = ctx.icon(R.drawable.ic_play_arrow, Theme.onAccent)
    private val start = android.os.SystemClock.uptimeMillis()
    private val spin = object : Runnable {
        override fun run() { invalidate(); if (NowPlaying.state == 1 && Theme.motion != 2) postDelayed(this, 50L) }
    }
    override val focusScale = 1.04f
    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, height, height / 2f)

    /** The clock and weather pill this one covers. Its time and temperature stay in view, small, beside the song. */
    var info: InfoPill? = null
    private val brief = textPaint(24f, Theme.TEXT_DIM, Theme.SEMIBOLD).apply { fontFeatureSettings = "tnum" }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minute = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Glass.register(this); post(minute) }
    override fun onDetachedFromWindow() { Glass.unregister(this); removeCallbacks(spin); removeCallbacks(minute); super.onDetachedFromWindow() }

    /** Width of the time and temperature part, in pixels, with its leading divider. Zero when both are off. */
    private fun briefWidth(): Float {
        val i = info ?: return 0f
        val wx = i.briefWeather()
        val tm = i.briefTime()
        if (wx == null && tm == null) return 0f
        var w = Theme.px(16f + 2f + 16f)
        if (wx != null) w += Theme.px(30f + 8f) + brief.measureText(wx.second)
        if (wx != null && tm != null) w += Theme.px(12f + 2f + 12f)
        if (tm != null) w += brief.measureText(tm)
        return w
    }

    /** Width that fits the song, up to 460 design units, plus the time and temperature. */
    fun fitWidth(): Float {
        val s = NowPlaying.song ?: return 0f
        val tw = maxOf(title.measureText(s.title), artist.measureText(s.artist)) / Theme.u
        return (12f + 64f + 16f + tw + 16f + 56f + 16f).coerceAtMost(460f) + briefWidth() / Theme.u
    }

    fun update() { removeCallbacks(spin); post(spin); invalidate() }

    override fun onDraw(c: Canvas) {
        val s = NowPlaying.song ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        box.set(0f, 0f, w, h)
        Glass.draw(c, this, box, h / 2)
        val u = Theme.u
        val ad = 64f * u
        val ax = 12f * u
        val ay = (h - ad) / 2
        c.save()
        disc.reset(); disc.addCircle(ax + ad / 2, ay + ad / 2, ad / 2, Path.Direction.CW); c.clipPath(disc)
        if (NowPlaying.state == 1 && Theme.motion != 2) c.rotate(((android.os.SystemClock.uptimeMillis() - start) % 12_000L) * 0.03f, ax + ad / 2, ay + ad / 2)
        val a = s.art
        if (a != null) { artBox.set(ax, ay, ax + ad, ay + ad); c.drawBitmap(a, null, artBox, bmp) }
        else { Theme.accentFill(fill, ax, ay, ax + ad, ay + ad); c.drawRect(ax, ay, ax + ad, ay + ad, fill); fill.shader = null }
        c.restore()
        val bd = 56f * u
        val bx = w - 16f * u - bd
        val tx = ax + ad + 16f * u
        val bw = briefWidth()
        val tw = bx - 16f * u - bw - tx
        if (bw > 0f) drawBrief(c, bx - 16f * u - bw, h)
        c.drawText(title.fit(s.title, tw), tx, h / 2 - 4f * u, title)
        c.drawText(artist.fit(s.artist, tw), tx, h / 2 - 4f * u - artist.ascent() + 2f * u, artist)
        // The button's gradient only changes with its place or the accent; the pill redraws every frame while the cover turns.
        if (btnShader == null || btnX != bx || btnD != bd || btnH != h || btnAccent != Theme.accent) {
            btnX = bx; btnD = bd; btnH = h; btnAccent = Theme.accent
            Theme.accentFill(fill, bx, (h - bd) / 2, bx + bd, (h + bd) / 2); btnShader = fill.shader
        }
        fill.shader = btnShader
        c.drawCircle(bx + bd / 2, h / 2, bd / 2, fill)
        fill.shader = null
        val ic = if (NowPlaying.state == 1) pause else play
        val isz = (32f * u).toInt()
        ic.setBounds((bx + (bd - isz) / 2).toInt(), ((h - isz) / 2).toInt(), (bx + (bd + isz) / 2).toInt(), ((h + isz) / 2).toInt())
        ic.draw(c)
        drawRing(c, h / 2)
    }

    /** Divider, weather icon and temperature, a short divider, then the time, from [x0]. */
    private fun drawBrief(c: Canvas, x0: Float, h: Float) {
        val i = info ?: return
        val u = Theme.u
        line.color = Theme.withAlpha(Theme.TEXT, 0.16f)
        var x = x0 + 16f * u
        c.drawRect(x, h / 2 - 22f * u, x + 2f * u, h / 2 + 22f * u, line)
        x += 2f * u + 16f * u
        val wx = i.briefWeather()
        val tm = i.briefTime()
        val y = baseline(brief, h / 2)
        if (wx != null) {
            val s = (30f * u).toInt()
            wx.first.setBounds(x.toInt(), ((h - s) / 2).toInt(), x.toInt() + s, ((h + s) / 2).toInt())
            wx.first.draw(c)
            x += s + 8f * u
            c.drawText(wx.second, x, y, brief)
            x += brief.measureText(wx.second)
        }
        if (wx != null && tm != null) {
            x += 12f * u
            c.drawRect(x, h / 2 - 14f * u, x + 2f * u, h / 2 + 14f * u, line)
            x += 2f * u + 12f * u
        }
        if (tm != null) c.drawText(tm, x, y, brief)
    }
}
