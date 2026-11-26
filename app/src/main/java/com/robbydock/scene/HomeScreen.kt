package com.robbydock.scene

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Home: fixed top bar (search with mic, weather and clock, settings), a Stremio spotlight over the
 * selected title's backdrop, a Continue watching carousel, and pinned apps with a Show all grid.
 * Rows scroll as a page when focus moves between them.
 */
@SuppressLint("ViewConstructor")
class HomeScreen(private val act: HomeActivity) : FrameLayout(act) {

    companion object {
        /** App tile: icon square plus room for the name underneath. */
        /** Left and right margin of Home's content and top bar. */
        const val EDGE = 64f
        const val TILE = 160f
        const val TILE_H = 216f
        const val TILE_GAP = 44f
        /** Width of the name shown under a focused round button. */
        const val HINT_W = 400f
        /** Where the pinned row starts when the spotlight is shown. */
        const val PINNED_Y_WITH_SPOT = 1150f
        /** All apps columns: 8 on a 16:9 screen, more on wider ones. */
        val GRID_COLS get() = gridCols(Theme.W - EDGE * 2)
        /** Columns of tiles that fit [avail] with at least the usual gap. */
        fun gridCols(avail: Float) = maxOf(4, ((avail + TILE_GAP) / (TILE + TILE_GAP) + 0.01f).toInt())
        /** Column pitch that spreads [cols] tiles across all of [avail], so no margin is left on the right. */
        fun gridPitch(avail: Float, cols: Int) = if (cols > 1) (avail - TILE) / (cols - 1) else TILE + TILE_GAP
        val GRID_PITCH get() = gridPitch(Theme.W - EDGE * 2, GRID_COLS)
        val TOP_KEYS = setOf("search", "mic", "gear", "avatar", "np", "info")
        /** How long Home rests on a title before its art starts to drift. */
        const val IDLE_MS = 4_000L
        /** Titles in a library row before its See all card. */
        const val ROW_TITLES = 15
        /** Narrowest Now Playing, so the song stays readable in a small pill's place. */
        const val NP_MIN = 420f
        /** Tabs of a shared row: its first row, or the row that shares its place. */
        const val TAB_FIRST = 0
        const val TAB_SECOND = 1
    }

