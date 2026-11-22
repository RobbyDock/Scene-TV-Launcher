package com.robbydock.scene

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The Watch With app's library: rows on Home between Continue watching and the apps, and a
 * full Library screen behind See all. Part of the spotlight, and on unless turned off.
 */
object Library {
    /** Home rows, by shelf key. Cleared when the app changes. */
    private val rows = HashMap<String, List<SearchHit>>()
    private val loading = HashSet<String>()
    /** Empty answers so far, by shelf key. */
    private val misses = HashMap<String, Int>()
    /** Waits before asking again after an empty answer. */
    private val RETRY_MS = longArrayOf(3_000L, 10_000L, 30_000L)

    fun clear() { rows.clear(); loading.clear(); misses.clear() }

    /** On unless turned off. Part of the spotlight, so it shows only with it. */
    fun onHome(act: HomeActivity): Boolean = act.cfg.libraryHome != 0

    /** The rows Home shows now. */
    fun shown(act: HomeActivity): List<Shelf> =
        if (!act.spotlightOn || !onHome(act) || !Watch.signedIn(act) || Demo.on) emptyList()
        else Watch.shelves(act).filter { it.key !in act.cfg.libraryOff }

    fun cached(key: String): List<SearchHit>? = rows[key]

    /**
     * Loads a Home row; [done] runs when it arrives and differs from what is shown. An empty or
     * failed answer is asked again a few times, then kept as empty so the row goes away.
     */
    fun load(act: HomeActivity, key: String, done: () -> Unit) {
        if (!loading.add(key)) return
        val source = Watch.source(act)
        Net.run({ Watch.shelf(act, key, 0, 0, HomeScreen.ROW_TITLES + 4) }) { list ->
            loading.remove(key)
            if (source != Watch.source(act)) return@run
            if (list.isNullOrEmpty()) { missed(act, key, done); return@run }
            misses.remove(key)
            if (rows[key]?.map { it.id } == list.map { it.id }) return@run
            rows[key] = list
            done()
        }
    }

    /** Stremio's saved titles come with the library fetch, so a miss there fetches it again. */
    private fun missed(act: HomeActivity, key: String, done: () -> Unit) {
        val n = misses[key] ?: 0
        if (n >= RETRY_MS.size) {
            if (rows[key] == null) { rows[key] = emptyList(); done() }
            return
        }
        misses[key] = n + 1
        if (n == 0 && Watch.isStremio(act) && !Stremio.libraryLoaded) act.refreshContinue(force = true)
        Net.main.postDelayed({ if (!act.isDestroyed && rows[key] == null) load(act, key, done) }, RETRY_MS[n])
    }

    /**
     * Fetches the Home rows again after Continue watching refreshes. Stremio's saved titles arrive
     * with Continue watching, so a row built before then was empty and needs this second look.
     */
    fun refresh(act: HomeActivity) {
        misses.clear()
        for (s in shown(act)) load(act, s.key) { act.home.rebuild() }
    }

    /** Fills a poster card: title, year, watched tick, progress and art. */
    fun bind(card: PosterCard, h: SearchHit) {
        card.title = h.name
        card.year = h.year
        card.watched = h.watched
        card.progress = h.progress
        card.loading = true
        Images.load(posterUrl(h), Theme.ipx(260f)) { b -> card.loading = false; card.art = b }
    }

    /** The title's poster, or Metahub's by IMDb id when the library entry has none. */
    fun posterUrl(h: SearchHit): String? =
        h.poster?.takeIf { it.isNotEmpty() } ?: if (h.id.startsWith("tt")) "https://images.metahub.space/poster/medium/${h.id}/img" else null

    /** Long-press menu for a library title. */
    fun menu(act: HomeActivity, h: SearchHit) {
        val app = Watch.name(act)
        val series = h.type == "series"
        val a = listOf(
            Sheet.Action("Play", R.drawable.ic_play_arrow, "In $app") { act.openDetail(h.type, h.id) },
            Sheet.Action(if (series) "Episode list" else "Details", if (series) R.drawable.ic_list else R.drawable.ic_info, "In $app") { act.openDetail(h.type, h.id) },
        )
        act.push(Sheet(act, h.name, listOf(if (series) "Series" else "Film", h.year).filter { it.isNotEmpty() }.joinToString("  ·  "), null, a, posterUrl = h.poster ?: ""))
    }
}

/**
 * Full library: tabs for films and series, a sort, and a grid of posters with watched ticks and
 * progress. More titles load as focus nears the end. The title and pills slide away once the grid
 * scrolls, so posters get the whole screen, and come back on the top row.
 */
