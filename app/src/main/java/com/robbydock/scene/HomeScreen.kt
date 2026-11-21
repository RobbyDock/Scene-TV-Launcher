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
        const val TILE = 168f
        const val TILE_H = 224f
        const val TILE_GAP = 44f
        /** Width of the name shown under a focused round button. */
        const val HINT_W = 400f
        /** Where the pinned row starts when the spotlight is shown. */
        const val PINNED_Y_WITH_SPOT = 1150f
        const val GRID_COLS = 8
        val TOP_KEYS = setOf("search", "mic", "gear", "avatar")
    }

    private val ambience = Ambience(act)
    private val backdrop = Backdrop(act)
    private val topScrim = TopScrim(act)
    private val content = FrameLayout(act).apply { clipChildren = false; clipToPadding = false }
    private val search = SearchPill(act)
    private val mic = RoundButton(act, R.drawable.ic_mic, true)
    private val info = InfoPill(act)
    private val gear = RoundButton(act, R.drawable.ic_settings, false)
    private val avatar = AvatarView(act)
    private val wifi = WifiIcon(act)

    // Spotlight views.
    private val spot = FrameLayout(act).apply { clipChildren = false }
    private val title = ScrollTitle(act)
    private val metaLine = act.label("", 26f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
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
    private var selected = 0
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
        if (Theme.CINEMA) {
            // A bare top bar: the search pill fits its text, the mic lives in Search, and nothing shows a ring.
            gear.bare = true
            for (v in listOf(search, gear, avatar)) v.ringless = true
            mic.visibility = GONE
            place(search, EDGE, 48f, search.fitWidth(), 88f)
        } else place(search, EDGE, 48f, 760f, 88f)
        place(mic, EDGE + 684f, 60f, 64f, 64f)
        place(info, 0f, 48f, -1f, 88f)
        place(gear, 1920f - EDGE - 88f, 48f, 88f, 88f)
        place(avatar, 1920f - EDGE - 200f, 48f, 88f, 88f)
        place(wifi, 0f, 68f, 48f, 48f)
        wifi.show(act.online, act.linkUp, animate = false)
        // The icon sits left of the clock and follows it.
        info.addOnLayoutChangeListener { _, l, _, _, _, _, _, _, _ ->
            val lp = wifi.layoutParams as LayoutParams
            val x = l - Theme.ipx(48f + 24f)
            if (lp.leftMargin != x) post { lp.leftMargin = x; wifi.requestLayout() }
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
        search.setOnClickListener { act.openSearch(false) }
        mic.setOnClickListener { act.openSearch(true) }
        gear.setOnClickListener { act.push(QuickPanel(act)) }
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
        search.nextFocusRightId = if (Theme.CINEMA) avatar.id else mic.id
        mic.nextFocusLeftId = search.id
        mic.nextFocusRightId = avatar.id
        avatar.nextFocusLeftId = if (Theme.CINEMA) search.id else mic.id
        avatar.nextFocusRightId = gear.id
        gear.nextFocusLeftId = avatar.id
        gear.nextFocusRightId = gear.id
        search.nextFocusLeftId = search.id

        viewTreeObserver.addOnGlobalFocusChangeListener(ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
            if (now == null) return@OnGlobalFocusChangeListener
            when {
                now === search || now === mic || now === gear || now === avatar -> scrollTo(0f)
                else -> scrollTarget[now]?.let { scrollTo(it) }
            }
        })
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

    fun weatherChanged() = info.apply(act.cfg, act.weather)

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
        primaryButton = null
        weatherChanged()

        val cfg = act.cfg
        var y: Float
        val pinnedTarget: Float
        // The spotlight needs a Stremio library. Signed out, a sign-in card takes its place.
        val hero = cfg.spotlight && Stremio.signedIn(act)
        if (hero) {
            buildSpotlight()
            y = 780f
            buildContinue(y)
            y = PINNED_Y_WITH_SPOT
            pinnedTarget = -(y - 560f)
        } else {
            art(null)
            y = if (cfg.spotlight) buildConnect(190f) + 64f else 220f
            pinnedTarget = 0f
        }
        pinTiles.clear()
        val rowsTop = y
        if (cfg.showPinned) y = buildPinned(y, pinnedTarget)
        if (cfg.showRecent) {
            // A second row scrolls further, so it sits where the first one would.
            val below = cfg.showPinned
            if (below) y += 56f
            y = buildRecent(y, pinnedTarget - (y - rowsTop))
        }
        if (!cfg.showPinned && !cfg.showRecent) y -= 56f
        buildGrid(y + 56f)
        if (selected >= act.continueItems.size) selected = 0
        if (hero) select(selected, immediate = true)

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
        content.place(ConnectCard(act), EDGE, y, 1920f - EDGE * 2, h)
        content.place(act.label("Bring your Stremio library home", 52f, Theme.TEXT, Theme.BOLD), EDGE + 64f, y + 60f, 1100f, 70f)
        content.place(act.label("Sign in with your phone to see Continue watching, new episodes and a full-screen spotlight for what you're watching.",
            28f, Theme.TEXT_DIM, Theme.REGULAR, 2), EDGE + 64f, y + 146f, 980f, 84f)
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val installed = Apps.isInstalled(act.packageManager, Config.STREMIO)
        val actions = listOfNotNull(
            Btn("Sign in", R.drawable.ic_qr_code_2) { act.openSignIn() },
            if (!installed) Btn("Get Stremio", R.drawable.ic_shop) { act.installStremio() } else null,
            Btn("Hide", R.drawable.ic_visibility_off) { act.cfg.spotlight = false; act.save(); rebuild() },
        )
        for ((i, b) in actions.withIndex()) {
            val pb = PillButton(act, b.label, b.icon, i == 0)
            pb.focusKey = "connect:$i"
            pb.id = View.generateViewId()
            pb.setOnClickListener { b.run() }
            row.addView(pb, LinearLayout.LayoutParams(-2, Theme.ipx(80f)).apply { rightMargin = Theme.ipx(20f) })
            keyed["connect:$i"] = pb
            scrollTarget[pb] = 0f
            if (i == 0) primaryButton = pb
        }
        content.place(row, EDGE + 64f, y + 280f, 1200f, 80f)
        linkTopRow()
        return y + h
    }

    /** Row title. Cinema sets it smaller and dimmer so the art leads. */
    private fun rowHeader(text: String) =
        if (Theme.CINEMA) act.label(text, 26f, Theme.TEXT_DIM, Theme.MEDIUM) else act.label(text, 34f, Theme.TEXT, Theme.SEMIBOLD)

    private fun buildSpotlight() {
        spot.removeAllViews()
        content.place(spot, EDGE, 190f, 1920f - EDGE * 2, 580f)
        // The title bleeds into the left margin.
        title.bleed = Theme.px(EDGE)
        if (Theme.CINEMA) {
            // Larger title; no genre chips, so the episode and a three-line synopsis move up.
            title.lineBox = Theme.px(116f)
            spot.place(title, -EDGE, 6f, 900f + EDGE, 148f)
            spot.place(metaLine, 0f, 136f, 900f, 34f)
            chips.visibility = GONE
            spot.place(episode, 0f, 196f, 900f, 38f)
            synopsis.maxLines = 3
            spot.place(synopsis, 0f, 246f, 860f, 120f)
        } else {
            spot.place(title, -EDGE, 20f, 900f + EDGE, 100f)
            spot.place(metaLine, 0f, 136f, 900f, 34f)
            spot.place(chips, 0f, 186f, 900f, 44f)
            spot.place(episode, 0f, 258f, 900f, 38f)
            spot.place(synopsis, 0f, 306f, 860f, 80f)
        }
        spot.place(loadingLines, 0f, 146f, 860f, 200f)
        spot.place(bar, 0f, 412f, 420f, 8f)
        spot.place(barLabel, 440f, 402f, 900f, 30f)
        spot.place(buttons, 0f, 456f, 1920f - EDGE * 2, 80f)
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
            if (Theme.CINEMA) { pb.bare = true; pb.ringless = true }
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
        gear.nextFocusDownId = search.nextFocusDownId
        avatar.nextFocusDownId = search.nextFocusDownId
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

    private fun buildContinue(y: Float) {
        val items = act.continueItems
        val header = rowHeader("Continue watching")
        content.place(header, EDGE, y, -1f, 44f)
        // Slightly smaller than full 16:9 width.
        val strip = Strip(act, 352f, 28f)
        content.place(strip, EDGE, y + 58f, 1920f - EDGE, 198f)
        if (items.isEmpty()) {
            val card = ArtCard(act).also { it.cinema = Theme.CINEMA }.apply {
                when {
                    !act.online -> {
                        title = "No connection"; subtitle = "Your progress shows when you're back online"; iconRes = R.drawable.ic_wifi
                        setOnClickListener { act.openSystemSettings(android.provider.Settings.ACTION_WIFI_SETTINGS) }
                    }
                    !Stremio.signedIn(act) -> {
                        title = "Sign in to Stremio"; subtitle = "Your progress shows up here"; iconRes = R.drawable.ic_account_circle
                        setOnClickListener { act.openSignIn() }
                    }
                    act.continueLoading -> {
                        title = ""; subtitle = ""; loading = true
                    }
                    act.continueError -> {
                        title = "Couldn't reach Stremio"; subtitle = "Press OK to try again"; iconRes = R.drawable.ic_refresh
                        setOnClickListener { act.refreshContinue(force = true) }
                    }
                    else -> {
                        title = "Nothing in progress"; subtitle = "Start something in Stremio"; iconRes = R.drawable.ic_movie
                        setOnClickListener { act.launch(Config.STREMIO) }
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
            val card = ArtCard(act).also { it.cinema = Theme.CINEMA }.apply {
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
            val url = m?.background ?: item.poster
            Images.load(url, Theme.ipx(400f)) { b -> if (cards.contains(card)) { card.loading = false; card.art = b } }
        }
        val cached = Stremio.cachedMeta(item.id)
        if (cached != null) apply(cached) else Net.first({ Stremio.meta(act, item.type, item.id) }) { apply(it) }
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
        tile.focusKey = key
        tile.setOnClickListener { act.launch(pkg) }
        tile.setOnLongClickListener { act.appMenu(pkg, tile.label, grid, prefix == "rec:"); true }
        keyed[key] = tile
        tile.invalidate()
    }

    private fun buildPinned(y: Float, target: Float): Float {
        val cfg = act.cfg
        val header = rowHeader("Pinned apps")
        content.place(header, EDGE, y, -1f, 44f)
        val strip = Strip(act, TILE, TILE_GAP)
        content.place(strip, EDGE, y + 72f, 1920f - EDGE, TILE_H)
        for ((i, pin) in cfg.pinned.withIndex()) {
            val tile = AppTile(act).also { it.cinema = Theme.CINEMA }
            bindApp(tile, pin.pkg, false)
            strip.add(tile)
            pinTiles.add(tile)
            if (i == 0) keyed["pin:0"] = tile
            scrollTarget[tile] = target
        }
        val add = AppTile(act).also { it.cinema = Theme.CINEMA }.apply {
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

    /** Apps last opened from Scene, newest first. Empty shows one line instead of tiles. */
    private fun buildRecent(y: Float, target: Float): Float {
        val list = act.recentApps()
        content.place(rowHeader("Recent apps"), EDGE, y, -1f, 44f)
        if (list.isEmpty()) {
            content.place(act.label("Apps you open from Home show here.", 28f, Theme.TEXT_DIM), EDGE, y + 64f, 1200f, 40f)
            return y + 64f + 40f
        }
        val strip = Strip(act, TILE, TILE_GAP)
        content.place(strip, EDGE, y + 72f, 1920f - EDGE, TILE_H)
        for ((i, pkg) in list.withIndex()) {
            val tile = AppTile(act).also { it.cinema = Theme.CINEMA }
            bindApp(tile, pkg, false, "rec:")
            strip.add(tile)
            if (i == 0) keyed.putIfAbsent("pin:0", tile)
            scrollTarget[tile] = target
        }
        return y + 72f + TILE_H
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
        keyed["sort"] = sort
        scrollTarget[sort] = -(y + 72f - 600f)
        gridTiles.clear()
        gridPkgs.clear()
        for ((i, e) in list.withIndex()) {
            val row = i / GRID_COLS
            val col = i % GRID_COLS
            val ty = y + 72f + row * (TILE_H + 40f)
            val tile = AppTile(act).also { it.cinema = Theme.CINEMA }
            bindApp(tile, e.pkg, true)
            content.place(tile, EDGE + col * (TILE + TILE_GAP), ty, TILE, TILE_H)
            gridTiles.add(tile)
            gridPkgs.add(e.pkg)
            if (i == 0) keyed["grid:0"] = tile
            scrollTarget[tile] = -(ty - 600f)
        }
    }

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
            act.cfg.appOrder = ArrayList(gridPkgs)
            act.cfg.appSort = 2
            rebuild("grid:$pkg")
        }
        val i = if (grid) gridPkgs.indexOf(pkg) else act.cfg.pinned.indexOfFirst { it.pkg == pkg }
        val tiles = if (grid) gridTiles else pinTiles
        val tile = tiles.getOrNull(i) ?: return
        moveIndex = i
        moveGrid = grid
        lift(tile, true)
        tile.requestFocus()
        // Every other tile in the row or grid wiggles.
        for (t in tiles) if (t !== tile) Motion.wiggle(t)
        if (moveHint.parent == null) place(moveHint, (1920f - 760f) / 2, 0f, 760f, 72f)
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
        act.cfg.pinned = ArrayList(undoPins)
        act.cfg.appSort = undoSort
        act.cfg.appOrder = ArrayList(undoOrder)
        act.save()
        moveHint.animate().alpha(0f).setDuration(Theme.FOCUS_MS).start()
        rebuild(pkg?.let { (if (grid) "grid:" else "pin:") + it })
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
        return onScreen.minByOrNull { (_, b) -> kotlin.math.abs((b[0] + b[2]) / 2 - fx) }?.first
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

    private fun select(index: Int, immediate: Boolean = false) {
        selected = index
        pendingSelect?.let { removeCallbacks(it) }
        val r = Runnable { showSpot(index) }
        pendingSelect = r
        if (immediate) r.run() else postDelayed(r, 180)
        // Up from any card goes to the first spotlight button; down from buttons to the selected card.
        val card = cards.getOrNull(index) ?: cards.firstOrNull()
        primaryButton?.let { pb ->
            for (c in cards) c.nextFocusUpId = pb.id
            for (i in 0 until buttons.childCount) buttons.getChildAt(i).nextFocusDownId = card?.id ?: View.NO_ID
        }
    }

    private fun showSpot(index: Int) {
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
        val meta = Stremio.cachedMeta(item.id)
        fillMeta(item, meta)
        if (meta == null) {
            Net.first({ Stremio.meta(act, item.type, item.id) }) { m ->
                if (selected != index || act.continueItems.getOrNull(index) !== item) return@first
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
        chips.visibility = shown
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
        chips.removeAllViews()
        meta?.genres?.forEach { g ->
            chips.addView(Chip(act, g), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Theme.ipx(12f) })
        }
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
        // Without the Stremio app nothing can play, so the main action installs it.
        val hasStremio = Apps.isInstalled(act.packageManager, Config.STREMIO)
        if (hasStremio) list.add(Btn(if (item.started) "Resume" else "Play", R.drawable.ic_play_arrow) { act.openDetail(item.type, item.id, item.videoId) })
        else list.add(Btn("Get Stremio", R.drawable.ic_shop) { act.installStremio() })
        val next = meta?.let { Stremio.nextVideo(it, item) }
        if (hasStremio && next != null && item.started) list.add(Btn("Next episode", R.drawable.ic_skip_next) { act.openDetail(item.type, item.id, next.id) })
        list.add(Btn(if (series) "Episodes" else "Details", if (series) R.drawable.ic_list else R.drawable.ic_info, true) { act.openDetail(item.type, item.id) })
        if (hasStremio) meta?.trailer?.let { yt -> list.add(Btn("Trailer", R.drawable.ic_movie, true) { act.openTrailer(yt, item.name) }) }
        if (Stremio.signedIn(act)) {
            list.add(Btn(if (series) "Mark episode watched" else "Mark watched", R.drawable.ic_check_circle, true) { act.markWatched(item) })
            list.add(if (item.inLibrary) Btn("Remove from library", R.drawable.ic_bookmark_added, true, "lib") { act.setInLibrary(item, false) }
                else Btn("Add to library", R.drawable.ic_bookmark_add, true, "lib") { act.setInLibrary(item, true) })
            if (series && item.inLibrary) {
                list.add(if (item.notify) Btn("Stop new episode alerts", R.drawable.ic_notifications_active, true, "notify") { act.setNotify(item, false) }
                    else Btn("Get new episode alerts", R.drawable.ic_notifications_off, true, "notify") { act.setNotify(item, true) })
            }
        }
        list.add(Btn("More options", R.drawable.ic_more_horiz, true) { act.continueMenu(item) })
        // Cinema keeps the main actions, Episodes, Trailer and More; the rest are in the More menu.
        if (Theme.CINEMA) list.retainAll { !it.round || it.icon == R.drawable.ic_list || it.icon == R.drawable.ic_info || it.icon == R.drawable.ic_movie || it.icon == R.drawable.ic_more_horiz }
        val focusedKey = (findFocus() as? FocusView)?.focusKey
        buildButtons(list)
        if (focusedKey?.startsWith("btn:") == true && findFocus() !== keyed[focusedKey]) keyed[focusedKey]?.focusQuietly()
        select2Focus()
        val url = meta?.background ?: item.poster
        if (url != null) {
            // Show the card's smaller copy at once, then swap in the full backdrop when it is ready.
            val full = Images.cached(url, Theme.ipx(1280f))
            if (full != null) setBackdrop(full)
            else {
                cards.getOrNull(selected)?.art?.let { setBackdrop(it) }
                Images.load(url, Theme.ipx(1280f)) { b -> if (b != null && act.continueItems.getOrNull(selected) === item) setBackdrop(b) }
            }
            prefetchBackdrop(selected + 1)
        }
    }

    /** Decodes the next title's backdrop ahead of time. */
    private fun prefetchBackdrop(index: Int) {
        val item = act.continueItems.getOrNull(index) ?: return
        val url = Stremio.cachedMeta(item.id)?.background ?: return
        if (Images.cached(url, Theme.ipx(1280f)) == null) Images.load(url, Theme.ipx(1280f)) { }
    }

    private fun newText(item: CwItem) = if (item.newCount == 1) "1 new episode" else "${item.newCount} new episodes"

    private fun select2Focus() {
        val card = cards.getOrNull(selected) ?: return
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).nextFocusDownId = card.id
        primaryButton?.let { pb -> for (c in cards) c.nextFocusUpId = pb.id }
    }

    private fun showEmptySpot() {
        loadingLines.visibility = GONE
        bar.visibility = INVISIBLE
        barLabel.translationX = 0f
        barLabel.text = ""
        chips.removeAllViews()
        episode.visibility = GONE
        metaLine.text = ""
        // Lifts the text and buttons when there are no meta lines.
        synopsis.translationY = -Theme.px(150f)
        buttons.translationY = -Theme.px(150f)
        metaLine.visibility = VISIBLE
        chips.visibility = VISIBLE
        synopsis.visibility = VISIBLE
        val list = ArrayList<Btn>()
        if (!act.online && Stremio.signedIn(act)) {
            title.text = "You're offline"
            synopsis.text = "Connect to Wi-Fi to see what's next. Your apps below still work."
            list.add(Btn("Wi-Fi settings", R.drawable.ic_wifi) { act.openSystemSettings(android.provider.Settings.ACTION_WIFI_SETTINGS) })
            list.add(Btn("Open Stremio", R.drawable.ic_play_arrow) { act.launch(Config.STREMIO) })
        } else if (!Stremio.signedIn(act)) {
            title.text = "Pick up where you left off"
            synopsis.text = "Sign in with your phone to see your shows and films here, with one press to resume."
            list.add(Btn("Sign in", R.drawable.ic_qr_code_2) { act.openSignIn() })
            list.add(Btn("Open Stremio", R.drawable.ic_play_arrow) { act.launch(Config.STREMIO) })
        } else {
            title.text = if (act.continueLoading) "Loading your library" else "Nothing in progress"
            synopsis.text = "Anything you start in Stremio appears here, ready to resume."
            list.add(Btn("Open Stremio", R.drawable.ic_play_arrow) { act.launch(Config.STREMIO) })
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

    private fun scrollTo(target: Float) {
        if (target == scroll && scrollAnim == null) return
        scrollAnim?.cancel()
        val from = scroll
        scrollAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = Theme.MEDIUM_MS
            interpolator = Theme.EASE
            addUpdateListener {
                scroll = it.animatedValue as Float
                content.translationY = Theme.px(scroll)
                val t = (-scroll / 400f).coerceIn(0f, 1f)
                spot.alpha = 1f - (t * 1.6f).coerceAtMost(1f)
                backdrop.alpha = 1f - t * 0.55f
                backdrop.translationY = Theme.px(scroll * 0.35f)
                ambience.follow(backdrop.translationY, backdrop.alpha)
                topScrim.alpha = t
                Glass.follow(backdrop.translationY, backdrop.alpha)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { scrollAnim = null }
            })
            start()
        }
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
class Strip(ctx: Context, private val itemW: Float, private val gap: Float, private val viewport: Float = 1920f - HomeScreen.EDGE * 2) : FrameLayout(ctx) {
    private var count = 0
    /** Called with the focused index. */
    var onIndex: ((Int) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
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

    private fun scrollToIndex(i: Int) {
        val pitch = itemW + gap
        val total = count * pitch - gap
        val max = (total - viewport).coerceAtLeast(0f)
        onIndex?.invoke(i)
        val target = -(i * pitch).coerceAtMost(max)
        animate().translationX(Theme.px(target)).setDuration(Theme.MEDIUM_MS).setInterpolator(Theme.EASE).start()
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
    private val src = Rect()
    private val dst = RectF()

    fun set(b: Bitmap?) {
        if (b === current) return
        previous = current
        current = b
        fade = 0f
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
        brand.shader = RadialGradient(w * 0.75f, heroH * 0.35f, w * 0.55f,
            Theme.withAlpha(Theme.accent, 0.20f), Theme.withAlpha(Theme.accent, 0f), Shader.TileMode.CLAMP)
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
        dst.set(0f, 0f, w, heroH)
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
        if (prev != null && fade < 1f) drawArt(c, prev, 1f - fade)
        if (cur != null) drawArt(c, cur, fade) else c.drawRect(0f, 0f, w, heroH, brand)
        c.drawRect(0f, 0f, w, heroH, h1)
        c.drawRect(0f, 0f, w, heroH + 1, v1)
    }

    fun themeChanged() {
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

    init { elevation = Theme.px(12f) ; outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, Theme.px(Theme.R_CARD)) }
    } }

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