    private val ambience = Ambience(act)
    private val backdrop = Backdrop(act)
    private val topScrim = TopScrim(act)
    /**
     * Home's rows. As tall as its lowest row, so taps still reach the rows that start below the
     * screen once Home scrolls them up.
     */
    private val content = object : FrameLayout(act) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, heightSpec)
            var bottom = MeasureSpec.getSize(heightSpec)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = c.layoutParams as? MarginLayoutParams ?: continue
                bottom = maxOf(bottom, lp.topMargin + c.measuredHeight)
            }
            setMeasuredDimension(measuredWidth, bottom)
        }
    }.apply { clipChildren = false; clipToPadding = false }
    private val search = SearchPill(act)
    private val mic = RoundButton(act, R.drawable.ic_mic, true)
    private val info = InfoPill(act)
    private val gear = RoundButton(act, R.drawable.ic_settings, false)
    private val avatar = AvatarView(act)
    private val wifi = WifiIcon(act)
    private val np = NowPlayingPill(act)
    private val npChanged = { nowPlayingChanged() }

    // Spotlight views.
    private val spot = FrameLayout(act).apply { clipChildren = false }
    private val title = ScrollTitle(act)
    /** The spotlight title shown now, for the Title Font preview. */
    fun spotTitle(): String = title.text
    private val metaLine = act.label("", 26f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val episode = act.label("", 30f, Theme.TEXT, Theme.SEMIBOLD)
    private val synopsis = act.label("", 26f, Theme.TEXT_DIM, Theme.REGULAR, 2)
    private val bar = ProgressBar(act)
    private val barLabel = act.label("", 24f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val loadingLines = ShimmerLines(act).apply { visibility = GONE }
    private val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
    /** Name of the focused round button, shown under it. */
    private val buttonHint = act.label("", 22f, Theme.TEXT, Theme.MEDIUM).apply {
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        alpha = 0f
    }

    /** A spotlight button. Round buttons show only the icon and name themselves on focus. */
    private class Btn(val label: String, val icon: Int, val round: Boolean = false, val key: String = "$icon", val run: () -> Unit)

    private val keyed = HashMap<String, View>()
    private val scrollTarget = HashMap<View, Float>()
    private val cards = ArrayList<ArtCard>()
    /** The first Continue watching cards' names and art, for previews in Settings. */
    fun sampleCards(): List<Pair<String, android.graphics.Bitmap?>> = cards.take(3).map { it.title to it.art }
    private var selected = 0
    /** Coming up cards, when they fill the top row in place of Continue watching. */
    private val upCards = ArrayList<ArtCard>()
    /** Open tabs of the shared rows. Home opens on Continue watching and the library. */
    private var topTab = TAB_FIRST
    /** The Coming up card the spotlight shows. */
    private var upSel = 0
    /** The open tab's pill in the top row's header, or null when the row has one tab. */
    private var topTabPill: View? = null
    private var primaryButton: View? = null
    private var scroll = 0f
    private var scrollAnim: ValueAnimator? = null
    private var pendingSelect: Runnable? = null

    init {
        clipChildren = false
        addView(ambience, LayoutParams(-1, -1))
        addView(backdrop, LayoutParams(-1, -1))
        addView(content, LayoutParams(-1, -1))
        addView(topScrim, LayoutParams(-1, Theme.ipx(260f)))
        // A bare settings button. Every control keeps the white focus ring.
        gear.bare = true
        place(search, EDGE, 48f, 760f, 88f)
        place(mic, EDGE + 684f, 60f, 64f, 64f)
        place(info, 0f, 48f, -1f, 88f)
        place(gear, Theme.W - EDGE - 88f, 48f, 88f, 88f)
        place(avatar, Theme.W - EDGE - 200f, 48f, 88f, 88f)
        place(wifi, 0f, 68f, 48f, 48f)
        place(np, 0f, 48f, 0f, 88f)
        np.visibility = GONE
        wifi.show(act.online, act.linkUp, animate = false)
        // The icon sits left of the clock and follows it, and Now Playing sits left of both.
        info.addOnLayoutChangeListener { _, l, _, _, _, _, _, _, _ ->
            val lp = wifi.layoutParams as LayoutParams
            val x = l - Theme.ipx(48f + 24f)
            if (lp.leftMargin != x) post { lp.leftMargin = x; wifi.requestLayout() }
            post { placeNowPlaying() }
        }
        (info.layoutParams as LayoutParams).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            leftMargin = 0
            rightMargin = Theme.ipx(EDGE + 88f + 24f + 88f + 24f)
        }
        topScrim.alpha = 0f
        search.focusKey = "search"
        mic.focusKey = "mic"
        gear.focusKey = "gear"
        avatar.focusKey = "avatar"
        avatar.contentDescription = "Switch profile"
        np.focusKey = "np"
        info.focusKey = "info"
        info.contentDescription = "Weather and forecast"
        info.id = View.generateViewId()
        info.setOnClickListener { act.openForecast() }
        keyed["info"] = info
        np.info = info
        np.contentDescription = "Now playing"
        np.setOnClickListener { NowPlaying.playPause() }
        np.setOnLongClickListener { act.nowPlayingMenu(); true }
        keyed["np"] = np
        np.id = View.generateViewId()
        search.setOnClickListener { act.openSearch(false) }
        mic.setOnClickListener { act.openSearch(true) }
        gear.setOnClickListener { act.push(QuickPanel(act)) }
        updateBadge()
        avatar.setOnClickListener { act.push(ProfilePicker(act)) }
        keyed["search"] = search
        keyed["mic"] = mic
        keyed["gear"] = gear
        keyed["avatar"] = avatar
        // Links left and right focus inside the top bar.
        search.id = View.generateViewId()
        mic.id = View.generateViewId()
        gear.id = View.generateViewId()
        avatar.id = View.generateViewId()
        linkTopBar()

        viewTreeObserver.addOnGlobalFocusChangeListener(ViewTreeObserver.OnGlobalFocusChangeListener { old, now ->
            if (now == null) return@OnGlobalFocusChangeListener
            when {
                now === search || now === mic || now === gear || now === avatar || now === np || now === info -> scrollTo(0f)
                else -> scrollTarget[now]?.let { scrollTo(it) }
            }
            (now.parent as? Strip)?.let { if (it.remember) lastIn[it] = now }
            idleWatch(now)
            if (!FocusView.quiet && !moving) polish(old, now)
        })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); NowPlaying.listen(npChanged); nowPlayingChanged() }
    override fun onDetachedFromWindow() { NowPlaying.unlisten(npChanged); super.onDetachedFromWindow() }

    /** Shows Now Playing when something plays or is paused, and links it into the top bar. */
    fun nowPlayingChanged() {
        val show = NowPlaying.showOnHome(act) && NowPlaying.song != null && NowPlaying.state > 0
        // Focus moves with the swap, so it never lands on the hidden pill.
        val hadFocus = np.isFocused || info.isFocused
        info.covered = show
        np.visibility = if (show) VISIBLE else GONE
        if (hadFocus) (if (show) np else if (info.visibility == VISIBLE) info else avatar).requestFocus()
        linkTopBar()
        if (show) {
            np.nextFocusDownId = search.nextFocusDownId
            placeNowPlaying()
            np.update()
        } else if (info.visibility == VISIBLE) {
            // The Wi-Fi icon goes back beside the clock.
            val wl = wifi.layoutParams as LayoutParams
            wl.leftMargin = info.left - Theme.ipx(48f + 24f)
            wifi.requestLayout()
        }
    }

    /** Left and right through the top bar: search, mic, Now Playing, weather, profile, settings. Hidden ones drop out. */
    private fun linkTopBar() {
        val chain = listOf(search, mic, np, info, avatar, gear).filter { it.visibility == VISIBLE }
        for ((i, v) in chain.withIndex()) {
            v.nextFocusLeftId = (chain.getOrNull(i - 1) ?: v).id
            v.nextFocusRightId = (chain.getOrNull(i + 1) ?: v).id
        }
    }

    /**
     * Now Playing takes the clock and weather pill's place, at its size, like a stacked widget.
     * It grows left to [NP_MIN] when the pill is narrow, and never reaches under the search pill.
     */
    private fun placeNowPlaying() {
        if (np.visibility != VISIBLE) return
        val right: Int
        var w: Int
        if (info.visibility != GONE && info.width > 0) {
            right = info.right
            w = maxOf(info.width, Theme.ipx(NP_MIN))
        } else {
            right = width - Theme.ipx(EDGE + 88f + 24f + 88f + 24f)
            w = Theme.ipx(np.fitWidth())
        }
        w = minOf(w, right - Theme.ipx(EDGE + 760f + 24f))
        val lp = np.layoutParams as LayoutParams
        if (lp.width != w || lp.leftMargin != right - w) { lp.width = w; lp.leftMargin = right - w; np.requestLayout() }
        // The Wi-Fi icon follows to the left of whichever pill shows.
        val wl = wifi.layoutParams as LayoutParams
        val x = right - w - Theme.ipx(48f + 24f)
        if (wl.leftMargin != x) { wl.leftMargin = x; wifi.requestLayout() }
    }

    // ---------------------------------------------------------------- data events

    // Skips data refreshes that return what is already shown.
    private var appsSig = ""
    private var contSig = ""
    private fun appsSignature() = act.apps.joinToString("|") { "${it.pkg}:${it.label}:${it.icon != null}" } +
        if (act.cfg.showRecent) "/" + act.recentApps().joinToString(",") else ""
    private fun contSignature() = act.continueItems.joinToString("|") { it.toJson().toString() } + "/${act.continueLoading}/${act.continueError}"

    fun appsChanged() { if (appsSignature() != appsSig) rebuild() }
    fun continueChanged() { if (contSignature() != contSig) rebuild() }

    fun weatherChanged() { info.apply(act.cfg, act.weather); linkTopBar(); np.invalidate() }

    /** Updates the Wi-Fi icon, and redraws the parts that depend on the network when [rebuild] is set. */
    fun networkChanged(online: Boolean, link: Boolean, rebuild: Boolean) {
        wifi.show(online, link, animate = true)
        if (rebuild) rebuild()
    }

    fun focusDefault() {
        (primaryButton ?: keyed["pin:0"] ?: search).requestFocus()
    }

    fun goTop() {
        endMove()
        scrollTo(0f)
        focusDefault()
    }

    // ---------------------------------------------------------------- build

    /** Rebuilds the scrolling content. [focus] names the view to focus afterwards. */
    fun rebuild(focus: String? = null) = FocusView.quietly { rebuildNow(focus) }

    // Replaces the content without showing focus changes.
    private fun rebuildNow(focus: String?) {
        appsSig = appsSignature()
        contSig = contSignature()
        val keep = focus ?: (findFocus() as? FocusView)?.focusKey
        if (moving) endMove()
        content.removeAllViews()
        keyed.entries.removeIf { it.key !in TOP_KEYS }
        val p = Profiles.current
        avatar.letter = p.initial
        avatar.colorIndex = p.color
        avatar.emoji = p.emoji
        avatar.locked = p.locked
        scrollTarget.clear()
        cards.clear()
        upCards.clear()
        topTabPill = null
        primaryButton = null
        weatherChanged()

        val cfg = act.cfg
        var y: Float
        val pinnedTarget: Float
        // The spotlight needs a library to watch from. Signed out, a sign-in card takes its place.
        val hero = act.spotlightOn && Watch.signedIn(act)
        if (hero) {
            buildSpotlight()
            y = 780f
            buildContinue(y)
            y = PINNED_Y_WITH_SPOT
            // The rows below are a page of their own: Continue watching leaves the screen.
            pinnedTarget = -(y - 220f)
        } else {
            art(null)
            y = if (act.spotlightOn) buildConnect(190f) + 64f else 220f
            pinnedTarget = 0f
        }
        pinTiles.clear()
        val rowsTop = y
        pageTop = pinnedTarget
        // Library rows lead the page, so the rows after them scroll further.
        y = buildLibrary(y, pinnedTarget, rowsTop)
        if (cfg.showPinned) y = buildPinned(y, pinnedTarget - (y - rowsTop))
        if (cfg.showRecent) {
            // A second row scrolls further, so it sits where the first one would.
            val below = cfg.showPinned
            if (below) y += 56f
            y = buildRecent(y, pinnedTarget - (y - rowsTop))
        }
        if (!cfg.showPinned && !cfg.showRecent) y -= 56f
        y = buildUserRows(y, pinnedTarget, rowsTop)
        buildGrid(y + 56f)
        if (selected >= act.continueItems.size) selected = 0
        if (hero) {
            if (topTab == TAB_SECOND) selectUp(upSel.coerceAtMost(upCards.size - 1).coerceAtLeast(0), immediate = true)
            else select(selected, immediate = true)
        }

        // Home uses the solid background mode.
        ambience.mode = 2
        ambience.hero = hero
        // Hides the art layer when there is no spotlight.
        backdrop.visibility = if (hero) VISIBLE else INVISIBLE
        backdrop.themeChanged()
        ambience.invalidate()
        val target = keep?.let { keyed[it] }
        if (target != null) target.focusQuietly()
        else if (act.root.findFocus() == null) focusDefault()
        invalidateAll()
    }

    /** Signed-out stand-in for the spotlight: what Stremio adds, and how to connect it. Returns its bottom edge. */
    private fun buildConnect(y: Float): Float {
        val h = 420f
        // One block that casts the shadow, so the text and buttons draw above the card.
        val block = FrameLayout(act).apply {
            clipChildren = false
            elevation = Theme.px(12f)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, Theme.px(Theme.R_CARD)) }
            }
        }
        content.place(block, EDGE, y, Theme.W - EDGE * 2, h)
        block.place(ConnectCard(act), 0f, 0f, Theme.W - EDGE * 2, h)
        // After Stremio ends the session, the card says what happened instead of starting over.
        val lost = Watch.signedOutByApp(act)
        block.place(act.label(if (lost) "Stremio signed Scene out" else "Bring your Stremio library home", 52f, Theme.TEXT, Theme.BOLD), 64f, 60f, 1100f, 70f)
        block.place(act.label(if (lost) "This happens after a password change or signing out everywhere. Sign in again with your phone and everything comes back."
            else "Sign in with your phone to see Continue watching, new episodes and a full-screen spotlight for what you're watching.",
            28f, Theme.TEXT_DIM, Theme.REGULAR, 2), 64f, 146f, 980f, 84f)
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val installed = Apps.isInstalled(act.packageManager, Config.STREMIO)
        val actions = listOfNotNull(
            Btn(if (lost) "Sign in again" else "Sign in", R.drawable.ic_qr_code_2) { act.openSignIn() },
            if (!installed) Btn("Get Stremio", R.drawable.ic_shop) { act.installStremio() } else null,
            Btn("Hide", R.drawable.ic_visibility_off) { act.cfg.spotlight = false; act.save(); rebuild() },
        )
        for ((i, b) in actions.withIndex()) {
            val pb = PillButton(act, b.label, b.icon, i == 0)
            pb.glow = false
            pb.focusKey = "connect:$i"
            pb.id = View.generateViewId()
            pb.setOnClickListener { b.run() }
            row.addView(pb, LinearLayout.LayoutParams(-2, Theme.ipx(80f)).apply { rightMargin = Theme.ipx(20f) })
            keyed["connect:$i"] = pb
            scrollTarget[pb] = 0f
            if (i == 0) primaryButton = pb
        }
        block.place(row, 64f, 280f, 1200f, 80f)
        linkTopRow()
        return y + h
    }

    /** Row title, small and dim so the art leads. */
    private fun rowHeader(text: String) = act.label(text, 26f, Theme.TEXT_DIM, Theme.MEDIUM)

    private fun buildSpotlight() {
        spot.removeAllViews()
        content.place(spot, EDGE, 190f, Theme.W - EDGE * 2, 580f)
        // The title bleeds into the left margin.
        title.bleed = Theme.px(EDGE)
        // A large title, then the meta line, the episode and a three-line synopsis.
        title.lineBox = Theme.px(116f)
        spot.place(title, -EDGE, 6f, 900f + EDGE, 148f)
        spot.place(metaLine, 0f, 136f, 900f, 34f)
        spot.place(episode, 0f, 196f, 900f, 38f)
        synopsis.maxLines = 3
        spot.place(synopsis, 0f, 246f, 860f, 120f)
        spot.place(loadingLines, 0f, 146f, 860f, 200f)
        spot.place(bar, 0f, 412f, 420f, 8f)
        spot.place(barLabel, 440f, 402f, 900f, 30f)
        spot.place(buttons, 0f, 456f, Theme.W - EDGE * 2, 80f)
        spot.place(buttonHint, 0f, 548f, HINT_W, 32f)
    }

    private fun buildButtons(list: List<Btn>) {
        // When the actions are unchanged, updates them in place.
        val shownKeys = (0 until buttons.childCount).map { (buttons.getChildAt(it) as? FocusView)?.focusKey }
        if (shownKeys.isNotEmpty() && shownKeys == list.map { "btn:${it.key}" }) {
            for ((i, b) in list.withIndex()) {
                val pb = buttons.getChildAt(i) as PillButton
                val text = if (b.round) "" else b.label
                if (pb.text != text) { pb.text = text; pb.requestLayout() }
                pb.contentDescription = b.label
                pb.setOnClickListener { b.run() }
                pb.setOnFocusChangeListener { v, has -> hintFor(v, has, b) }
                pb.invalidate()
                keyed["btn:${b.key}"] = pb
                scrollTarget[pb] = 0f
                if (i == 0) primaryButton = pb
            }
            linkTopRow()
            return
        }
        buttons.removeAllViews()
        primaryButton = null
        buttonHint.animate().cancel()
        buttonHint.alpha = 0f
        for ((i, b) in list.withIndex()) {
            val pb = PillButton(act, if (b.round) "" else b.label, b.icon, i == 0)
            pb.bare = true
            pb.glow = false
            pb.contentDescription = b.label
            // Keyed by icon.
            val key = "btn:${b.key}"
            pb.focusKey = key
            pb.setOnClickListener { b.run() }
            pb.id = View.generateViewId()
            pb.setOnFocusChangeListener { v, has -> hintFor(v, has, b) }
            buttons.addView(pb, LinearLayout.LayoutParams(-2, Theme.ipx(80f)).apply { rightMargin = Theme.ipx(20f) })
            keyed[key] = pb
            scrollTarget[pb] = 0f
            if (i == 0) primaryButton = pb
        }
        linkTopRow()
    }

    /** Down from the top bar lands on the first action. */
    private fun linkTopRow() {
        search.nextFocusDownId = primaryButton?.id ?: View.NO_ID
        mic.nextFocusDownId = search.nextFocusDownId
        np.nextFocusDownId = search.nextFocusDownId
        gear.nextFocusDownId = search.nextFocusDownId
        avatar.nextFocusDownId = search.nextFocusDownId
        info.nextFocusDownId = search.nextFocusDownId
    }

    /** Names a round button under it while it has focus. */
    private fun hintFor(v: View, has: Boolean, b: Btn) {
        if (has && b.round) {
            buttonHint.text = b.label
            // Waits for layout.
            v.post {
                if (!v.hasFocus()) return@post
                buttonHint.translationX = v.left + v.width / 2f - Theme.px(HINT_W / 2)
                buttonHint.translationY = buttons.translationY
                buttonHint.animate().alpha(1f).setDuration(140).start()
            }
        } else {
            buttonHint.animate().alpha(0f).setDuration(100).start()
        }
    }

    /** Continue watching, with Coming up as a second tab when anything is due. */
    private fun buildContinue(y: Float) {
        val items = act.continueItems
        val comingOn = Coming.shown(act)
        if (comingOn) {
            // An episode that has aired since the list loaded sends it back for a fresh one.
            if (Coming.ready?.any { it.air.at < System.currentTimeMillis() } == true) Coming.reset()
            if (Coming.ready == null) Coming.load(act) { rebuild() }
        }
        // Nothing due in the window: Coming up leaves the row.
        val comingHas = comingOn && Coming.ready?.isEmpty() != true
        if (!comingHas) topTab = TAB_FIRST
        // The header sits a little higher than other rows, so a lifted card never touches it.
        topTabPill = rowTabs(y - 10f, 0f, "tophead", if (comingHas) listOf("Continue watching", "Coming up") else listOf("Continue watching"), topTab,
            { topTab = it; upSel = 0 })
        // About 352 wide, sized so whole cards end at the right margin and the next one peeks in clearly.
        val gap = 28f
        val n = Math.round((Theme.W - EDGE * 2 + gap) / (352f + gap)).coerceAtLeast(2)
        val cw = (Theme.W - EDGE * 2 - (n - 1) * gap) / n
        val strip = Strip(act, cw, gap).apply { remember = false }
        content.place(strip, EDGE, y + 58f, Theme.W - EDGE, 198f * cw / 352f)
        if (topTab == TAB_SECOND) { buildComingCards(strip); return }
        if (items.isEmpty()) {
            val card = ArtCard(act).also { it.cinema = true }.apply {
                when {
                    !act.online -> {
                        title = "No connection"; subtitle = "Your progress shows when you're back online"; iconRes = R.drawable.ic_wifi
                        setOnClickListener { act.openSystemSettings(android.provider.Settings.ACTION_WIFI_SETTINGS) }
                    }
                    Watch.signedOutByApp(act) -> {
                        title = "${Watch.name(act)} signed Scene out"; subtitle = "Press OK to sign in again"; iconRes = R.drawable.ic_account_circle
                        setOnClickListener { if (Watch.isStremio(act)) act.openSignIn() else WatchSignIn.start(act, Watch.source(act)) }
                    }
                    !Watch.signedIn(act) -> {
                        title = "Sign in to Stremio"; subtitle = "Your progress shows up here"; iconRes = R.drawable.ic_account_circle
                        setOnClickListener { act.openSignIn() }
                    }
                    act.continueLoading -> {
                        title = ""; subtitle = ""; loading = true
                    }
                    act.continueError -> {
                        title = "Couldn't reach ${Watch.name(act)}"; subtitle = "Press OK to try again"; iconRes = R.drawable.ic_refresh
                        setOnClickListener { act.refreshContinue(force = true) }
                    }
                    else -> {
                        title = "Nothing in progress"; subtitle = "Start something in ${Watch.name(act)}"; iconRes = R.drawable.ic_movie
                        setOnClickListener { act.launch(Watch.source(act).pkg) }
                    }
                }
                focusKey = "cw:empty"
                id = View.generateViewId()
            }
            strip.add(card)
            keyed["cw:empty"] = card
            scrollTarget[card] = 0f
            cards.add(card)
            return
        }
        for ((i, item) in items.withIndex()) {
            val card = ArtCard(act).also { it.cinema = true }.apply {
                title = item.name
                subtitle = subtitleFor(item)
                progress = item.progress
                badge = when {
                    item.newCount > 1 -> "${item.newCount} NEW"
                    item.isNew -> "NEW"
                    else -> ""
                }
                iconRes = R.drawable.ic_movie
                focusKey = "cw:${item.id}"
                id = View.generateViewId()
                setOnClickListener { act.openDetail(item.type, item.id, item.videoId) }
                setOnLongClickListener { act.continueMenu(item); true }
                setOnFocusChangeListener { _, has -> if (has) select(i) }
            }
            strip.add(card)
            keyed["cw:${item.id}"] = card
            scrollTarget[card] = 0f
            cards.add(card)
        }
        // Art loads only near the focused card; far cards let go of their bitmaps.
        strip.onIndex = { i -> artWindow(items, i) }
        artWindow(items, 0)
    }

    private fun artWindow(items: List<CwItem>, focus: Int) {
        for ((i, card) in cards.withIndex()) {
            val item = items.getOrNull(i) ?: continue
            when {
                i in (focus - 2)..(focus + 7) -> if (card.art == null) loadCardArt(card, item)
                i < focus - 10 || i > focus + 16 -> card.art = null
            }
        }
    }

    private fun loadCardArt(card: ArtCard, item: CwItem) {
        card.loading = true
        val apply = { m: Meta? ->
            // Jellyfin and Plex give the episode's own still, as their apps show.
            val url = item.still ?: m?.background ?: item.poster
            Images.load(url, Theme.ipx(400f)) { b -> if (cards.contains(card)) { card.loading = false; card.art = b } }
        }
        val cached = Watch.cachedMeta(item.id)
        if (item.still != null) apply(cached)
        else if (cached != null) apply(cached) else Net.first({ Watch.meta(act, item.type, item.id) }) { apply(it) }
    }

    private fun subtitleFor(item: CwItem): String {
        if (!item.started) {
            val ep = if (item.season != null) "S${item.season} E${item.episode}" else "Film"
            return if (item.isNew) "$ep · New episode" else "$ep · Up next"
        }
        val left = item.minutesLeft
        val time = if (left >= 60) "${left / 60} h ${left % 60} min left" else "$left min left"
        return if (item.type == "series" && item.season != null) "S${item.season} E${item.episode} · $time" else "Film · $time"
    }

    /** Shows one app on a tile. Move mode rebinds tiles in place. */
    private fun bindApp(tile: AppTile, pkg: String, grid: Boolean, prefix: String = if (grid) "grid:" else "pin:") {
        val entry = act.app(pkg)
        val key = prefix + pkg
        tile.label = entry?.label ?: act.cfg.pinned.firstOrNull { it.pkg == pkg }?.label ?: pkg
        tile.icon = entry?.icon
        tile.dim = entry == null && act.apps.isNotEmpty()
        tile.locked = pkg in act.cfg.locked
        tile.focusKey = key
        tile.setOnClickListener { act.launch(pkg) }
        val row = prefix.removePrefix("row").removeSuffix(":").toIntOrNull() ?: -1
        tile.setOnLongClickListener { act.appMenu(pkg, tile.label, grid, prefix == "rec:", row); true }
        keyed[key] = tile
        tile.invalidate()
    }

    private fun buildPinned(y: Float, target: Float): Float {
        val cfg = act.cfg
        val header = rowHeader("Pinned apps")
        content.place(header, EDGE, y, -1f, 44f)
        val strip = Strip(act, TILE, TILE_GAP)
        content.place(strip, EDGE, y + 72f, Theme.W - EDGE, TILE_H)
        for ((i, pin) in cfg.pinned.withIndex()) {
            val tile = AppTile(act).also { it.cinema = true }
            bindApp(tile, pin.pkg, false)
            strip.add(tile)
            pinTiles.add(tile)
            if (i == 0) keyed["pin:0"] = tile
            scrollTarget[tile] = target
        }
        val add = AppTile(act).also { it.cinema = true }.apply {
            label = "Pin app"
            glyph = R.drawable.ic_add
            dashed = true
            focusKey = "pin:add"
            setOnClickListener { act.push(AppPicker(act)) }
        }
        strip.add(add)
        keyed["pin:add"] = add
        if (cfg.pinned.isEmpty()) keyed["pin:0"] = add
        scrollTarget[add] = target
        return y + 72f + TILE_H
    }

    /** The open tab's window in a shared row's header, such as "Next 30 days". */
    /**
     * Header of a shared row: rows with the same card shape share one place on Home, and tabs
     * switch between them. One tab shows as a plain title. Tabs only look like controls when
     * focused. Returns the open tab's pill, if any.
     */
    private fun rowTabs(y: Float, rowTarget: Float, key: String, names: List<String>, tab: Int, pick: (Int) -> Unit): View? {
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        fun keepHead(v: FocusView, k: String) {
            v.focusKey = k
            v.id = View.generateViewId()
            keyed[k] = v
            scrollTarget[v] = rowTarget
            head.addView(v, LinearLayout.LayoutParams(-2, Theme.ipx(52f)))
        }
        var open: View? = null
        if (names.size > 1) for ((i, name) in names.withIndex()) {
            val pill = HeaderPill(act, name).apply {
                on = i == tab
                contentDescription = name + if (i == tab) ", selected" else ""
                setOnClickListener { if (i != tab) { pick(i); rebuild("$key:$i") } }
            }
            keepHead(pill, "$key:$i")
            if (i == tab) open = pill
        } else head.addView(rowHeader(names[0]), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Theme.ipx(18f); rightMargin = Theme.ipx(18f) })
        // Left and right stay in the header, so they never jump down to a card.
        if (head.childCount > 0) {
            head.getChildAt(0).let { if (it is FocusView) it.nextFocusLeftId = it.id }
            head.getChildAt(head.childCount - 1).let { if (it is FocusView) it.nextFocusRightId = it.id }
        }
        content.place(head, EDGE - 18f, y - 4f, -1f, 52f)
        return open
    }

    /** Next episodes of followed series, soonest first, in the top row's strip. */
    private fun buildComingCards(strip: Strip) {
        val list = Coming.ready
        fun keep(v: ArtCard, key: String) {
            v.focusKey = key
            v.id = View.generateViewId()
            keyed[key] = v
            scrollTarget[v] = 0f
            strip.add(v)
            upCards.add(v)
        }
        if (list == null) {
            for (i in 0 until 5) keep(ArtCard(act).also { it.cinema = true }.apply { loading = true }, "up:$i")
            return
        }
        for ((i, u) in list.withIndex()) {
            val card = ArtCard(act).also { it.cinema = true }.apply {
                val (chip, soon) = Coming.chip(act, act.cfg.clock, u.air)
                title = u.name
                subtitle = Coming.line(act, act.cfg.clock, u.air)
                badge = chip
                badgeSoft = !soon
                iconRes = R.drawable.ic_event_upcoming
                contentDescription = "${u.name}, $chip"
                setOnClickListener { act.comingMenu(u) }
                setOnLongClickListener { act.comingMenu(u); true }
                setOnFocusChangeListener { _, has -> if (has) selectUp(i) }
            }
            keep(card, "up:${u.id}")
            // Series art, never the episode's still, so nothing ahead is spoiled.
            card.loading = true
            upMeta(u) { m -> Images.load(m?.background ?: u.poster, Theme.ipx(400f)) { b -> card.loading = false; card.art = b } }
        }
    }

    /** Series details for an upcoming episode: Cinemeta, or the Kitsu addon for anime. */
    private fun upMeta(u: Coming.Upcoming, done: (Meta?) -> Unit) {
        if (u.id.startsWith("kitsu:")) {
            Net.first({ Coming.kitsuMeta(act, u.id) }) { j ->
                done(j?.let {
                    val genres = it.optJSONArray("genres")?.let { a -> (0 until a.length()).map { k -> a.optString(k) } } ?: emptyList()
                    Meta(u.id, "series", it.optString("name", u.name), it.optString("background").ifEmpty { null }, null,
                        it.optString("poster").ifEmpty { null }, it.optString("description"), it.optString("releaseInfo"),
                        it.optString("imdbRating"), "", genres, emptyList())
                })
            }
            return
        }
        val cached = Watch.cachedMeta(u.id)
        if (cached != null) done(cached) else Net.first({ Watch.meta(act, "series", u.id) }) { done(it) }
    }

    /**
     * The Watch With app's library rows, each ending in a See all card. Returns where the next
     * row starts.
     */
    private fun buildLibrary(start: Float, target: Float, rowsTop: Float): Float {
        var y = start
        for (shelf in Library.shown(act)) {
            val list = Library.cached(shelf.key)
            if (list == null) Library.load(act, shelf.key) { rebuild() }
            // Still empty after a few tries: the row leaves Home.
            if (list != null && list.isEmpty()) continue
            val rowTarget = target - (y - rowsTop)
            content.place(rowHeader(shelf.title), EDGE, y, -1f, 44f)
            val strip = Strip(act, 200f, 28f)
            content.place(strip, EDGE, y + 72f, Theme.W - EDGE, 300f)
            fun keep(v: FocusView, key: String) {
                v.focusKey = key
                keyed[key] = v
                keyed.putIfAbsent("pin:0", v)
                scrollTarget[v] = rowTarget
                strip.add(v)
            }
            if (list == null) {
                for (i in 0 until 6) keep(PosterCard(act).apply { loading = true }, "lib:${shelf.key}:$i")
            } else {
                // The row shows 15; the titles after them fill the See all collage.
                for ((i, h) in list.take(ROW_TITLES).withIndex()) keep(PosterCard(act).apply {
                    Library.bind(this, h)
                    contentDescription = h.name
                    setOnClickListener { act.openDetail(h.type, h.id) }
                    setOnLongClickListener { Library.menu(act, h); true }
                }, "lib:${shelf.key}:$i")
                val tab = Watch.tabs(act).indexOfFirst { it.key == shelf.key }.coerceAtLeast(0)
                keep(SeeAllCard(act).apply {
                    line = shelf.title
                    contentDescription = "See all, ${shelf.title}"
                    posters(list.drop(ROW_TITLES).ifEmpty { list })
                    setOnClickListener { act.push(LibraryScreen(act, tab)) }
                }, "lib:${shelf.key}:all")
            }
            y += 72f + 300f + 56f
        }
        return y
    }

    /** Apps last opened from Scene, newest first. Empty shows one line instead of tiles. */
    private fun buildRecent(y: Float, target: Float): Float {
        val list = act.recentApps()
        content.place(rowHeader("Recent apps"), EDGE, y, -1f, 44f)
        if (list.isEmpty()) {
            content.place(act.label("Apps you open from Home show here.", 28f, Theme.TEXT_DIM), EDGE, y + 64f, 1200f, 40f)
            return y + 64f + 40f
        }
        val strip = Strip(act, TILE, TILE_GAP)
        content.place(strip, EDGE, y + 72f, Theme.W - EDGE, TILE_H)
        for ((i, pkg) in list.withIndex()) {
            val tile = AppTile(act).also { it.cinema = true }
            bindApp(tile, pkg, false, "rec:")
            strip.add(tile)
            if (i == 0) keyed.putIfAbsent("pin:0", tile)
            scrollTarget[tile] = target
        }
        return y + 72f + TILE_H
    }

    /** The user's own rows. Each scrolls to sit where the first row would. Returns the bottom edge. */
    private fun buildUserRows(start: Float, target: Float, rowsTop: Float): Float {
        var y = start
        val inputs = if (Inputs.showOnHome(act)) Inputs.list(act).filter { !it.hidden } else emptyList()
        if (inputs.isNotEmpty()) {
            y += 56f
            content.place(rowHeader("Inputs"), EDGE, y, -1f, 44f)
            val strip = Strip(act, TILE, TILE_GAP)
            content.place(strip, EDGE, y + 72f, Theme.W - EDGE, TILE_H)
            for (i in inputs) {
                val tile = AppTile(act).also { it.cinema = true }.apply {
                    label = i.name
                    glyph = R.drawable.ic_input
                    focusKey = "inp:${i.id}"
                    contentDescription = "${i.name}, ${i.system}"
                    setOnClickListener { Inputs.switchTo(act, i) }
                    setOnLongClickListener { Inputs.menu(act, i); true }
                }
                strip.add(tile)
                keyed["inp:${i.id}"] = tile
                keyed.putIfAbsent("pin:0", tile)
                scrollTarget[tile] = target - (y - rowsTop)
            }
            y += 72f + TILE_H
        }
        for ((ri, r) in act.cfg.rows.withIndex()) {
            val list = act.rowApps(r)
            // A ready-made row with no apps of its kind stays off Home.
            if (list.isEmpty() && r.auto.isNotEmpty()) continue
            y += 56f
            // The sort name sits on the row name's text line.
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = true }
            head.addView(rowHeader(r.name), LinearLayout.LayoutParams(-2, -2))
            if (r.sort != 0) head.addView(act.label(RowSort.NAMES[r.sort], 24f, Theme.TEXT_DIM, Theme.MEDIUM),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Theme.ipx(16f) })
            content.place(head, EDGE, y, -1f, 44f)
            val rowTarget = target - (y - rowsTop)
            // An empty row of your own offers an add tile, like Pin app.
            if (list.isEmpty()) {
                val add = AppTile(act).also { it.cinema = true }.apply {
                    label = "Add app"
                    glyph = R.drawable.ic_add
                    dashed = true
                    focusKey = "row$ri:add"
                    setOnClickListener {
                        act.push(AppPicker(act, "Add to ${r.name}", "Choose an app for this row") { e ->
                            if (e.pkg !in r.apps) r.apps.add(e.pkg)
                            act.save(); rebuild()
                        })
                    }
                }
                content.place(add, EDGE, y + 72f, TILE, TILE_H)
                keyed["row$ri:add"] = add
                keyed.putIfAbsent("pin:0", add)
                scrollTarget[add] = rowTarget
                y += 72f + TILE_H
                continue
            }
            // Rows wrap onto more lines like All apps, up to two lines; longer rows end in See all.
            val shown = capped(list)
            for ((i, pkg) in shown.withIndex()) {
                val line = i / GRID_COLS
                val tile = AppTile(act).also { it.cinema = true }
                bindApp(tile, pkg, false, "row$ri:")
                content.place(tile, EDGE + (i % GRID_COLS) * GRID_PITCH, y + 72f + line * (TILE_H + 40f), TILE, TILE_H)
                keyed.putIfAbsent("pin:0", tile)
                scrollTarget[tile] = rowTarget - line * (TILE_H + 40f)
            }
            if (shown.size < list.size) {
                val i = shown.size
                val line = i / GRID_COLS
                val more = seeAll(list, shown.size, "row$ri:all") {
                    val icon = AppCategory.KINDS.firstOrNull { it.key == r.auto }?.icon ?: R.drawable.ic_view_agenda
                    act.push(AppGridScreen(act, r.name, icon, false, ri, { act.rowApps(act.cfg.rows.getOrNull(ri) ?: r) },
                        { "${appCount(act.rowApps(act.cfg.rows.getOrNull(ri) ?: r).size)}  ·  ${RowSort.NAMES[r.sort]}" }))
                }
                content.place(more, EDGE + (i % GRID_COLS) * GRID_PITCH, y + 72f + line * (TILE_H + 40f), TILE, TILE_H)
                scrollTarget[more] = rowTarget - line * (TILE_H + 40f)
            }
            val lines = (shown.size + (if (shown.size < list.size) 1 else 0) + GRID_COLS - 1) / GRID_COLS
            y += 72f + lines * (TILE_H + 40f) - 40f
        }
        return y
    }

    private fun buildGrid(y: Float) {
        val list = act.sortedApps()
        val header = rowHeader("All apps")
        content.place(header, EDGE, y, -1f, 44f)
        // Sort control next to the header. Shows the current order and fills on focus.
        val sort = SmallPill(act, arrayOf("A to Z", "Recently installed", "Custom order")[act.cfg.appSort], R.drawable.ic_sort_by_alpha).apply {
            quiet = true
            focusKey = "sort"
            contentDescription = "Sort All apps"
            setOnClickListener { act.sortMenu() }
        }
        content.place(sort, EDGE + 176f, y - 2f, -1f, 48f)
        // Sits on the header's text line, a short gap after it.
        sort.post {
            sort.translationY = (header.top + header.baseline - sort.top - sort.baseline).toFloat()
            // A clear gap after the header, so the focused pill and its ring never cover "All apps".
            sort.translationX = header.right + Theme.px(24f) - sort.left
        }
        keyed["sort"] = sort
        scrollTarget[sort] = -(y + 72f - 600f)
        gridTiles.clear()
        gridPkgs.clear()
        // Two lines and See all, except while moving an app, when every app has to be in reach.
        val shown = if (allOpen) list else list.take(capped(list.map { it.pkg }).size)
        for ((i, e) in shown.withIndex()) {
            val row = i / GRID_COLS
            val col = i % GRID_COLS
            val ty = y + 72f + row * (TILE_H + 40f)
            val tile = AppTile(act).also { it.cinema = true }
            bindApp(tile, e.pkg, true)
            content.place(tile, EDGE + col * GRID_PITCH, ty, TILE, TILE_H)
            gridTiles.add(tile)
            gridPkgs.add(e.pkg)
            if (i == 0) keyed["grid:0"] = tile
            scrollTarget[tile] = -(ty - 600f)
        }
        if (shown.size < list.size) {
            val i = shown.size
            val ty = y + 72f + (i / GRID_COLS) * (TILE_H + 40f)
            val more = seeAll(list.map { it.pkg }, shown.size, "grid:all") {
                act.push(AppGridScreen(act, "All apps", R.drawable.ic_apps, true, -1, { act.sortedApps().map { it.pkg } },
                    { appCount(act.sortedApps().size) }))
            }
            content.place(more, EDGE + (i % GRID_COLS) * GRID_PITCH, ty, TILE, TILE_H)
            scrollTarget[more] = -(ty - 600f)
        }
    }

    /** A quiet dot on the settings button while an update waits that the user hasn't skipped. */
    fun updateBadge() { gear.badge = Updates.shouldNotify(act) }

    /** The apps a Home row shows: all of them up to two lines, or one less to leave room for See all. */
    private fun capped(list: List<String>): List<String> {
        val max = GRID_COLS * 2
        return if (list.size <= max) list else list.take(max - 1)
    }

    /** The tile after a capped row: the next four apps in small, and how many more there are. */
    private fun seeAll(list: List<String>, from: Int, key: String, open: () -> Unit) = AppTile(act).also { it.cinema = true }.apply {
        stack = list.drop(from).take(4).map { act.app(it)?.icon }
        label = "See all ${list.size}"
        focusKey = key
        contentDescription = "See all ${list.size} apps"
        setOnClickListener { open() }
        keyed[key] = this
    }

    /** All apps shows every app while one is being moved. */
    private var allOpen = false

    // ---------------------------------------------------------------- move mode

    private val pinTiles = ArrayList<AppTile>()
    private val gridTiles = ArrayList<AppTile>()
    private val gridPkgs = ArrayList<String>()
    /** Index of the lifted tile, or -1 when not moving. */
    private var moveIndex = -1
    private var moveGrid = false
    private val moveHint = Panel(act, 36f, { Theme.float }).apply {
        alpha = 0f
        addView(act.label("Arrows to move  ·  OK to drop  ·  Back to cancel", 26f, Theme.TEXT, Theme.MEDIUM).apply {
            gravity = android.view.Gravity.CENTER
        }, LayoutParams(-1, -1))
    }
    /** The order before the move. */
    private var undoPins: List<Pin> = emptyList()
    private var undoGrid: List<String> = emptyList()
    private var undoSort = 0
    private var undoOrder: List<String> = emptyList()

    val moving get() = moveIndex >= 0

    private fun moveTiles() = if (moveGrid) gridTiles else pinTiles

    /** Keeps the hint at the bottom, or at the top when the lifted tile is low on screen. */
    private fun placeHint(tile: View) {
        val loc = IntArray(2).also { tile.getLocationOnScreen(it) }
        val low = loc[1] + tile.height > Theme.px(960f)
        val y = Theme.px(if (low) 150f else 984f)
        moveHint.animate().translationY(y).setDuration(Theme.MEDIUM_MS).setInterpolator(Theme.EASE).start()
    }

    /** Slides [v] from where [from] sits to its own place. */
    private fun glide(v: View, from: View) {
        if (!Theme.moves) return
        val dx = (from.left - v.left).toFloat() + from.translationX
        val dy = (from.top - v.top).toFloat() + from.translationY
        v.animate().cancel()
        v.translationX = dx
        v.translationY = dy
        v.animate().translationX(0f).translationY(0f).setDuration(Theme.MEDIUM_MS + 80).setInterpolator(Motion.SPRING).start()
    }

    /** Picks up an app so the arrows move it. Moving in All apps switches it to a custom order. */
    fun startMove(grid: Boolean, pkg: String) {
        undoPins = ArrayList(act.cfg.pinned)
        undoGrid = ArrayList(gridPkgs)
        undoSort = act.cfg.appSort
        undoOrder = ArrayList(act.cfg.appOrder)
        if (grid && act.cfg.appSort != 2) {
            act.cfg.appOrder = ArrayList(act.sortedApps().map { it.pkg })
            act.cfg.appSort = 2
        }
        if (grid && !allOpen) { allOpen = true; rebuild("grid:$pkg") }
        if (grid) undoGrid = ArrayList(gridPkgs)
        val i = if (grid) gridPkgs.indexOf(pkg) else act.cfg.pinned.indexOfFirst { it.pkg == pkg }
        val tiles = if (grid) gridTiles else pinTiles
        val tile = tiles.getOrNull(i) ?: return
        moveIndex = i
        moveGrid = grid
        lift(tile, true)
        tile.requestFocus()
        // Every other tile in the row or grid wiggles.
        for (t in tiles) if (t !== tile) Motion.wiggle(t)
        if (moveHint.parent == null) place(moveHint, (Theme.W - 760f) / 2, 0f, 760f, 72f)
        moveHint.translationY = Theme.px(984f)
        moveHint.animate().alpha(1f).setDuration(Theme.FOCUS_MS).start()
        post { placeHint(tile) }
    }

    private fun lift(t: AppTile, on: Boolean) {
        t.liftVertical = moveGrid
        t.lifted = on
        Motion.stop(t)
        t.translationZ = if (on) Theme.px(24f) else 0f
    }

    /** Starts a move on the first pinned app, or on All apps when there is no pinned row to arrange. */
    fun arrange() {
        val pins = act.cfg.showPinned && act.cfg.pinned.size > 1
        when {
            pins -> startMove(false, act.cfg.pinned[0].pkg)
            gridPkgs.isNotEmpty() -> startMove(true, gridPkgs[0])
            else -> act.toast("No apps to arrange")
        }
    }

    /** Back: every tile returns to where it was before the move. */
    fun cancelMove() {
        if (!moving) return
        val pkg = (if (moveGrid) gridPkgs.getOrNull(moveIndex) else act.cfg.pinned.getOrNull(moveIndex)?.pkg)
        val grid = moveGrid
        for (t in moveTiles()) Motion.stop(t)
        moveTiles().getOrNull(moveIndex)?.let { lift(it, false) }
        moveIndex = -1
        allOpen = false
        act.cfg.pinned = ArrayList(undoPins)
        act.cfg.appSort = undoSort
        act.cfg.appOrder = ArrayList(undoOrder)
        act.save()
        moveHint.animate().alpha(0f).setDuration(Theme.FOCUS_MS).start()
        // An app past the first two lines is back behind See all, so focus goes there.
        val back = if (grid && pkg != null && pkg !in capped(act.sortedApps().map { it.pkg })) "grid:all" else pkg?.let { (if (grid) "grid:" else "pin:") + it }
        rebuild(back)
        act.toast("Move cancelled")
    }

    /** Drops the lifted app where it is and saves the order. */
    fun endMove() {
        if (!moving) return
        val tiles = moveTiles()
        tiles.getOrNull(moveIndex)?.let { t ->
            lift(t, false)
            // A small settle as the tile lands.
            if (Theme.moves) {
                t.scaleX = 1.16f; t.scaleY = 1.16f
                t.animate().scaleX(t.focusScale).scaleY(t.focusScale).setDuration(Theme.MEDIUM_MS + 120).setInterpolator(Motion.SPRING).start()
            }
        }
        // The others stop wiggling a moment after the drop.
        val others = ArrayList(tiles)
        postDelayed({ for (o in others) Motion.stop(o) }, 300)
        if (moveGrid) act.cfg.appOrder = ArrayList(gridPkgs)
        moveIndex = -1
        allOpen = false
        act.save()
        moveHint.animate().alpha(0f).setDuration(Theme.FOCUS_MS).start()
    }

    private fun moveBy(delta: Int) {
        val tiles = if (moveGrid) gridTiles else pinTiles
        val j = moveIndex + delta
        if (j !in tiles.indices) { tiles.getOrNull(moveIndex)?.let { nudge(it, delta) }; return }
        val i = moveIndex
        if (moveGrid) {
            val p = gridPkgs.removeAt(i)
            gridPkgs.add(j, p)
            // Rebinds every tile between the two places.
            for (k in minOf(i, j)..maxOf(i, j)) bindApp(tiles[k], gridPkgs[k], true)
        } else {
            val p = act.cfg.pinned.removeAt(i)
            act.cfg.pinned.add(j, p)
            for (k in minOf(i, j)..maxOf(i, j)) bindApp(tiles[k], act.cfg.pinned[k].pkg, false)
        }
        lift(tiles[i], false)
        Motion.wiggle(tiles[i])
        lift(tiles[j], true)
        // Tiles between the two places each shift one step; slide them from their old spot.
        val step = if (j > i) 1 else -1
        for (k in minOf(i, j)..maxOf(i, j)) if (k != j) tiles.getOrNull(k + step)?.let { glide(tiles[k], it) }
        glide(tiles[j], tiles[i])
        moveIndex = j
        post { placeHint(tiles[j]) }
        keyed["pin:0"] = pinTiles.firstOrNull() ?: keyed["pin:add"] ?: search
        keyed["grid:0"] = gridTiles.firstOrNull() ?: search
        tiles[j].requestFocus()
    }

    /** A small bump against the edge when the lifted app cannot move further that way. */
    private fun nudge(v: View, delta: Int) {
        if (!Theme.moves) return
        val d = Theme.px(14f) * Integer.signum(delta)
        val prop = if (kotlin.math.abs(delta) == 1) View.TRANSLATION_X else View.TRANSLATION_Y
        android.animation.ObjectAnimator.ofFloat(v, prop, 0f, d, -d * 0.4f, 0f).apply { duration = 320; interpolator = android.view.animation.DecelerateInterpolator() }.start()
    }

    /** Up and Down move exactly one row and keep the horizontal position. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (focused == null || moving || (direction != FOCUS_UP && direction != FOCUS_DOWN)) return super.focusSearch(focused, direction)
        val linked = if (direction == FOCUS_UP) focused.nextFocusUpId else focused.nextFocusDownId
        if (linked != View.NO_ID || !isInside(content, focused)) return super.focusSearch(focused, direction)
        return rowNeighbour(focused, direction == FOCUS_DOWN) ?: super.focusSearch(focused, direction)
    }

    /** The tile focused last in each app strip, so coming back to a row lands where you left it. */
    private val lastIn = java.util.WeakHashMap<Strip, View>()

    /** Cards and tiles tip the way focus moved within their row, and catch a band of light. */
    private fun polish(old: View?, now: View) {
        if (!Theme.moves || (now !is AppTile && now !is ArtCard) || !isInside(content, now)) return
        if (old != null && (old is AppTile || old is ArtCard)) {
            val at = IntArray(2)
            old.getLocationOnScreen(at); val ox = at[0]; val oy = at[1]
            now.getLocationOnScreen(at)
            val sameRow = old.parent === now.parent && (now.parent is Strip || kotlin.math.abs(at[1] - oy) < now.height / 2)
            if (sameRow && at[0] != ox) Polish.tilt(now, at[0] > ox)
        }
        val square = now is AppTile
        Polish.sheen(now, square, if (square) now.width * (36f / 168f) else Theme.px(Theme.R_CARD))
    }

    private fun isInside(parent: View, v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p === parent) return true; p = p.parent }
        return false
    }

    // Picks the nearest row above or below, then the item in it closest to the focused one horizontally.
    private fun rowNeighbour(from: View, down: Boolean): View? {
        val all = ArrayList<View>()
        content.addFocusables(all, if (down) FOCUS_DOWN else FOCUS_UP)
        val at = IntArray(2)
        fun box(v: View): FloatArray { v.getLocationOnScreen(at); return floatArrayOf(at[0].toFloat(), at[1].toFloat(), at[0] + v.width.toFloat(), at[1] + v.height.toFloat()) }
        val f = box(from)
        val fx = (f[0] + f[2]) / 2
        val gap = Theme.px(8f)
        val rows = all.filter { it !== from && it.isShown }.map { it to box(it) }
            .filter { (_, b) -> if (down) b[1] >= f[3] - gap else b[3] <= f[1] + gap }
        if (rows.isEmpty()) return null
        val edge = if (down) rows.minOf { it.second[1] } else rows.maxOf { it.second[3] }
        val tol = Theme.px(40f)
        val row = rows.filter { (_, b) -> if (down) b[1] - edge < tol else edge - b[3] < tol }
        val screenW = resources.displayMetrics.widthPixels
        val onScreen = row.filter { (_, b) -> b[2] > 0 && b[0] < screenW }.ifEmpty { row }
        val best = onScreen.minByOrNull { (_, b) -> kotlin.math.abs((b[0] + b[2]) / 2 - fx) }?.first
        // App strips remember the tile focused last.
        val strip = best?.parent as? Strip
        if (strip != null && strip.remember) lastIn[strip]?.takeIf { it.parent === strip && it.isShown }?.let { return it }
        return best
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!moving) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveBy(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveBy(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (down && moveGrid) moveBy(-GRID_COLS)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && moveGrid) moveBy(GRID_COLS)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (!down) endMove()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    // ---------------------------------------------------------------- spotlight

    private val idleStart = Runnable { backdrop.idle(true) }

    /** Any move settles the art; resting on a Continue watching card or spotlight button starts the drift. */
    private fun idleWatch(now: View?) {
        removeCallbacks(idleStart)
        backdrop.idle(false)
        val rest = if (isInTouchMode) cards.isNotEmpty() && scroll == 0f else now != null && (now in cards || now in upCards || now.parent === buttons)
        if (Theme.moves && rest) postDelayed(idleStart, IDLE_MS)
    }

    /** Starts the wait for the art to drift again, for example when Home comes back into view. */
    fun armIdle() = idleWatch(findFocus())

    /** Touch has no focus to rest on: the art drifts once Home is left alone at the top. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { removeCallbacks(idleStart); backdrop.idle(false) }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> postDelayed({ idleWatch(findFocus()) }, Theme.MEDIUM_MS)
        }
        return super.dispatchTouchEvent(e)
    }

    private fun select(index: Int, immediate: Boolean = false) {
        selected = index
        pendingSelect?.let { removeCallbacks(it) }
        val r = Runnable { showSpot(index) }
        pendingSelect = r
        if (immediate) r.run() else postDelayed(r, 180)
        select2Focus()
    }

    /** Shows the Coming up card at [index] in the spotlight. */
    private fun selectUp(index: Int, immediate: Boolean = false) {
        upSel = index
        pendingSelect?.let { removeCallbacks(it) }
        val r = Runnable { showUpSpot(index) }
        pendingSelect = r
        if (immediate) r.run() else postDelayed(r, 180)
        select2Focus()
    }

    /** An upcoming episode in the spotlight: show art and details, never the episode's still. */
    private fun showUpSpot(index: Int) {
        val u = Coming.ready?.getOrNull(index)
        bar.visibility = INVISIBLE
        barLabel.translationX = -Theme.px(440f)
        synopsis.translationY = 0f
        buttons.translationY = 0f
        if (u == null) {
            // Still loading: only the shimmer shows.
            title.text = ""
            barLabel.text = ""
            loadingLines.visibility = VISIBLE
            metaLine.visibility = INVISIBLE
            synopsis.visibility = INVISIBLE
            episode.visibility = GONE
            return
        }
        val a = u.air
        title.text = u.name
        barLabel.text = Coming.long(act, act.cfg.clock, a)
        loadingLines.visibility = VISIBLE
        metaLine.visibility = INVISIBLE
        synopsis.visibility = INVISIBLE
        episode.visibility = INVISIBLE
        val list = listOf(
            Btn("Episode list", R.drawable.ic_list) { act.openDetail("series", u.id) },
            Btn("More options", R.drawable.ic_more_horiz, true) { act.comingMenu(u) },
        )
        val focusedKey = (findFocus() as? FocusView)?.focusKey
        buildButtons(list)
        if (focusedKey?.startsWith("btn:") == true && findFocus() !== keyed[focusedKey]) (keyed[focusedKey] ?: primaryButton)?.focusQuietly()
        select2Focus()
        upMeta(u) { m ->
            if (topTab != TAB_SECOND || upSel != index || Coming.ready?.getOrNull(index) !== u) return@upMeta
            loadingLines.visibility = GONE
            metaLine.visibility = VISIBLE
            synopsis.visibility = VISIBLE
            episode.visibility = VISIBLE
            val metaText = android.text.SpannableStringBuilder(listOfNotNull(m?.year?.takeIf { it.isNotEmpty() }, "Series").joinToString("  ·  "))
            m?.rating?.takeIf { it.isNotEmpty() }?.let {
                metaText.append("  ·  ")
                // Anime ratings are Kitsu's, so they never wear the IMDb tag.
                if (u.id.startsWith("kitsu:")) metaText.append("Kitsu ")
                else {
                    val at = metaText.length
                    metaText.append("I").setSpan(ImdbSpan(), at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                metaText.append(it)
            }
            metaLine.text = metaText
            val named = a.title.isNotEmpty() && a.title != "TBA" && !Regex("^Episode \\d+$").matches(a.title)
            episode.text = "S${a.season} · E${a.episode}" + (if (named) "  ${a.title}" else "")
            synopsis.text = m?.description ?: ""
            val url = m?.background ?: u.poster
            if (url == null) { art(null); return@upMeta }
            val full = Images.cached(url, Theme.ipx(1280f))
            if (full != null) setBackdrop(full)
            else {
                upCards.getOrNull(index)?.art?.let { setBackdrop(it) }
                Images.load(url, Theme.ipx(1280f), exact = false) { b -> if (b != null && topTab == TAB_SECOND && upSel == index) setBackdrop(b) }
            }
        }
    }

    private fun showSpot(index: Int) {
        if (topTab != TAB_FIRST) return
        val items = act.continueItems
        val item = items.getOrNull(index)
        if (item == null) {
            showEmptySpot()
            return
        }
        title.text = item.name
        bar.progress = item.progress
        bar.visibility = if (item.started) VISIBLE else INVISIBLE
        barLabel.translationX = if (item.started) 0f else -Theme.px(440f)
        barLabel.text = when {
            item.started && item.isNew -> subtitleFor(item).substringAfter(" · ") + "  ·  " + newText(item)
            item.started -> subtitleFor(item).substringAfter(" · ")
            item.isNew -> newText(item)
            else -> "Up next"
        }
        val meta = Watch.cachedMeta(item.id)
        fillMeta(item, meta)
        if (meta == null) {
            Net.first({ Watch.meta(act, item.type, item.id) }) { m ->
                if (topTab != TAB_FIRST || selected != index || act.continueItems.getOrNull(index) !== item) return@first
                if (m != null) fillMeta(item, m) else loadingLines.visibility = GONE
            }
        }
    }

    private fun fillMeta(item: CwItem, meta: Meta?) {
        // Until details arrive, only the shimmer shows.
        val waiting = meta == null
        loadingLines.visibility = if (waiting) VISIBLE else GONE
        val shown = if (waiting) INVISIBLE else VISIBLE
        metaLine.visibility = shown
        synopsis.visibility = shown
        synopsis.translationY = 0f
        buttons.translationY = 0f
        val parts = ArrayList<String>()
        meta?.year?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        parts.add(if (item.type == "series") "Series" else "Film")
        val metaText = android.text.SpannableStringBuilder(parts.joinToString("  ·  "))
        meta?.rating?.takeIf { it.isNotEmpty() }?.let {
            metaText.append("  ·  ")
            val at = metaText.length
            metaText.append("I").setSpan(ImdbSpan(), at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            metaText.append(it)
        }
        meta?.runtime?.takeIf { it.isNotEmpty() }?.let { metaText.append("  ·  ").append(it) }
        metaLine.text = metaText
        val video = meta?.videos?.firstOrNull { it.id == item.videoId }
        if (item.type == "series" && item.season != null) {
            val name = video?.title?.takeIf { it.isNotEmpty() && !it.startsWith("Episode") && it != "TBA" }
            episode.text = "S${item.season} · E${item.episode}" + (name?.let { "  $it" } ?: "")
            episode.visibility = shown
        } else {
            episode.visibility = GONE
        }
        synopsis.text = video?.overview?.takeIf { it.isNotEmpty() } ?: meta?.description ?: ""
        // Main actions get labels; the rest are round icon buttons that name themselves on focus.
        val list = ArrayList<Btn>()
        val series = item.type == "series"
        // Without the app nothing can play, so the main action installs it.
        val src = Watch.source(act)
        val stremio = src == Source.STREMIO
        val hasApp = Watch.appInstalled(act)
        if (hasApp) list.add(Btn(if (item.started) "Resume" else "Play", R.drawable.ic_play_arrow) { act.openDetail(item.type, item.id, item.videoId) })
        else list.add(Btn("Get ${src.label}", R.drawable.ic_shop) { act.installApp(src) })
        val next = meta?.let { Watch.nextVideo(it, item) }
        if (hasApp && next != null && item.started) list.add(Btn("Next episode", R.drawable.ic_skip_next) { act.openDetail(item.type, item.id, next.id) })
        list.add(Btn(if (series) "Episodes" else "Details", if (series) R.drawable.ic_list else R.drawable.ic_info, true) { act.openDetail(item.type, item.id) })
        if (hasApp && stremio) meta?.trailer?.let { yt -> list.add(Btn("Trailer", R.drawable.ic_movie, true) { act.openTrailer(yt, item.name) }) }
        if (Watch.signedIn(act)) {
            list.add(Btn(if (series) "Mark episode watched" else "Mark watched", R.drawable.ic_check_circle, true) { act.markWatched(item) })
            val words = Watch.libraryWords(act)
            if (Watch.hasLibrary(act)) list.add(if (item.inLibrary) Btn(words.second, R.drawable.ic_bookmark_added, true, "lib") { act.setInLibrary(item, false) }
                else Btn(words.first, R.drawable.ic_bookmark_add, true, "lib") { act.setInLibrary(item, true) })
            if (stremio && series && item.inLibrary) {
                list.add(if (item.notify) Btn("Stop new episode alerts", R.drawable.ic_notifications_active, true, "notify") { act.setNotify(item, false) }
                    else Btn("Get new episode alerts", R.drawable.ic_notifications_off, true, "notify") { act.setNotify(item, true) })
            }
        }
        list.add(Btn("More options", R.drawable.ic_more_horiz, true) { act.continueMenu(item) })
        // Cinema keeps the main actions, Episodes, Trailer and More; the rest are in the More menu.
        list.retainAll { !it.round || it.icon == R.drawable.ic_list || it.icon == R.drawable.ic_info || it.icon == R.drawable.ic_movie || it.icon == R.drawable.ic_more_horiz }
        val focusedKey = (findFocus() as? FocusView)?.focusKey
        buildButtons(list)
        if (focusedKey?.startsWith("btn:") == true && findFocus() !== keyed[focusedKey]) keyed[focusedKey]?.focusQuietly()
        select2Focus()
        val url = meta?.background ?: item.still ?: item.poster
        if (url != null) {
            // Show the card's smaller copy at once, then swap in the full backdrop when it is ready.
            val full = Images.cached(url, Theme.ipx(1280f))
            if (full != null) setBackdrop(full)
            else {
                cards.getOrNull(selected)?.art?.let { setBackdrop(it) }
                Images.load(url, Theme.ipx(1280f), exact = false) { b -> if (b != null && topTab == TAB_FIRST && act.continueItems.getOrNull(selected) === item) setBackdrop(b) }
            }
            prefetchBackdrop(selected + 1)
        }
    }

    /** Decodes the next title's backdrop ahead of time. */
    private fun prefetchBackdrop(index: Int) {
        val item = act.continueItems.getOrNull(index) ?: return
        val url = Watch.cachedMeta(item.id)?.background ?: return
        if (Images.cached(url, Theme.ipx(1280f)) == null) Images.load(url, Theme.ipx(1280f), exact = false) { }
    }

    private fun newText(item: CwItem) = if (item.newCount == 1) "1 new episode" else "${item.newCount} new episodes"

    /**
     * Links the spotlight to the top row: down from the buttons goes to the shown card, past the
     * tabs. Up from a card goes to the open tab, or to the first button when there are no tabs.
     */
    private fun select2Focus() {
        val row = if (topTab == TAB_SECOND) upCards else cards
        val card = row.getOrNull(if (topTab == TAB_SECOND) upSel else selected) ?: row.firstOrNull() ?: return
        // Down from the buttons stops at the row's tabs first, so they are never skipped.
        val down = topTabPill ?: card
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).nextFocusDownId = down.id
        val up = topTabPill ?: primaryButton ?: return
        for (c in row) c.nextFocusUpId = up.id
        topTabPill?.let { tab ->
            primaryButton?.let { pb -> tab.nextFocusUpId = pb.id }
            tab.nextFocusDownId = card.id
            keyed["tophead:${1 - topTab}"]?.let { it.nextFocusUpId = tab.nextFocusUpId; it.nextFocusDownId = card.id }
        }
    }

    private fun showEmptySpot() {
        loadingLines.visibility = GONE
        bar.visibility = INVISIBLE
        barLabel.translationX = 0f
        barLabel.text = ""
        episode.visibility = GONE
        metaLine.text = ""
        // Lifts the text and buttons when there are no meta lines.
        synopsis.translationY = -Theme.px(150f)
        buttons.translationY = -Theme.px(150f)
        metaLine.visibility = VISIBLE
        synopsis.visibility = VISIBLE
        val list = ArrayList<Btn>()
        val app = Watch.name(act)
        val pkg = Watch.source(act).pkg
        if (!act.online && Watch.signedIn(act)) {
            title.text = "You're offline"
            synopsis.text = "Connect to Wi-Fi to see what's next. Your apps below still work."
            list.add(Btn("Wi-Fi settings", R.drawable.ic_wifi) { act.openSystemSettings(android.provider.Settings.ACTION_WIFI_SETTINGS) })
            list.add(Btn("Open $app", R.drawable.ic_play_arrow) { act.launch(pkg) })
        } else if (!Watch.signedIn(act)) {
            title.text = "Pick up where you left off"
            synopsis.text = "Sign in with your phone to see your shows and films here, with one press to resume."
            list.add(Btn("Sign in", R.drawable.ic_qr_code_2) { act.openSignIn() })
            list.add(Btn("Open Stremio", R.drawable.ic_play_arrow) { act.launch(Config.STREMIO) })
        } else {
            title.text = if (act.continueLoading) "Loading your library" else "Nothing in progress"
            synopsis.text = "Anything you start in $app appears here, ready to resume."
            list.add(Btn("Open $app", R.drawable.ic_play_arrow) { act.launch(pkg) })
        }
        buildButtons(list)
        select2Focus()
        art(null)
    }

    private fun setBackdrop(b: Bitmap) = art(b)

    /** The spotlight art and the screen background follow the same title. */
    private fun art(b: Bitmap?) {
        backdrop.set(b)
        ambience.set(b)
    }

    // ---------------------------------------------------------------- scrolling

    private fun scrollTo(target: Float, ms: Long = Theme.MEDIUM_MS) {
        if (target == scroll && scrollAnim == null) return
        scrollAnim?.cancel()
        val from = scroll
        scrollAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = ms
            interpolator = Theme.EASE
            addUpdateListener { applyScroll(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { scrollAnim = null }
            })
            start()
        }
    }

    /** Moves Home's rows to [v] design units and fades the spotlight as they rise. */
    private fun applyScroll(v: Float) {
        scroll = v
        content.translationY = Theme.px(scroll)
        val t = (-scroll / 400f).coerceIn(0f, 1f)
        spot.alpha = 1f - (t * 1.6f).coerceAtMost(1f)
        backdrop.alpha = 1f - t * 0.55f
        backdrop.translationY = Theme.px(scroll * 0.35f)
        ambience.follow(backdrop.translationY, backdrop.alpha)
        topScrim.alpha = t
        Glass.follow(backdrop.translationY, backdrop.alpha)
    }

    // ---------------------------------------------------------------- touch

    private val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
    private var touchY = 0f
    private var touchX = 0f
    private var dragFrom = 0f
    private var dragging = false
    private var tracker: android.view.VelocityTracker? = null
    /** Where the second page starts. Home never rests between it and the top. */
    private var pageTop = 0f

    /** Farthest Home scrolls down: the last row's resting place. */
    private fun minScroll() = scrollTarget.values.minOrNull()?.coerceAtMost(0f) ?: 0f

    private fun startDrag(e: android.view.MotionEvent) {
        dragging = true
        scrollAnim?.cancel()
        dragFrom = scroll
        touchY = e.y
        tracker?.recycle()
        tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
    }

    /** An up or down swipe takes over from the tile under the finger. */
    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { touchX = e.x; touchY = e.y; dragging = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = e.y - touchY
                if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(e.x - touchX)) { startDrag(e); return true }
            }
        }
        return false
    }

    /** Swipes scroll Home's rows; a quick swipe carries on a little. */
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { touchX = e.x; touchY = e.y; dragging = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging) { if (kotlin.math.abs(e.y - touchY) > slop) startDrag(e) else return true }
                tracker?.addMovement(e)
                applyScroll((dragFrom + (e.y - touchY) / Theme.u).coerceIn(minScroll(), 0f))
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val t = tracker
                    t?.addMovement(e); t?.computeCurrentVelocity(1000)
                    val v = (t?.yVelocity ?: 0f) / Theme.u
                    var end = (scroll + v * 0.25f).coerceIn(minScroll(), 0f)
                    // Between the spotlight and the rows below, settles on one page or the other.
                    if (pageTop < 0f && end > pageTop && end < 0f) {
                        val down = if (kotlin.math.abs(v) > 300f) v < 0f else scroll < dragFrom
                        end = if (down) pageTop else 0f
                    }
                    scrollTo(end, 420L)
                }
                tracker?.recycle(); tracker = null
                dragging = false
            }
        }
        return true
    }
}