@SuppressLint("ViewConstructor")
class LibraryScreen(act: HomeActivity, private var tab: Int = 0) : OverlayBase(act, 1f), HomeActivity.Closable {
    private val tabs = Watch.tabs(act)
    private val sorts = Watch.sorts(act)
    private var sort = 0
    /** Only for an empty or unreachable library. */
    private val note = act.label("", 28f, Theme.TEXT_DIM)
    private val header = FrameLayout(act).apply { clipChildren = false }
    private var collapsed = false
    private val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
    private val scroller = ScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(Theme.ipx(60f))
    }
    private val grid = FrameLayout(act).apply { clipChildren = false }
    private val shown = ArrayList<SearchHit>()
    private var busy = false
    private var ended = false
    private var gen = 0
    private var closed = false
    /** As many columns as the screen holds between the header's margins, posters widened to fill the rest. */
    private val cols = ((Theme.W - 2 * EDGE + GX) / (PW + GX)).toInt().coerceAtLeast(4)
    private val pw = (Theme.W - 2 * EDGE - (cols - 1) * GX) / cols
    private val ph = pw * PH / PW

    private companion object {
        const val PW = 200f
        const val PH = 300f
        const val GX = 40f
        const val GY = 44f
        /** Side margin, the same as the title and pills. */
        const val EDGE = 96f
        const val PAGE = 70
        /** Where the first row starts, under the header. */
        const val TOP = 268f
    }

    init {
        // The grid goes in first and is clipped to its own area, so posters scroll under the header, never over it.
        scroller.addView(grid, LayoutParams(-1, -2))
        place(act.clipBox(scroller), 0f, 0f, Theme.W, Theme.H)
        place(note, 96f, TOP + 40f, 1700f, 40f)
        // The header sits over the grid on a fade from the background, so passing posters stay behind it.
        val scrim = View(act).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Theme.bg, Theme.bg, Theme.withAlpha(Theme.bg, 0f)))
        }
        header.place(scrim, 0f, 0f, Theme.W, TOP + 20f)
        val src = Watch.source(act)
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(android.widget.ImageView(act).apply { setImageResource(src.icon) }, LinearLayout.LayoutParams(Theme.ipx(56f), Theme.ipx(56f)).apply { rightMargin = Theme.ipx(22f) })
        head.addView(act.label("Library", 60f, Theme.TEXT, Theme.BOLD), LinearLayout.LayoutParams(-2, -2))
        header.place(head, 96f, 48f, 1700f, 90f)
        header.place(bar, 96f, 150f, 1700f, 80f)
        place(header, 0f, 0f, Theme.W, TOP + 20f)
        scroller.setOnScrollChangeListener { _, _, y, _, _ -> collapse(y > Theme.px(48f)) }
        buildBar()
        fill()
    }

    /** Slides the header away while the grid is scrolled, and back at the top. */
    private fun collapse(on: Boolean) {
        if (on == collapsed) return
        collapsed = on
        val ms = Theme.MEDIUM_MS
        header.animate().cancel()
        header.animate().alpha(if (on) 0f else 1f).translationY(if (on) -Theme.px(60f) else 0f).setDuration(ms)
            .setInterpolator(Theme.EASE).start()
        // Hidden pills can't take focus; Up from the top row brings them back.
        for (i in 0 until bar.childCount) bar.getChildAt(i).isFocusable = !on
    }

    private fun buildBar() {
        bar.removeAllViews()
        for ((i, t) in tabs.withIndex()) {
            val b = PillButton(act, t.title, if (i == 0) R.drawable.ic_movie else R.drawable.ic_tv, i == tab)
            b.setOnClickListener { if (tab != i) { tab = i; buildBar(); fill(); bar.getChildAt(i)?.requestFocus() } }
            bar.addView(b, LinearLayout.LayoutParams(-2, Theme.ipx(80f)).apply { rightMargin = Theme.ipx(20f) })
        }
        val s = PillButton(act, sorts[sort], R.drawable.ic_sort, false)
        s.setOnClickListener { sort = (sort + 1) % sorts.size; buildBar(); fill(); bar.getChildAt(tabs.size)?.requestFocus() }
        bar.addView(s, LinearLayout.LayoutParams(-2, Theme.ipx(80f)))
        post { if (findFocus() == null) bar.getChildAt(tab)?.requestFocus() }
    }

    private fun fill() {
        gen++
        shown.clear()
        grid.removeAllViews()
        ended = false
        busy = false
        scroller.scrollTo(0, 0)
        collapse(false)
        note.text = ""
        more()
    }

    /** Loads the next page and adds its posters. */
    private fun more() {
        if (busy || ended) return
        busy = true
        val g = gen
        val key = tabs[tab].key
        val start = shown.size
        Net.run({ Watch.shelf(act, key, sort, start, PAGE) }) { list ->
            if (closed || g != gen) return@run
            busy = false
            if (list == null) { if (shown.isEmpty()) note.text = "Couldn't reach ${Watch.name(act)}"; return@run }
            if (list.size < PAGE) ended = true
            for (h in list) add(h)
            note.text = if (shown.isEmpty()) "Nothing here yet" else ""
        }
    }

    private fun add(h: SearchHit) {
        val i = shown.size
        shown.add(h)
        val card = PosterCard(act).apply {
            Library.bind(this, h)
            setOnClickListener { act.openDetail(h.type, h.id) }
            setOnLongClickListener { Library.menu(act, h); true }
            setOnFocusChangeListener { _, has ->
                if (!has) return@setOnFocusChangeListener
                if (i >= shown.size - cols * 2) more()
                // The top row shows the header again.
                if (i < cols) scroller.smoothScrollTo(0, 0)
            }
            // Up from the top row goes to the pills, even while they slide back in.
            if (i < cols) setOnKeyListener { _, code, e ->
                if (code == android.view.KeyEvent.KEYCODE_DPAD_UP && e.action == android.view.KeyEvent.ACTION_DOWN) {
                    collapse(false); bar.getChildAt(tab)?.requestFocus(); true
                } else false
            }
        }
        grid.addView(card, LayoutParams(Theme.ipx(pw), Theme.ipx(ph)).apply {
            leftMargin = Theme.ipx(EDGE + (i % cols) * (pw + GX))
            topMargin = Theme.ipx(TOP + (i / cols) * (ph + GY))
        })
        val rowsNow = (shown.size + cols - 1) / cols
        grid.minimumHeight = Theme.ipx(TOP + rowsNow * (ph + GY) + 120f)
        Motion.pop(card, (i % cols) * 18L)
    }

    override fun onClosed() { closed = true }
}