/**
 * Network state as one icon beside the clock. Slashed and muted with no network, red when a
 * network has no internet. On recovery it turns green, holds, then slides out.
 */
class WifiIcon(ctx: Context) : View(ctx) {
    private val off = ctx.icon(R.drawable.ic_wifi_off, Theme.TEXT_DIM)
    private val bad = ctx.icon(R.drawable.ic_wifi, RED)
    private val good = ctx.icon(R.drawable.ic_wifi, GREEN)
    private var shown: android.graphics.drawable.Drawable? = null
    private val hide = Runnable { leave() }

    private companion object {
        const val RED = 0xFFFF6B6B.toInt()
        const val GREEN = 0xFF6FD08C.toInt()
        const val HOLD_MS = 1200L
    }

    /** Every change cancels the previous one. */
    fun show(online: Boolean, link: Boolean, animate: Boolean) {
        removeCallbacks(hide)
        animate().cancel()
        translationX = 0f
        if (online) {
            if (visibility != VISIBLE || !animate) { visibility = GONE; return }
            shown = good
            contentDescription = "Back online"
            alpha = 1f
            invalidate()
            postDelayed(hide, HOLD_MS)
            return
        }
        shown = if (link) bad else off
        contentDescription = if (link) "No internet" else "Offline"
        visibility = VISIBLE
        alpha = 1f
        invalidate()
    }

    private fun leave() {
        if (Theme.motion == 2) { visibility = GONE; return }
        animate().translationX(Theme.px(-24f)).alpha(0f).setDuration(Theme.MEDIUM_MS)
            .withEndAction { visibility = GONE; translationX = 0f }.start()
    }

    override fun onDraw(c: Canvas) {
        val d = shown ?: return
        d.setBounds(0, 0, width, height)
        d.draw(c)
    }
}

/** Horizontal row that keeps the focused child near the left edge. */
@SuppressLint("ViewConstructor")
class Strip(ctx: Context, private val itemW: Float, private val gap: Float, private val viewport: Float = Theme.W - HomeScreen.EDGE * 2) : FrameLayout(ctx) {
    private var count = 0
    /** Coming back to this strip from another row lands on the tile focused last. */
    var remember = true
    /** Called with the focused index. */
    var onIndex: ((Int) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** As wide as all its items, so taps reach the ones slid in from the right. */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        var right = measuredWidth
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as? MarginLayoutParams ?: continue
            right = maxOf(right, lp.leftMargin + c.measuredWidth)
        }
        setMeasuredDimension(right, measuredHeight)
    }

    fun add(v: View) {
        val x = count * (itemW + gap)
        addView(v, LayoutParams(Theme.ipx(itemW), LayoutParams.MATCH_PARENT).apply { leftMargin = Theme.ipx(x) })
        val index = count
        val prev = v.onFocusChangeListener
        v.setOnFocusChangeListener { view, has ->
            prev?.onFocusChange(view, has)
            if (has) scrollToIndex(index)
        }
        count++
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (child != null) scrollToIndex(indexOfChild(child))
    }

    /** Right on the last item stays put, so focus never jumps to the top bar. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (direction == FOCUS_RIGHT && focused != null && childCount > 0 && indexOfChild(focused) == childCount - 1) return focused
        return super.focusSearch(focused, direction)
    }

    private fun scrollToIndex(i: Int) {
        val pitch = itemW + gap
        val total = count * pitch - gap
        val max = (total - viewport).coerceAtLeast(0f)
        onIndex?.invoke(i)
        val target = -(i * pitch).coerceAtMost(max)
        animate().translationX(Theme.px(target)).setDuration(Theme.MEDIUM_MS).setInterpolator(Theme.EASE).start()
    }

    // Side swipes slide the row; a quick swipe carries on a little.
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var touchX = 0f
    private var touchY = 0f
    private var dragFrom = 0f
    private var dragging = false
    private var tracker: android.view.VelocityTracker? = null

    private fun minX() = -Theme.px((count * (itemW + gap) - gap - viewport).coerceAtLeast(0f))

    /** Tells the row which item a swipe brings to the left edge, so art loads without focus moving. */
    private var lastShown = -1
    private fun shownFrom(x: Float) {
        val i = (-x / Theme.px(itemW + gap)).toInt().coerceAtLeast(0)
        if (i != lastShown) { lastShown = i; onIndex?.invoke(i) }
    }

    private fun startDrag(e: android.view.MotionEvent) {
        dragging = true
        animate().cancel()
        dragFrom = translationX
        touchX = e.rawX
        parent?.requestDisallowInterceptTouchEvent(true)
        tracker?.recycle()
        tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
    }

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { touchX = e.rawX; touchY = e.rawY; dragging = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - touchX
                if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(e.rawY - touchY)) { startDrag(e); return true }
            }
        }
        return false
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> return true
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging) { if (kotlin.math.abs(e.rawX - touchX) > slop) startDrag(e) else return true }
                tracker?.addMovement(e)
                translationX = (dragFrom + e.rawX - touchX).coerceIn(minX(), 0f)
                shownFrom(translationX)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val t = tracker
                    t?.addMovement(e); t?.computeCurrentVelocity(1000)
                    val end = (translationX + (t?.xVelocity ?: 0f) * 0.25f).coerceIn(minX(), 0f)
                    animate().translationX(end).setDuration(420L).setInterpolator(Theme.EASE).start()
                    shownFrom(end)
                }
                tracker?.recycle(); tracker = null
                dragging = false
            }
        }
        return true
    }
}