/**
 * Last card of a library row: a dim 2 by 2 collage of the titles that come next, with See all and
 * an accent arrow over it.
 */
class SeeAllCard(ctx: android.content.Context) : FocusView(ctx) {
    private val art = arrayOfNulls<Bitmap>(4)
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = textPaint(30f, Theme.TEXT, Theme.BOLD)
    private val sub = textPaint(20f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val clip = android.graphics.Path()
    private val cell = RectF()
    private val arrow = ctx.icon(R.drawable.ic_chevron_right, Theme.onAccent)
    /** Line under See all, such as the row's name. */
    var line = ""
    override val restZ = 8f
    override val hotZ = 30f
    override fun shadowShape(o: android.graphics.Outline) = o.setRoundRect(0, 0, width, height, Theme.px(Theme.R_CHIP))

    /** Loads up to four posters for the collage. */
    fun posters(list: List<SearchHit>) {
        for ((i, h) in list.take(4).withIndex()) Images.load(Library.posterUrl(h), Theme.ipx(110f)) { b -> art[i] = b; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), Theme.px(Theme.R_CHIP), Theme.px(Theme.R_CHIP), android.graphics.Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.save()
        c.clipPath(clip)
        fill.color = Theme.elev
        c.drawRect(0f, 0f, w, h, fill)
        for (i in 0 until 4) {
            val b = art[i] ?: continue
            cell.set((i % 2) * w / 2, (i / 2) * h / 2, (i % 2 + 1) * w / 2, (i / 2 + 1) * h / 2)
            c.drawBitmap(b, null, cell, bmp)
        }
        // Dims the collage so the label reads, a little less on focus.
        fill.color = Theme.withAlpha(Theme.bg, if (hot()) 0.62f else 0.74f)
        c.drawRect(0f, 0f, w, h, fill)
        val cx = w / 2
        val cy = h * 0.42f
        val rr = Theme.px(34f)
        Theme.accentFill(fill, cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawCircle(cx, cy, rr, fill)
        fill.shader = null
        val s = Theme.ipx(40f)
        arrow.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        arrow.draw(c)
        val t = "See all"
        c.drawText(t, cx - title.measureText(t) / 2, cy + rr + Theme.px(48f), title)
        val l = sub.fit(line, w - Theme.px(24f))
        c.drawText(l, cx - sub.measureText(l) / 2, cy + rr + Theme.px(80f), sub)
        c.restore()
        drawRing(c, Theme.px(Theme.R_CHIP))
    }
}