/** Full-bleed art behind the spotlight with soft scrims, cross-fading between titles. */
class Backdrop(ctx: Context) : View(ctx) {
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var fade = 1f
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val h1 = Paint()
    private val v1 = Paint()
    private val brand = Paint()
    /** Darkens the hero's edges around the empty right side, so the top bar and text read on bright art. */
    private val vignette = Paint(Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    /** The art's main colour, eased in over [TINT_MS]. Falls back to the accent. */
    private var tint = Theme.accent
    private var tintAnim: ValueAnimator? = null
    private val tintP = Paint(Paint.DITHER_FLAG)
    /** Zoom and pan of the art while Home sits idle on a title, 0 to 1 and back, at full motion. */
    private var drift = 0f
    private var driftAnim: ValueAnimator? = null
    /** Eases the art back after the drift. One at a time, so repeated keys never fight over [drift]. */
    private var settleAnim: ValueAnimator? = null

    private companion object { const val TINT_MS = 1200L; const val DRIFT_MS = 18_000L; const val SETTLE_MS = 700L }

    private fun tintTo(c: Int) {
        tintAnim?.cancel()
        if (Theme.motion == 2) { tint = c; updateShaders(); invalidate(); return }
        val from = tint
        tintAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TINT_MS
            interpolator = Theme.EASE
            addUpdateListener { tint = Theme.mix(from, c, it.animatedValue as Float); updateShaders(); invalidate() }
            start()
        }
    }

    /**
     * Starts the slow zoom and pan once Home has sat on one title for a while, or eases the art
     * back when anything moves. Only at full motion.
     */
    fun idle(on: Boolean) {
        if (on && (!Theme.moves || !isAttachedToWindow || current == null || driftAnim != null)) return
        // Already easing back: let it finish from where it is.
        if (!on && driftAnim == null && (drift == 0f || settleAnim != null)) return
        driftAnim?.cancel()
        driftAnim = null
        settleAnim?.cancel()
        settleAnim = null
        if (on) {
            driftAnim = ValueAnimator.ofFloat(drift, 1f).apply {
                duration = DRIFT_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { drift = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (Theme.moves && drift > 0f) {
            settleAnim = ValueAnimator.ofFloat(drift, 0f).apply {
                duration = SETTLE_MS
                interpolator = Theme.EASE
                addUpdateListener { drift = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { if (settleAnim === a) settleAnim = null }
                })
                start()
            }
        } else { drift = 0f; invalidate() }
    }

    // Rests while Home is hidden behind another app.
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v != VISIBLE) idle(false) }
    override fun onDetachedFromWindow() { idle(false); tintAnim?.cancel(); super.onDetachedFromWindow() }

    fun set(b: Bitmap?) {
        if (b === current) return
        previous = current
        current = b
        fade = 0f
        tintTo(b?.let { Polish.artColor(it) } ?: Theme.accent)
        feedGlass()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = Theme.EASE
            addUpdateListener { fade = it.animatedValue as Float; invalidate(); Glass.fade(fade) }
            start()
        }
    }

    private val still = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Hands a blurred copy of the settled backdrop to the top bar's haze. */
    private fun feedGlass() {
        if (width <= 0 || height <= 0) return
        if (h1.shader == null) updateShaders()
        Glass.setBackdrop(Images.blurSync(width, height) { paintStill(it) })
    }

    /** The backdrop as it looks once the cross-fade ends. */
    private fun paintStill(c: Canvas) {
        val w = width.toFloat()
        val heroH = Theme.px(800f)
        c.drawColor(Theme.bg)
        val cur = current
        if (cur == null) {
            c.drawRect(0f, 0f, w, heroH, brand)
        } else {
            drawArt(c, cur, 1f, still)
            c.drawRect(0f, 0f, w, heroH, h1)
            c.drawRect(0f, 0f, w, heroH, vignette)
        }
        c.drawRect(0f, 0f, w, heroH + 1, v1)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        updateShaders()
        feedGlass()
    }

    private fun updateShaders() {
        // Waits for layout.
        if (width <= 0) return
        val w = width.toFloat()
        val heroH = Theme.px(800f)
        val bg = Theme.bg
        // The art stays faintly visible to the left edge.
        h1.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Theme.withAlpha(bg, 0.86f), Theme.withAlpha(bg, 0.84f), Theme.withAlpha(bg, 0.35f), Theme.withAlpha(bg, 0.10f)),
            floatArrayOf(0f, 0.30f, 0.62f, 1f), Shader.TileMode.CLAMP)
        v1.shader = LinearGradient(0f, 0f, 0f, heroH,
            intArrayOf(Theme.withAlpha(bg, 0.25f), Theme.withAlpha(bg, 0f), Theme.withAlpha(bg, 0.6f), bg),
            floatArrayOf(0f, 0.3f, 0.78f, 1f), Shader.TileMode.CLAMP)
        val vx = w * 0.70f
        val vy = heroH * 0.50f
        vignette.shader = RadialGradient(vx, vy, w * 0.55f,
            intArrayOf(Theme.withAlpha(bg, 0f), Theme.withAlpha(bg, 0f), Theme.withAlpha(bg, 0.45f), Theme.withAlpha(bg, 0.8f)),
            floatArrayOf(0f, 0.3f, 0.66f, 1f), Shader.TileMode.CLAMP).also {
            it.setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, 0.5f, vx, vy) })
        }
        brand.shader = RadialGradient(w * 0.75f, heroH * 0.35f, w * 0.55f,
            Theme.withAlpha(tint, 0.26f), Theme.withAlpha(tint, 0f), Shader.TileMode.CLAMP)
        // A wash of the art's colour low on the left, under the text.
        tintP.shader = RadialGradient(w * 0.18f, heroH * 0.78f, w * 0.7f,
            intArrayOf(Theme.withAlpha(tint, 0.30f), Theme.withAlpha(tint, 0.12f), Theme.withAlpha(tint, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP).also {
            it.setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, Theme.px(864f) / (0.7f * w), w * 0.18f, heroH * 0.78f) })
        }
    }

    private fun drawArt(c: Canvas, b: Bitmap, alpha: Float, p: Paint = bmp) {
        val w = width.toFloat()
        val heroH = Theme.px(800f)
        val target = w / heroH
        val ratio = b.width.toFloat() / b.height
        if (ratio > target) {
            val sw = (b.height * target).toInt()
            src.set((b.width - sw) / 2, 0, (b.width + sw) / 2, b.height)
        } else {
            val sh = (b.width / target).toInt()
            src.set(0, 0, b.width, sh)
        }
        // Idle drift: 100% to 112%, panning from the centre towards the left. Off when [p] is the still paint.
        val k = if (p === still || !Theme.moves) 1f else 1f + 0.12f * drift
        val dw = w * k
        val dh = heroH * k
        val fx = if (p === still) 0.5f else 0.5f - 0.3f * drift
        val left = -(dw - w) * fx
        dst.set(left, 0f, left + dw, dh)
        p.alpha = (alpha * 255).toInt()
        c.drawBitmap(b, src, dst, p)
    }

    override fun onDraw(c: Canvas) = paint(c)

    private val fill = Paint()

    fun paint(c: Canvas) {
        if (h1.shader == null) updateShaders()
        val w = width.toFloat()
        val heroH = Theme.px(800f)
        // Fills only the hero area.
        fill.color = Theme.bg
        c.drawRect(0f, 0f, w, heroH, fill)
        val cur = current
        val prev = previous
        if (cur == null && prev == null) {
            c.drawRect(0f, 0f, w, heroH, brand)
            c.drawRect(0f, 0f, w, heroH + 1, v1)
            return
        }
        c.save(); c.clipRect(0f, 0f, w, heroH)
        if (prev != null && fade < 1f) drawArt(c, prev, 1f - fade)
        if (cur != null) drawArt(c, cur, fade) else c.drawRect(0f, 0f, w, heroH, brand)
        c.drawRect(0f, 0f, w, heroH, tintP)
        c.restore()
        c.drawRect(0f, 0f, w, heroH, h1)
        c.drawRect(0f, 0f, w, heroH, vignette)
        c.drawRect(0f, 0f, w, heroH + 1, v1)
    }

    fun themeChanged() {
        if (current == null) tint = Theme.accent
        if (!Theme.moves) idle(false)
        updateShaders()
        invalidate()
        feedGlass()
    }
}

/**
 * The screen behind Home. Artwork mode shows a blurred, dimmed copy of the spotlight title's art,
 * cross-fading slower than the art itself. With no
 * art, or in accent mode, a soft accent gradient. Solid is the plain background.
 */
class Ambience(ctx: Context) : View(ctx) {
    var mode = 0
        set(v) {
            if (field == v) return
            field = v
            current = null; previous = null
            val b = source
            source = null
            set(b)
            invalidate()
        }
    /** False when Home has no spotlight. */
    var hero = true
        set(v) { if (field != v) { field = v; invalidate() } }
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var source: Bitmap? = null
    private var fade = 1f
    private var anim: ValueAnimator? = null
    private var offset = 0f
    private var heroAlpha = 1f
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val p = Paint(Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private companion object {
        /** Black over the art. */
        const val DIM = 0.78f
        const val HERO_H = 800f
        const val BAND = 320f
    }

    /** A new title's art. The blur is made once per title. */
    fun set(b: Bitmap?) {
        if (b === source) return
        source = b
        if (mode != 0 || width <= 0) return
        val next = if (b == null) null else Images.blurSync(width, height) { c -> cover(c, b, width.toFloat(), height.toFloat(), bmp, 1f) }
        anim?.cancel()
        // Keeps only the last two blurs.
        previous = if (fade >= 0.5f) current else previous
        current = next
        if (Theme.AMBIENT_MS == 0L) { fade = 1f; previous = null; invalidate(); return }
        fade = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Theme.AMBIENT_MS
            interpolator = Theme.EASE
            addUpdateListener { fade = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { previous = null }
            })
            start()
        }
    }

    /** Keeps the blend under the hero lined up while the spotlight scrolls and fades. */
    fun follow(dy: Float, alpha: Float) {
        if (dy == offset && alpha == heroAlpha) return
        offset = dy; heroAlpha = alpha
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val b = source
        source = null
        set(b)
    }

    private fun cover(c: Canvas, b: Bitmap, w: Float, h: Float, paint: Paint, alpha: Float) {
        val target = w / h
        val ratio = b.width.toFloat() / b.height
        if (ratio > target) {
            val sw = (b.height * target).toInt()
            src.set((b.width - sw) / 2, 0, (b.width + sw) / 2, b.height)
        } else {
            val sh = (b.width / target).toInt()
            src.set(0, (b.height - sh) / 2, b.width, (b.height + sh) / 2)
        }
        dst.set(0f, 0f, w, h)
        paint.alpha = (alpha * 255).toInt()
        c.drawBitmap(b, src, dst, paint)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(Theme.bg)
        val cur = current
        val prev = previous
        val art = mode == 0 && (cur != null || prev != null)
        if (art) {
            if (prev != null) cover(c, prev, w, h, bmp, if (cur == null) 1f - fade else 1f)
            if (cur != null) cover(c, cur, w, h, bmp, fade)
            c.drawColor(Theme.withAlpha(Color.BLACK, DIM))
            // Darker toward the bottom, where the app tiles sit.
            p.shader = LinearGradient(0f, h * 0.5f, 0f, h, Color.TRANSPARENT, Theme.withAlpha(Color.BLACK, 0.5f), Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.5f, w, h, p)
        } else if (mode != 2) {
            p.shader = RadialGradient(w * 0.8f, h * 0.15f, w * 0.7f, Theme.withAlpha(Theme.accent, 0.16f), Theme.withAlpha(Theme.accent, 0f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = RadialGradient(w * 0.1f, h * 1.05f, w * 0.6f, Theme.withAlpha(Theme.accent, 0.08f), Theme.withAlpha(Theme.accent, 0f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
        }
        p.shader = null
        // Scrolling down fades the whole layer to the plain background, as the hero fades.
        if (hero) {
            val t = ((1f - heroAlpha) / 0.55f).coerceIn(0f, 1f)
            if (t > 0f) c.drawColor(Theme.withAlpha(Theme.bg, t))
        }
        if (mode == 2 || !hero) return
        // The hero ends in the plain background; blend from there into this layer.
        val edge = Theme.px(HERO_H) + offset
        p.color = Theme.withAlpha(Theme.bg, heroAlpha)
        c.drawRect(0f, 0f, w, edge, p)
        p.shader = LinearGradient(0f, edge, 0f, edge + Theme.px(BAND), Theme.withAlpha(Theme.bg, heroAlpha), Theme.withAlpha(Theme.bg, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, edge, w, edge + Theme.px(BAND), p)
        p.shader = null
    }
}

/** Fades content out under the fixed top bar once the page has scrolled. */
class TopScrim(ctx: Context) : View(ctx) {
    private val p = Paint()
    override fun onDraw(c: Canvas) {
        // Solid behind the bar, then a short fade.
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(Theme.bg, Theme.bg, Theme.withAlpha(Theme.bg, 0f)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}

/** Thin pill progress bar used in the spotlight. */
class ProgressBar(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.color = Theme.withAlpha(Color.WHITE, 0.18f)
        c.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, p)
        Theme.accentFill(p, 0f, 0f, maxOf(h, width * progress), h)
        c.drawRoundRect(0f, 0f, maxOf(h, width * progress), h, h / 2, h / 2, p)
        p.shader = null
    }
}


/** Backdrop for the signed-out card: an accent glow over the surface, with the Stremio mark at the right. */
@SuppressLint("ViewConstructor")
class ConnectCard(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val mark = ctx.getDrawable(R.drawable.ic_stremio)


    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = Theme.px(Theme.R_CARD)
        box.set(0f, 0f, w, h)
        fill.shader = LinearGradient(0f, 0f, w, h, Theme.surface, Theme.withAlpha(Theme.accent, 0.35f), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, fill)
        glow.shader = RadialGradient(w * 0.82f, h * 0.5f, h * 0.9f, Theme.withAlpha(Theme.accent, 0.45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.save(); c.clipRect(box); c.drawRoundRect(box, r, r, glow); c.restore()
        mark?.let {
            val s = (h * 0.62f).toInt()
            val cx = (w * 0.82f).toInt(); val cy = (h * 0.5f).toInt()
            it.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
            it.draw(c)
        }
    }
}
