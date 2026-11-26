package com.robbydock.scene

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Split-view settings: grouped rows on the left, and a live preview or controls for the focused
 * row on the right. Previews are renders of the real Home screen. Nothing changes until OK.
 */
@SuppressLint("ViewConstructor")
class SettingsScreen(act: HomeActivity, private val initialFocus: String?) : OverlayBase(act, 1f), BackAware {

    private companion object {
        /** Pane titles whose settings live in each profile's own file. */
        val PER_PROFILE = setOf("Background", "True Dark", "Glass Strength", "Motion", "Focus Style", "Title Font", "Coming Up", "Clock & Weather", "Spotlight",
            "Pinned Apps", "Home Row", "All Apps Order", "Hidden Apps", "Hidden Titles", "App Rows", "App Categories", "Locked Apps", "Open on Start", "Your Library", "Export Settings", "Restore Settings", "Reset Home")
        /** Pane titles whose settings every profile shares. */
        val ALL_PROFILES = setOf("Profiles", "Show in App Launcher", "Sounds", "Watch With", "Stremio Account", "Jellyfin Account", "Plex Account", "Screensaver", "Now Playing", "Inputs")
        val FOCUS_NAMES = arrayOf("White Ring and Lift", "White Ring", "Accent Glow")
        const val GROUP_LABEL_H = 32f
        const val GROUP_GAP = 14f
        /** Space above the large title, which scrolls away with the list. */
        const val TITLE_TOP = 56f
        const val TITLE_H = 96f
        const val PANE_X = 984f
        const val PANE_Y = 96f
        /** Grows on screens wider than 16:9. */
        val PANE_W get() = Theme.W - PANE_X - 80f
        val PANE_H get() = Theme.H - PANE_Y - 40f
    }

    /** Keeps the focused row near the middle of the list. */
    private val scroll = object : ScrollView(act) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int {
            // A tapped row is already on screen; moving it would put the next tap on another row.
            if (isInTouchMode) return 0
            if (childCount == 0) return 0
            val max = (getChildAt(0).height - height).coerceAtLeast(0)
            val target = (rect.centerY() - height * 0.45f).toInt().coerceIn(0, max)
            return target - scrollY
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        isSmoothScrollingEnabled = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(Theme.ipx(48f))
    }
    /** Clips the list and, once scrolled, fades its top out under the small title. */
    private val listBox = object : FrameLayout(act) {
        var fade = 0f
            set(v) { if (field != v) { field = v; invalidate() } }
        private val mask = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }

        init { addView(scroll, LayoutParams(-1, -1)) }

        override fun dispatchDraw(c: android.graphics.Canvas) {
            if (fade <= 0f) { super.dispatchDraw(c); return }
            // Hidden under the small title, then a long eased fade back in.
            val top = Theme.px(64f)
            val h = Theme.px(220f)
            val s = c.saveLayer(0f, 0f, width.toFloat(), h, null)
            super.dispatchDraw(c)
            val a = fade * 255
            fun k(v: Float) = (a * v).toInt() shl 24
            mask.shader = android.graphics.LinearGradient(0f, top, 0f, h,
                intArrayOf(k(1f), k(0.82f), k(0.5f), k(0.18f), 0), floatArrayOf(0f, 0.2f, 0.45f, 0.72f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), h, mask)
            c.restoreToCount(s)
            c.save()
            c.clipRect(0f, h, width.toFloat(), height.toFloat())
            super.dispatchDraw(c)
            c.restore()
        }
    }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val pane = FrameLayout(act).apply { clipChildren = false }
    /** Small title that settles at the top once the large one has scrolled away. */
    private val smallTitle = act.label("Settings", 30f, Theme.TEXT, Theme.SEMIBOLD).apply { alpha = 0f }
    private val rows = LinkedHashMap<String, SettingRow>()
    private val rowIcons = HashMap<String, Int>()
    private var current: String? = null
    private var backupStatus = ""
    /** Progress or a problem from the last update step, shown in the Updates pane. */
    private var updateStatus = ""
    private var pendingPreview: Runnable? = null
    private var glassPreview: GlassPreview? = null

    init {
        place(Backdrop(), 0f, 0f, Theme.W, Theme.H)
        // The large title is the list's first item and scrolls with it.
        place(listBox, 36f, 0f, 936f, Theme.H)
        scroll.addView(list, LayoutParams(-1, -2))
        // Side padding leaves room for the card shadows inside the clip.
        list.setPadding(Theme.ipx(60f), Theme.ipx(TITLE_TOP), Theme.ipx(60f), Theme.ipx(120f))
        place(smallTitle, 96f, 30f, 600f, 40f)
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val y = scroll.scrollY.toFloat()
            val th = Theme.px(TITLE_H)
            // First the large title shrinks and fades as it scrolls away.
            list.getChildAt(0)?.let { big ->
                val k = (y / th).coerceIn(0f, 1f)
                big.pivotX = 0f
                big.pivotY = big.height.toFloat()
                big.scaleX = 1f - 0.3f * k
                big.scaleY = big.scaleX
                big.alpha = 1f - k
            }
            // Then a small title settles at the top and stays.
            val inT = ((y - th * 0.6f) / (th * 0.5f)).coerceIn(0f, 1f)
            smallTitle.alpha = inT
            smallTitle.translationY = Theme.px(10f) * (1f - inT)
            listBox.fade = (y / Theme.px(48f)).coerceIn(0f, 1f)
        }
        place(pane, PANE_X, PANE_Y, PANE_W, PANE_H)
        build()
        post { (rows[initialFocus ?: "accent"] ?: rows.values.first()).requestFocus() }
    }

    /** Accent light behind the screen and a vignette toward the corners. */
    private inner class Backdrop : View(act) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        private fun glow(c: android.graphics.Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, a: Float) {
            c.save()
            c.translate(Theme.px(cx), Theme.px(cy))
            c.scale(1f, ry / rx)
            p.shader = android.graphics.RadialGradient(0f, 0f, Theme.px(rx), Theme.withAlpha(color, a), color and 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, Theme.px(rx), p)
            c.restore()
        }

        override fun onDraw(c: android.graphics.Canvas) {
            glow(c, 1498f, 194f, 770f, 532f, Theme.accent, 0.13f)
            glow(c, 115f, 1037f, 630f, 490f, Theme.duo(Theme.accent), 0.09f)
            // Vignette toward the corners.
            c.save()
            c.translate(width / 2f, height / 2f)
            c.scale(1f, 900f / 1400f)
            val vr = Theme.px(1400f)
            p.shader = android.graphics.RadialGradient(0f, 0f, vr, intArrayOf(0, 0, 0x59000000), floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(-vr * 2, -vr * 2, vr * 2, vr * 2, p)
            c.restore()
        }
    }

    override fun onBack(): Boolean {
        // Back from the right-hand pane returns to the row instead of closing.
        val f = findFocus()
        if (f != null && isInPane(f)) {
            rows[current]?.requestFocus()
            return true
        }
        return false
    }

    /**
     * Up and down inside the pane stay in the pane. Left moves within the pane while it can, then
     * returns to the row the pane belongs to, wherever the list has scrolled.
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (focused != null && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) && isInPane(focused)) {
            return android.view.FocusFinder.getInstance().findNextFocus(pane, focused, direction) ?: focused
        }
        if (focused != null && direction == View.FOCUS_LEFT && isInPane(focused)) {
            return android.view.FocusFinder.getInstance().findNextFocus(pane, focused, direction) ?: rows[current] ?: super.focusSearch(focused, direction)
        }
        return super.focusSearch(focused, direction)
    }

    private fun isInPane(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === pane) return true
            p = p.parent as? View
        }
        return false
    }

    // ---------------------------------------------------------------- list

    private fun build() {
        val keep = current
        list.removeAllViews()
        rows.clear()
        val cfg = act.cfg
        first = true
        list.addView(act.label("Settings", 72f, Theme.TEXT, Theme.BOLD).apply { setPadding(Theme.ipx(8f), 0, 0, 0) },
            LinearLayout.LayoutParams(-1, Theme.ipx(TITLE_H)).apply { bottomMargin = Theme.ipx(24f) })

        group("Profile")
        val prof = Profiles.current
        row("profile", R.drawable.ic_account_circle, prof.name,
            if (Profiles.list.size == 1) "1 profile" else "${Profiles.list.size} profiles") { paneProfiles() }.apply {
            avatar = prof
            setOnClickListener { pane.findViewWithTag<View>("profav${Profiles.list.indexOf(Profiles.current)}")?.requestFocus() }
        }
        endGroup()

        group("Watching")
        val src = Watch.source(act)
        row("watch", src.icon, "Watch With", src.label) { paneWatch() }.apply {
            brandIcon = true
            setOnClickListener { pane.findViewWithTag<View>("watch${Source.values().indexOf(Watch.source(act))}")?.requestFocus() }
        }
        val signed = Watch.signedIn(act)
        row("account", src.icon, "${src.label} Account", if (signed) (Watch.account(act) ?: "Signed in") else "Not signed in") { paneAccount() }.apply {
            brandIcon = true
            setOnClickListener { if (Watch.signedIn(act)) pane.findViewWithTag<View>("acct0")?.requestFocus() else WatchSignIn.start(act, Watch.source(act)) }
        }
        row("spot", R.drawable.ic_movie, "Spotlight", "") { paneSpotlight() }.apply {
            trail = SettingRow.Trail.SWITCH
            checked = cfg.spotlight
            setOnClickListener { cfg.spotlight = !cfg.spotlight; act.save(); act.home.rebuild(); refresh("spot") }
        }
        if (Watch.isStremio(act)) row("coming", R.drawable.ic_event_upcoming, "Coming Up", comingSummary()) { paneComing() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("coming0")?.requestFocus() }
        }
        val cwCount = act.hiddenContinueCount
        row("cwhidden", R.drawable.ic_movie_off, "Hidden Titles", if (cwCount == 0) "None" else "$cwCount hidden") { paneCwHidden() }.apply {
            trail = SettingRow.Trail.NONE
            setOnClickListener { if (act.hiddenContinueCount > 0) { act.unhideAllContinue(); refresh("cwhidden") } }
        }
        endGroup()

        group("Home Screen")
        row("clock", R.drawable.ic_schedule, "Clock & Weather", clockSummary()) { paneClock() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("opt0")?.requestFocus() }
        }
        // Left out on TVs that can't allow the access it needs.
        if (NowPlaying.available(act)) row("music", R.drawable.ic_music_note, "Now Playing", musicSummary()) { paneMusic() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("music0")?.requestFocus() }
        }
        row("homerow", R.drawable.ic_apps, "Home Row", homeRowSummary()) { paneHomeRow() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("homerow0")?.requestFocus() }
        }
        row("rows", R.drawable.ic_view_agenda, "App Rows", if (cfg.rows.isEmpty()) "None" else cfg.rows.joinToString(", ") { it.name }) { paneRows() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("rows0")?.requestFocus() }
        }
        row("start", R.drawable.ic_power_settings_new, "Open on Start", startName() ?: "Nothing") { paneStart() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("start0")?.requestFocus() }
        }
        endGroup()

        group("Apps")
        row("pins", R.drawable.ic_push_pin, "Pinned Apps", "${cfg.pinned.size} pinned") { panePinned() }.apply {
            setOnClickListener { act.push(AppPicker(act)) }
        }
        val hiddenCount = hiddenInstalled().size
        row("hidden", R.drawable.ic_visibility_off, "Hidden Apps", if (hiddenCount == 0) "None" else "$hiddenCount hidden") { paneHidden() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("hidden0")?.requestFocus() }
        }
        row("sort", R.drawable.ic_sort_by_alpha, "All Apps Order", arrayOf("A to Z", "Recently installed", "Custom")[cfg.appSort]) { paneSort() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("sort0")?.requestFocus() }
        }
        row("locks", R.drawable.ic_lock, "Locked Apps", if (cfg.locked.isEmpty()) "None" else "${cfg.locked.size} locked") { paneLocks() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("locks0")?.requestFocus() }
        }
        row("appicon", R.drawable.ic_home_app_logo, "Show in App Launcher", "") { paneAppIcon() }.apply {
            trail = SettingRow.Trail.SWITCH
            checked = iconShown()
            setOnClickListener { showIcon(!iconShown()); refresh("appicon") }
        }
        endGroup()

        group("Appearance")
        row("accent", R.drawable.ic_palette, "Accent Colour", Theme.ACCENTS[cfg.accent].name) { paneAccent() }.apply {
            swatch = Theme.accent
            setOnClickListener { pane.findViewWithTag<View>("swatch${cfg.accent}")?.requestFocus() }
        }
        row("font", R.drawable.ic_edit, "Title Font", Theme.TITLE_FONTS[cfg.titleFont].name) { paneFont() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("font${cfg.titleFont}")?.requestFocus() }
        }
        row("dark", R.drawable.ic_dark_mode, "True Dark", "") { paneDark() }.apply {
            trail = SettingRow.Trail.SWITCH
            checked = cfg.trueDark
            setOnClickListener { cfg.trueDark = !cfg.trueDark; applyTheme("dark") }
        }
        row("glass", R.drawable.ic_opacity, "Glass Strength", "") { paneGlass() }.apply {
            trail = SettingRow.Trail.CHEVRON
            value = "${Math.round(cfg.glass * 100)}%"
            setOnClickListener { pane.findViewWithTag<View>("glassbar")?.requestFocus() }
        }
        row("sounds", R.drawable.ic_volume_up, "Sounds", "") { paneSounds() }.apply {
            trail = SettingRow.Trail.SWITCH
            checked = Sounds.on
            setOnClickListener { Sounds.set(act, !Sounds.on); refresh("sounds") }
        }
        row("motion", R.drawable.ic_auto_awesome, "Motion", arrayOf("Full", "Reduced", "Off")[cfg.motion]) { paneMotion() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("motion0")?.requestFocus() }
        }
        row("focus", R.drawable.ic_dashboard_customize, "Focus Style", FOCUS_NAMES[cfg.focusStyle]) { paneFocus() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("focus${cfg.focusStyle}")?.requestFocus() }
        }
        endGroup()

        group("Device")
        row("dream", R.drawable.ic_schedule, "Screensaver", dreamSummary()) { paneDream() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("dream0")?.requestFocus() }
        }
        val inputs = Inputs.list(act)
        if (inputs.isNotEmpty()) row("inputs", R.drawable.ic_input, "Inputs",
            if (Inputs.showOnHome(act)) "${inputs.count { !it.hidden }} on Home" else "Off") { paneInputs() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("inputs0")?.requestFocus() }
        }
        row("remote", R.drawable.ic_settings_remote, "Remote Buttons", remoteSummary()) { paneRemote() }.apply {
            setOnClickListener { pane.findViewWithTag<View>("remote0")?.requestFocus() }
        }
        endGroup()

        group("System")
        row("export", R.drawable.ic_backup, "Export Settings", "") { paneBackup(true) }.apply {
            trail = SettingRow.Trail.NONE
            setOnClickListener {
                val write = {
                    backupStatus = if (Store.export(act, act.cfg)) "Saved to ${Store.backupPath(act)}" else "Couldn't write the backup file."
                    paneBackup(true)
                }
                if (Store.hasBackup(act)) {
                    confirm("Replace the backup?", "A backup file is already on this TV", "Replace backup", R.drawable.ic_backup,
                        "The old file is overwritten with ${Profiles.current.name}'s settings") { write() }
                } else write()
            }
        }
        row("restore", R.drawable.ic_restore, "Restore Settings", "") { paneBackup(false) }.apply {
            trail = SettingRow.Trail.NONE
            setOnClickListener {
                val c = Store.restore(act)
                if (c == null) {
                    backupStatus = "${Store.restoreProblem ?: "No valid backup."} File: ${Store.backupPath(act)}"
                    paneBackup(false)
                } else {
                    confirm("Restore settings?", "Replaces ${Profiles.current.name}'s Home settings", "Restore from backup", R.drawable.ic_restore,
                        "Pinned apps, colours and layout come from the file. This can't be undone.") {
                        backupStatus = "Settings restored."
                        act.replaceConfig(c)
                        refresh("restore")
                    }
                }
            }
        }
        row("setup", R.drawable.ic_rocket_launch, "Run Setup Again", "") { paneSetup() }.apply {
            trail = SettingRow.Trail.NONE
            setOnClickListener {
                confirm("Run setup again?", "Settings close and setup starts from the beginning", "Start setup", R.drawable.ic_rocket_launch,
                    "Each step can still be skipped") { act.runSetup() }
            }
        }
        row("reset", R.drawable.ic_refresh, "Reset Home", "") { paneReset() }.apply {
            trail = SettingRow.Trail.NONE
            setOnClickListener {
                confirm("Reset ${Profiles.current.name}'s Home?", "This can't be undone", "Reset Home", R.drawable.ic_refresh,
                    "True Dark, layout options, hidden apps and sorting go back to the defaults") {
                    val fresh = Config().apply {
                        hidden.add(Config.STREMIO_BOOT)
                        // The accent is the profile's colour.
                        accent = Profiles.current.color
                        city = act.cfg.city; lat = act.cfg.lat; lon = act.cfg.lon
                        for (p in act.cfg.pinned) pinned.add(p)
                        names.putAll(act.cfg.names)
                    }
                    act.replaceConfig(fresh)
                    refresh("reset")
                    act.toast("Home reset")
                }
            }
        }
        row("update", R.drawable.ic_update, "Updates", updateSummary()) { paneUpdates() }.apply {
            trail = SettingRow.Trail.NONE
            if (Updates.shouldNotify(act)) { pill = "New"; pillAccent = true }
            setOnClickListener { pane.findViewWithTag<View>("upd0")?.requestFocus() }
        }
        row("about", R.drawable.ic_info, "About", versionName()) { paneAbout() }.apply {
            trail = SettingRow.Trail.NONE
            setOnClickListener { pane.findViewWithTag<View>("about0")?.requestFocus() }
            // Holding OK turns Demo Mode on or off.
            setOnLongClickListener {
                act.pop()
                if (Demo.on) act.endDemo() else act.startDemo()
                true
            }
        }
        row("kofi", R.drawable.ic_favorite, "Support on Ko-fi", "") { paneKofi() }.apply { trail = SettingRow.Trail.HEART }
        endGroup()

        if (keep != null) post { rows[keep]?.requestFocus() }
    }

    private var groupView: LinearLayout? = null
    private var first = true

    private fun group(title: String) {
        list.addView(act.label(title, 24f, Theme.TEXT_DIM, Theme.SEMIBOLD).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.ipx(32f), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, Theme.ipx(GROUP_LABEL_H)).apply {
            topMargin = if (first) 0 else Theme.ipx(36f)
            bottomMargin = Theme.ipx(GROUP_GAP)
        })
        first = false
        val g = object : LinearLayout(act) {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) = drawCard(c, p, width, height, Theme.surface)
        }.apply {
            orientation = LinearLayout.VERTICAL
            setWillNotDraw(false)
            clipChildren = false
        }
        list.addView(g, LinearLayout.LayoutParams(-1, -2))
        groupView = g
    }

    private fun endGroup() { groupView = null }

    /** Adds a row to the open group. Focus shows its pane. */
    private fun row(key: String, icon: Int, title: String, sub: String, pane: () -> Unit): SettingRow {
        val g = groupView!!
        val r = SettingRow(act, icon, title).apply {
            subtitle = sub
            divider = g.childCount > 0
            lean = true
            focusKey = key
            onFocused = { if (!clearingPane) { current = key; markSelected(); pane() } }
        }
        g.addView(r, LinearLayout.LayoutParams(-1, Theme.ipx(112f)))
        rows[key] = r
        rowIcons[key] = icon
        return r
    }

    /** Marks the open row, and hides the divider under it. */
    private fun markSelected() {
        var prev = false
        for ((k, r) in rows) {
            r.marked = k == current
            r.aboveSelected = prev
            prev = r.marked
        }
    }

    /** Tag of the pane control that last had focus, or null when focus was in the list. */
    private var lastPaneTag: Any? = null

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        lastPaneTag = focused?.takeIf { isInPane(it) }?.tag
    }

    /** Back from Android's settings: redraws, so a permission just allowed shows at once. */
    fun onResumed() { current?.let { refresh(it) } }

    /** Rebuilds the list after a change, keeping focus on [key], or on the pane control in use. */
    private fun refresh(key: String) {
        val restore = if (key == current) lastPaneTag else null
        current = key
        build()
        invalidateAll()
        if (restore != null) post { post { pane.findViewWithTag<View>(restore)?.requestFocus() } }
    }

    private fun applyTheme(key: String) {
        act.themeChanged()
        refresh(key)
    }

    private fun versionName(): String = try {
        act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    // ---------------------------------------------------------------- pane helpers

    /**
     * Pane top: a large title and, optionally, one short line.
     */
    private fun paneHeader(title: String, body: String = "") {
        pendingPreview?.let { removeCallbacks(it) }
        glassPreview = null
        // Removing the focused control can hand focus to a list row, which must not draw its own pane into this one.
        clearingPane = true
        pane.removeAllViews()
        clearingPane = false
        pane.place(act.label(title, 48f, Theme.TEXT, Theme.BOLD).apply { letterSpacing = -0.01f }, 48f, 40f, PANE_W - 96f, 62f)
        // Says whether a change stays with this profile or reaches every profile.
        val scope = when (title) {
            in PER_PROFILE -> "Per profile"
            in ALL_PROFILES -> "All profiles"
            else -> null
        }
        if (scope != null) {
            val box = FrameLayout(act)
            box.addView(Chip(act, scope, tinted = true).apply { contentDescription = scope }, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.END))
            pane.place(box, 48f, 50f, PANE_W - 96f, 44f)
        }
        if (body.isNotEmpty()) pane.place(act.label(body, 24f, Theme.TEXT_DIM, Theme.REGULAR, 1), 48f, 114f, PANE_W - 96f, 34f)
        Motion.enter(pane)
    }

    /** Quiet line at the foot of the pane saying what OK does. */
    private fun paneHint(text: String) {
        pane.place(act.label(text, 22f, Theme.TEXT_DIM, Theme.MEDIUM).apply { alpha = 0.8f }, 48f, PANE_H - 52f, PANE_W - 96f, 32f)
    }

    /** A rounded card that holds a column of rows. */
    private fun cardColumn(): LinearLayout = object : LinearLayout(act) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: android.graphics.Canvas) = drawCard(c, p, width, height, Theme.elev)
    }.apply {
        orientation = LinearLayout.VERTICAL
        setWillNotDraw(false)
        clipChildren = false
    }

    private fun preview(
        x: Float, y: Float, w: Float,
        accent: Int = act.cfg.accent, dark: Boolean = act.cfg.trueDark, glass: Float = act.cfg.glass, spot: Boolean = act.cfg.spotlight,
    ): MiniHome = MiniHome(act).apply {
        accentIndex = accent; this.dark = dark; this.glass = glass; spotlight = spot
    }.also { pane.place(it, x, y, w, w * 9f / 16f) }

    private fun centredText(text: String, size: Float, color: Int, face: android.graphics.Typeface = Theme.REGULAR): TextView =
        act.label(text, size, color, face).apply { gravity = Gravity.CENTER_HORIZONTAL }

    // ---------------------------------------------------------------- pane building blocks

    /** Where content starts under a header with no sub line, and with one. */
    private val top0 = 150f
    private val top1 = 192f

    /** Small section label inside the pane. */
    private fun section(text: String, y: Float) {
        pane.place(act.label(text, 24f, Theme.TEXT_DIM, Theme.SEMIBOLD), 56f, y, PANE_W - 112f, 32f)
    }

    /** Rounded card with label and value lines. Returns the bottom edge. */
    private fun infoCard(lines: List<Pair<String, String>>, y: Float): Float {
        val card = cardColumn()
        card.setPadding(Theme.ipx(32f), Theme.ipx(14f), Theme.ipx(32f), Theme.ipx(14f))
        for ((i, kv) in lines.withIndex()) {
            val line = FrameLayout(act)
            line.place(act.label(kv.first, 25f, Theme.TEXT_DIM), 0f, 0f, 260f, 64f).also { (it as TextView).gravity = Gravity.CENTER_VERTICAL }
            line.place(act.label(kv.second, 25f, Theme.TEXT, Theme.MEDIUM).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }, 260f, 0f, PANE_W - 96f - 64f - 260f, 64f)
            card.addView(line, LinearLayout.LayoutParams(-1, Theme.ipx(64f)))
            if (i < lines.size - 1) card.addView(View(act).apply { setBackgroundColor(Theme.withAlpha(Theme.outline, 0.6f)) }, LinearLayout.LayoutParams(-1, Theme.ipx(1.5f)))
        }
        val h = 28f + lines.size * 64f + (lines.size - 1) * 1.5f
        pane.place(card, 48f, y, PANE_W - 96f, h)
        return y + h
    }

    /** Empty state: an icon in a tinted circle with one line under it. */
    private fun emptyState(icon: Int, text: String, y: Float = 260f) {
        pane.place(IconBadge(act, icon), (PANE_W - 120f) / 2, y, 120f, 120f)
        pane.place(centredText(text, 26f, Theme.TEXT_DIM), 48f, y + 152f, PANE_W - 96f, 80f).also { (it as TextView).maxLines = 2 }
    }

    /**
     * Signed out: setup's picture of Home with Continue watching shows what signing in adds, then
     * the service, one line on how it works, and the button.
     */
    private fun signedOutPane(brand: Int, title: String, hint: String, button: String, buttonIcon: Int, go: () -> Unit) {
        // The picture's TV starts 153 units down its 820-unit box.
        val art = OnboardArt(act).apply { spotlight = true }
        pane.place(art, (PANE_W - 800f) / 2, top0 - 143f, 800f, 820f)
        art.show(OnboardArt.Kind.STREMIO, 0)
        val y = top0 + 560f
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        head.addView(ImageView(act).apply { setImageResource(brand) }, LinearLayout.LayoutParams(Theme.ipx(44f), Theme.ipx(44f)).apply { rightMargin = Theme.ipx(16f) })
        head.addView(act.label(title, 34f, Theme.TEXT, Theme.SEMIBOLD), LinearLayout.LayoutParams(-2, -2))
        pane.place(head, 48f, y, PANE_W - 96f, 52f)
        pane.place(centredText(hint, 24f, Theme.TEXT_DIM), 48f, y + 62f, PANE_W - 96f, 36f)
        val b = PillButton(act, button, buttonIcon, true)
        b.tag = "acct0"
        b.setOnClickListener { go() }
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        row.addView(b, LinearLayout.LayoutParams(-2, Theme.ipx(80f)))
        pane.place(row, 0f, y + 128f, PANE_W, 80f)
        Motion.stagger(listOf(head, row), 60L)
    }

    private fun updateSummary(): String {
        val r = Updates.available(act)
        return if (r != null) "Version ${r.version} available" else "Up to date  ·  ${versionName()}"
    }

    /**
     * Updates from GitHub: the installed and newest versions, install or skip, and the daily check.
     * Nothing installs without Android's own confirmation.
     */
    private fun paneUpdates() {
        paneHeader("Updates", "Checks GitHub once a day, only while Home is open.")
        val r = Updates.available(act)
        val lastLook = Updates.lastCheck(act).let { if (it == 0L) "Not checked yet" else "Checked " + android.text.format.DateUtils.getRelativeTimeSpanString(it).toString().replaceFirstChar { c -> c.lowercase() } }
        var y = infoCard(listOf("Installed" to versionName(), "Newest" to (r?.version ?: "This one")), top1)
        val add = optionColumn(y + 20f, "upd")
        if (r != null) {
            val mb = if (r.size > 0) "%.1f MB".format(r.size / 1_048_576f) else ""
            add(R.drawable.ic_update, "Install ${r.version}", mb, SettingRow.Trail.NONE) {
                if (!Updates.canInstall(act)) {
                    val go = Updates.permissionIntent(act)
                    if (go == null) { updateStatus = "This TV has no setting to let Scene install apps."; refresh("update"); return@add }
                    confirm("Allow Scene to install updates?", "A one-time Android setting", "Open setting", R.drawable.ic_open_in_new,
                        "Turn on Allow from this source for Scene, then come back") { act.start(go, "Can't open the setting") }
                    return@add
                }
                Updates.install(act, r) { s -> updateStatus = s; if (current == "update") refresh("update") }
            }
            if (Updates.skipped(act) != r.version) add(R.drawable.ic_close, "Skip This Version", "", SettingRow.Trail.NONE) {
                Updates.skip(act, r.version); act.home.updateBadge(); refresh("update")
            }
        }
        add(R.drawable.ic_sync, "Check Now", lastLook, SettingRow.Trail.NONE) {
            updateStatus = "Checking GitHub"
            refresh("update")
            Updates.check(act) { ok ->
                updateStatus = if (!ok) "Couldn't reach GitHub." else if (Updates.available(act) == null) "Scene is up to date." else ""
                act.home.updateBadge()
                if (current == "update") refresh("update")
            }
        }
        add(R.drawable.ic_schedule, "Check Automatically", "", SettingRow.Trail.SWITCH) {
            Updates.setAuto(act, !Updates.auto(act)); refresh("update")
        }.checked = Updates.auto(act)
        if (updateStatus.isNotEmpty()) paneHint(updateStatus)
    }

    /** Rounded card for a preview or short content, on the surface colour. */
    private fun surfaceCard(x: Float, y: Float, w: Float, h: Float): Panel =
        Panel(act, Theme.R_CARD, { Theme.surface }, false).also { pane.place(it, x, y, w, h) }

    // ---------------------------------------------------------------- panes

    private fun paneAccent() {
        val cfg = act.cfg
        paneHeader("Accent Colour")
        val pw = 600f
        val big = preview((PANE_W - pw) / 2, top0, pw)
        val name = centredText(Theme.ACCENTS[cfg.accent].name, 30f, Theme.TEXT, Theme.SEMIBOLD)
        pane.place(name, 48f, top0 + pw * 9f / 16f + 22f, PANE_W - 96f, 40f)
        val cols = 7
        val w = 72f
        val gap = 30f
        val rowW = cols * w + (cols - 1) * gap
        val x0 = (PANE_W - rowW) / 2
        val y0 = top0 + pw * 9f / 16f + 90f
        val swatches = ArrayList<View>()
        for (i in Theme.ACCENTS.indices) {
            val s = Swatch(act, i).apply {
                chosen = i == cfg.accent
                tag = "swatch$i"
                setOnFocusChangeListener { _, has ->
                    if (!has) return@setOnFocusChangeListener
                    name.text = Theme.ACCENTS[i].name
                    big.accentIndex = i
                    big.outlined = true
                }
                setOnClickListener {
                    cfg.accent = i
                    // The profile's picture follows its colour.
                    Profiles.current.color = i
                    Profiles.save(act)
                    act.themeChanged()
                    refresh("accent")
                    post { pane.findViewWithTag<View>("swatch$i")?.requestFocus() }
                }
            }
            pane.place(s, x0 + (i % cols) * (w + gap), y0 + (i / cols) * 96f, w, w)
            swatches.add(s)
        }
        Motion.stagger(swatches, 14L, 14)
    }

    private fun twoUp(left: String, right: String, leftOn: Boolean, a: MiniHome.() -> Unit, b: MiniHome.() -> Unit, y: Float = top1): Pair<MiniHome, MiniHome> {
        val w = 360f
        val gap = 40f
        val x0 = (PANE_W - w * 2 - gap) / 2
        val l = preview(x0, y, w).apply(a).apply { outlined = leftOn }
        val r = preview(x0 + w + gap, y, w).apply(b).apply { outlined = !leftOn }
        val labelY = y + w * 9f / 16f + 22f
        pane.place(centredText(left, 26f, if (leftOn) Theme.TEXT else Theme.TEXT_DIM, Theme.MEDIUM), x0, labelY, w, 36f)
        pane.place(centredText(right, 26f, if (!leftOn) Theme.TEXT else Theme.TEXT_DIM, Theme.MEDIUM), x0 + w + gap, labelY, w, 36f)
        Motion.stagger(listOf(l, r), 60L)
        return l to r
    }

    private fun paneDark() {
        val cfg = act.cfg
        paneHeader("True Dark")
        val (a, b) = twoUp("Dark", "True Dark", !cfg.trueDark, { dark = false }, { dark = true })
        a.makeFocusable(true)
        b.makeFocusable(true)
        a.setOnClickListener { if (cfg.trueDark) { cfg.trueDark = false; applyTheme("dark") } }
        b.setOnClickListener { if (!cfg.trueDark) { cfg.trueDark = true; applyTheme("dark") } }
    }

    private fun paneGlass() {
        val cfg = act.cfg
        paneHeader("Glass Strength")
        val w = PANE_W - 96f
        val ph = w * 9f / 16f
        glassPreview = GlassPreview(act).apply { strength = cfg.glass }.also { pane.place(it, 48f, top0, w, ph) }
        val bar = GlassBar(act).apply {
            tag = "glassbar"
            value = cfg.glass
            onChange = { v ->
                cfg.glass = v
                Theme.apply(cfg.accent, cfg.trueDark, cfg.glass)
                act.save()
                act.home.invalidateAll()
                glassPreview?.strength = v
                rows["glass"]?.value = "${Math.round(v * 100)}%"
            }
        }
        pane.place(bar, 48f, top0 + ph + 36f, w, 80f)
    }

    private fun paneMotion() {
        val cfg = act.cfg
        paneHeader("Motion")
        val demo = MotionPreview(act).apply { mode = cfg.motion }
        pane.place(demo, 48f, top0, PANE_W - 96f, 260f)
        val add = optionColumn(top0 + 284f, "motion")
        val names = arrayOf("Full", "Reduced", "Off")
        val icons = intArrayOf(R.drawable.ic_auto_awesome, R.drawable.ic_timer, R.drawable.ic_close)
        for (i in names.indices) {
            val on = cfg.motion == i
            choice(add, icons[i], names[i], on) {
                cfg.motion = i
                Theme.motion = i
                act.save()
                refresh("motion")
            }.setOnFocusChangeListener { _, has -> if (has) demo.mode = i }
        }
        add(R.drawable.ic_home_app_logo, "Start Animation", "", SettingRow.Trail.SWITCH) {
            if (cfg.motion == 2) return@add
            cfg.splash = !cfg.splash
            act.save()
            refresh("motion")
        }.apply {
            checked = cfg.splash && cfg.motion != 2
            alpha = if (cfg.motion == 2) 0.45f else 1f
            setOnFocusChangeListener { _, has -> if (has) demo.mode = cfg.motion }
        }
    }

    private fun comingSummary() = if (!act.cfg.comingOn) "Off" else Coming.AHEAD_NAMES[act.cfg.comingAhead]

    private fun paneComing() {
        val cfg = act.cfg
        paneHeader("Coming Up", "Next episodes of the series you follow, at the time they air here.")
        val again = {
            act.save()
            Coming.reset()
            act.home.rebuild()
            refresh("coming")
        }
        // The row as it looks on Home, smaller, or a note when nothing is due.
        val prev = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; alpha = if (Coming.shown(act)) 1f else 0.45f }
        pane.place(prev, 48f, top1, PANE_W - 96f, 136f)
        val fill = { list: List<Coming.Upcoming> ->
            prev.removeAllViews()
            if (list.isEmpty()) {
                val until = if (cfg.comingAhead == 3) "yet" else "in the next " + listOf("two weeks", "30 days", "3 months")[cfg.comingAhead]
                prev.addView(act.label("Nothing due $until", 26f, Theme.TEXT_DIM), LinearLayout.LayoutParams(-1, -1))
            } else for ((i, u) in list.take(3).withIndex()) {
                val card = ArtCard(act).apply {
                    isFocusable = false; isClickable = false
                    val (chip, soon) = Coming.chip(act, cfg.clock, u.air)
                    title = u.name; subtitle = Coming.line(act, cfg.clock, u.air); badge = chip; badgeSoft = !soon
                    iconRes = R.drawable.ic_event_upcoming
                }
                prev.addView(card, LinearLayout.LayoutParams(Theme.ipx(242f), -1).apply { if (i > 0) leftMargin = Theme.ipx(20f) })
                val m = Watch.cachedMeta(u.id)
                Images.load(m?.background ?: u.poster, Theme.ipx(300f)) { b -> card.art = b }
            }
        }
        val ready = Coming.ready
        if (ready != null) fill(ready)
        else {
            val shows = Coming.following(act).filter { it.id !in cfg.comingHidden }
            val ctx = act.applicationContext
            Net.run({ Coming.upcoming(ctx, shows, cfg.comingAhead) }) { if (prev.isAttachedToWindow) fill(it ?: emptyList()) }
        }
        val hidden = Coming.following(act).filter { it.id in cfg.comingHidden }
        val add = optionColumn(top1 + 160f, "coming")
        add(R.drawable.ic_event_upcoming, "Show on Home", "", SettingRow.Trail.SWITCH) {
            if (!cfg.spotlight) { act.toast("Turn on Spotlight first"); return@add }
            cfg.comingOn = !cfg.comingOn
            again()
        }.apply { checked = cfg.comingOn; alpha = if (cfg.spotlight) 1f else 0.45f }
        add(R.drawable.ic_schedule, "How Far Ahead", Coming.AHEAD_NAMES[cfg.comingAhead], SettingRow.Trail.CHEVRON) {
            act.push(Sheet(act, "How Far Ahead", "Coming Up", null, Coming.AHEAD_NAMES.mapIndexed { i, n ->
                Sheet.Action(n, R.drawable.ic_event_upcoming, Coming.AHEAD_SUBS[i], checked = cfg.comingAhead == i) { cfg.comingAhead = i; again() }
            }))
        }.apply { alpha = if (cfg.comingOn) 1f else 0.45f }
        add(R.drawable.ic_visibility_off, "Hidden Shows", if (hidden.isEmpty()) "None" else "${hidden.size}", SettingRow.Trail.CHEVRON) {
            if (hidden.isEmpty()) act.toast("Nothing hidden. Hold OK on a card to hide a show.")
            else act.push(Sheet(act, "Hidden Shows", "Coming Up", null, hidden.map { s ->
                Sheet.Action(s.name, R.drawable.ic_visibility, "Show again") { cfg.comingHidden.remove(s.id); again() }
            }))
        }
    }

    private fun paneFont() {
        val cfg = act.cfg
        paneHeader("Title Font")
        // A spotlight title on a card. Long names drift, as on Home.
        val card = surfaceCard(48f, top0, PANE_W - 96f, 200f)
        val demo = ScrollTitle(act).apply { font = cfg.titleFont; text = act.home.spotTitle().ifEmpty { "The Lord of the Rings" } }
        card.place(demo, 48f, 20f, PANE_W - 192f, 160f)
        val add = optionColumn(top0 + 224f, "font")
        for (i in Theme.TITLE_FONTS.indices) {
            choice(add, R.drawable.ic_edit, Theme.TITLE_FONTS[i].name, cfg.titleFont == i) {
                cfg.titleFont = i
                Theme.titleFont = i
                act.save()
                act.home.invalidateAll()
                refresh("font")
            }.setOnFocusChangeListener { _, has -> if (has) demo.font = i }
        }
    }

    private fun paneFocus() {
        val cfg = act.cfg
        paneHeader("Focus Style")
        val demo = FocusPreview(act).apply { style = cfg.focusStyle; labelsAll = cfg.labelsAll; samples = act.home.sampleCards() }
        pane.place(demo, 48f, top0, PANE_W - 96f, 260f)
        val add = optionColumn(top0 + 284f, "focus")
        val icons = intArrayOf(R.drawable.ic_focus_lift, R.drawable.ic_check_circle, R.drawable.ic_gradient)
        for (i in FOCUS_NAMES.indices) {
            choice(add, icons[i], FOCUS_NAMES[i], cfg.focusStyle == i) {
                cfg.focusStyle = i
                Theme.focusStyle = i
                act.themeChanged()
                refresh("focus")
            }.setOnFocusChangeListener { _, has -> if (has) demo.style = i }
        }
        add(R.drawable.ic_view_agenda, "Name Every Card", "", SettingRow.Trail.SWITCH) {
            cfg.labelsAll = !cfg.labelsAll
            Theme.labelsAll = cfg.labelsAll
            act.themeChanged()
            refresh("focus")
        }.apply {
            checked = cfg.labelsAll
            setOnFocusChangeListener { _, has -> demo.style = cfg.focusStyle; demo.labelsAll = if (has) true else cfg.labelsAll }
        }
    }

    private fun clockSummary(): String {
        val cfg = act.cfg
        return when {
            cfg.clockOn && cfg.weatherOn -> "On"
            cfg.clockOn -> "Clock only"
            cfg.weatherOn -> "Weather only"
            else -> "Hidden"
        }
    }

    private class Choice(val label: String, val icon: Int, val sub: String = "")

    /** Opens a list of choices; the current one is ticked. */
    private fun choose(title: String, options: List<Choice>, current: Int, focusTag: String, footer: View? = null, footerH: Float = 0f, pick: (Int) -> Unit) {
        val actions = options.mapIndexed { i, o ->
            Sheet.Action(o.label, o.icon, o.sub, checked = i == current) {
                pick(i)
                act.save()
                act.home.weatherChanged()
                act.home.requestLayout()
                rows["clock"]?.subtitle = clockSummary()
                paneClock()
                post { pane.findViewWithTag<View>(focusTag)?.requestFocus() }
            }
        }
        act.push(Sheet(act, title, "Clock & Weather", null, actions, footer, footerH))
    }

    private fun locationCard(): LocationCard {
        val cfg = act.cfg
        return LocationCard(act).apply {
            city = cfg.city
            lat = cfg.lat
            lon = cfg.lon
            automatic = cfg.locAuto
            act.weather?.let { w ->
                val (cond, _) = Weather.describe(w.code, w.day)
                weatherLine = "${Weather.format(w.temp, cfg.fahrenheit)}  ·  $cond  ·  ${Weather.format(w.high, cfg.fahrenheit)} / ${Weather.format(w.low, cfg.fahrenheit)}"
            }
        }
    }

    private fun paneClock() {
        val cfg = act.cfg
        paneHeader("Clock & Weather")
        // Preview: the real, live top bar pill, larger and on its own.
        val strip = FrameLayout(act).apply { clipChildren = false }
        pane.place(strip, 48f, top0, PANE_W - 96f, 150f)
        if (cfg.clockOn || cfg.weatherOn) {
            val pill = InfoPill(act).apply { apply(cfg, act.weather); scaleX = 1.35f; scaleY = 1.35f }
            strip.addView(pill, FrameLayout.LayoutParams(-2, Theme.ipx(88f), Gravity.CENTER))
        } else {
            strip.addView(act.label("Hidden", 28f, Theme.TEXT_DIM, Theme.MEDIUM).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1))
        }

        val scroller = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            // Rows fade out at the edges.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(Theme.ipx(40f))
        }
        val holder = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.ipx(8f), Theme.ipx(8f), Theme.ipx(8f), Theme.ipx(24f))
            clipChildren = false
        }
        val col = cardColumn()
        holder.addView(col, LinearLayout.LayoutParams(-1, -2))
        scroller.addView(holder, LayoutParams(-1, -2))
        pane.place(act.clipBox(scroller), 40f, top0 + 170f, PANE_W - 80f, PANE_H - top0 - 194f)
        var n = 0
        fun option(icon: Int, title: String, value: String, enabled: Boolean = true, click: (String) -> Unit) {
            val tag = "opt${n++}"
            val r = SettingRow(act, icon, title).apply {
                this.value = value
                trail = SettingRow.Trail.CHEVRON
                divider = col.childCount > 0
                this.tag = tag
                dimmed = !enabled
                setOnClickListener { if (enabled) click(tag) }
            }
            col.addView(r, LinearLayout.LayoutParams(-1, Theme.ipx(96f)))
        }
        // Two-way settings flip in place; settings with more choices open a list.
        fun toggle(icon: Int, title: String, on: Boolean, enabled: Boolean = true, flip: () -> Unit) {
            val tag = "opt${n++}"
            val r = SettingRow(act, icon, title).apply {
                trail = SettingRow.Trail.SWITCH
                checked = on
                divider = col.childCount > 0
                this.tag = tag
                dimmed = !enabled
                setOnClickListener {
                    if (!enabled) return@setOnClickListener
                    flip()
                    act.save()
                    act.home.weatherChanged()
                    act.home.requestLayout()
                    rows["clock"]?.subtitle = clockSummary()
                    val y = scroller.scrollY
                    paneClock()
                    post {
                        val v = pane.findViewWithTag<View>(tag)
                        var p = v?.parent
                        while (p != null && p !is ScrollView) p = p.parent
                        (p as? ScrollView)?.scrollTo(0, y)
                        v?.requestFocus()
                    }
                }
            }
            col.addView(r, LinearLayout.LayoutParams(-1, Theme.ipx(96f)))
        }
        toggle(R.drawable.ic_schedule, "Clock", cfg.clockOn) { cfg.clockOn = !cfg.clockOn }
        option(R.drawable.ic_schedule, "Time Format", arrayOf("System", "12-hour", "24-hour")[cfg.clock], cfg.clockOn) { t ->
            choose("Time Format", listOf(
                Choice("Follow System", R.drawable.ic_settings),
                Choice("12-hour", R.drawable.ic_schedule, "7:38"),
                Choice("24-hour", R.drawable.ic_schedule, "19:38"),
            ), cfg.clock, t) { cfg.clock = it }
        }
        toggle(R.drawable.ic_schedule, "Date", cfg.showDate, cfg.clockOn) { cfg.showDate = !cfg.showDate }
        toggle(R.drawable.ic_partly_cloudy_day, "Weather", cfg.weatherOn) {
            cfg.weatherOn = !cfg.weatherOn
            if (cfg.weatherOn) act.refreshWeather(force = true)
        }
        option(R.drawable.ic_thermostat, "Temperature Units", if (cfg.fahrenheit) "Fahrenheit" else "Celsius", cfg.weatherOn) { t ->
            choose("Temperature Units", listOf(
                Choice("Celsius", R.drawable.ic_thermostat, "9°"),
                Choice("Fahrenheit", R.drawable.ic_thermostat, "48°"),
            ), if (cfg.fahrenheit) 1 else 0, t) { cfg.fahrenheit = it == 1 }
        }
        option(R.drawable.ic_partly_cloudy_day, "Weather Details", arrayOf("Full", "High & low", "Now")[cfg.weatherDetail], cfg.weatherOn) { t ->
            choose("Weather Details", listOf(
                Choice("Full", R.drawable.ic_partly_cloudy_day, "Clear · 62° / 38°"),
                Choice("High and Low", R.drawable.ic_thermostat, "62° / 38°"),
                Choice("Temperature Only", R.drawable.ic_thermostat, "48°"),
            ), cfg.weatherDetail, t) { cfg.weatherDetail = it }
        }
        val where = if (cfg.locAuto) "Automatic" else cfg.city.ifEmpty { "Not set" }.let { if (it.length > 18) it.take(17).trimEnd() + "…" else it }
        option(R.drawable.ic_location_on, "Location", where, cfg.weatherOn) { t ->
            choose("Weather Location", listOf(
                Choice("Use This TV's Location", R.drawable.ic_my_location),
                Choice("Choose a City", R.drawable.ic_search, cfg.city),
            ), if (cfg.locAuto) 0 else 1, t, locationCard(), 330f) { i ->
                if (i == 0) {
                    cfg.locAuto = true
                    act.withPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) { ok ->
                        if (ok) act.refreshWeather(force = true)
                        else act.toast("Location is off for this app. Allow it in Android settings, Apps.")
                    }
                } else {
                    post {
                        act.push(LocationPicker(act) {
                            cfg.locAuto = false
                            act.save()
                            act.refreshWeather(force = true)
                            paneClock()
                        })
                    }
                }
            }
        }
        Motion.staggerChildren(col)
    }

    /** Scrolling card of option rows in the pane, below [top]. Returns an adder for rows. */
    private fun optionColumn(top: Float, tagPrefix: String): (Int, String, String, SettingRow.Trail, () -> Unit) -> SettingRow {
        val scroller = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            // Rows fade out at the edges.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(Theme.ipx(40f))
        }
        val holder = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.ipx(8f), Theme.ipx(8f), Theme.ipx(8f), Theme.ipx(24f))
            clipChildren = false
        }
        val col = cardColumn()
        holder.addView(col, LinearLayout.LayoutParams(-1, -2))
        scroller.addView(holder, LayoutParams(-1, -2))
        pane.place(act.clipBox(scroller), 40f, top, PANE_W - 80f, PANE_H - top - 24f)
        post { Motion.staggerChildren(col) }
        var n = 0
        return { icon, title, value, trail, click ->
            SettingRow(act, icon, title).apply {
                // Pane rows carry no subtitle. A value on a plain row shows at the end.
                this.value = value
                this.trail = if (trail == SettingRow.Trail.NONE && value.isNotEmpty()) SettingRow.Trail.VALUE else trail
                divider = col.childCount > 0
                tag = "$tagPrefix${n++}"
                setOnClickListener { click() }
                col.addView(this, LinearLayout.LayoutParams(-1, Theme.ipx(96f)))
            }
        }
    }

    /** A choice row: its own icon, with a check on the right when chosen. */
    private fun choice(add: (Int, String, String, SettingRow.Trail, () -> Unit) -> SettingRow, icon: Int, title: String, on: Boolean, click: () -> Unit) =
        add(icon, title, "", SettingRow.Trail.CHECK, click).apply { checked = on }

    /** Asks before a change that can't be undone. Focus starts on Cancel. */
    private fun confirm(title: String, detail: String, doLabel: String, doIcon: Int, doSub: String, who: Profile? = null, run: () -> Unit) {
        act.push(Dialog(act, doIcon, title, listOf(detail, doSub).filter { it.isNotEmpty() }.joinToString(". ") { it.trimEnd('.') } + ".", listOf(
            Dialog.Button("Cancel", focus = true),
            Dialog.Button(doLabel, primary = true) { run() },
        ), who))
    }

    /** Switches to [q] from the Profiles pane, asking for its passcode first. */
    private fun switchTo(q: Profile) {
        if (q.id == Profiles.currentId) return
        val go = {
            act.switchProfile(q.id)
            // Switching to a Kids profile closes Settings.
            if (!q.kids) {
                refresh("profile")
                post { pane.findViewWithTag<View>("profav${Profiles.list.indexOf(q)}")?.requestFocus() }
            }
        }
        if (!q.locked) go() else act.push(PasscodeScreen(act, q, PasscodeScreen.Mode.CHECK) { act.pop(); go() })
    }

    /** Runs [then] after the current profile's passcode, or straight away when it has none. */
    private fun withCode(p: Profile, then: () -> Unit) {
        if (!p.locked) { then(); return }
        act.push(PasscodeScreen(act, p, PasscodeScreen.Mode.CHECK) { act.pop(); then() })
    }

    private fun paneProfiles() {
        val p = Profiles.current
        paneHeader("Profiles")
        // Everyone at a glance, then Add. OK on a picture switches to that profile.
        val canAdd = Profiles.list.size < Profiles.MAX
        val cells = Profiles.list.size + if (canAdd) 1 else 0
        val many = cells > 5
        val size = if (many) 92f else 104f
        val pitch = if (many) 122f else 140f
        val stripH = size + 104f
        val strip = FrameLayout(act).apply { clipChildren = false }
        val scroll = android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(Theme.ipx(40f))
            clipChildren = false
        }
        scroll.addView(strip, FrameLayout.LayoutParams(Theme.ipx(32f + cells * pitch), Theme.ipx(stripH)))
        pane.place(scroll, 40f, top0 - 8f, PANE_W - 80f, stripH)
        fun caption(text: String, i: Int, dy: Float, sp: Float, color: Int, weight: android.graphics.Typeface) =
            strip.place(act.label(text, sp, color, weight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, 16f + i * pitch, 16f + size + dy, pitch, sp + 8f)
        val avatars = ArrayList<View>()
        for ((i, q) in Profiles.list.withIndex()) {
            val av = AvatarView(act).apply {
                letter = q.initial; emoji = q.emoji; colorIndex = q.color; locked = q.locked
                tag = "profav$i"
                contentDescription = if (q.id == p.id) "${q.name}, current" else "Switch to ${q.name}"
                setOnClickListener { switchTo(q) }
                setOnLongClickListener { profileMenu(p, q); true }
            }
            strip.place(av, 16f + i * pitch + (pitch - size) / 2, 16f, size, size)
            val cur = q.id == p.id
            caption(q.name, i, 20f, 21f, if (cur) Theme.TEXT else Theme.TEXT_DIM, if (cur) Theme.SEMIBOLD else Theme.REGULAR)
            if (q.admin) caption("Admin", i, 50f, 18f, Theme.accent, Theme.SEMIBOLD)
            else if (q.kids) caption("Kids", i, 50f, 18f, Theme.accent, Theme.SEMIBOLD)
            avatars.add(av)
        }
        if (canAdd) {
            val i = Profiles.list.size
            val add = AvatarView(act, addMode = true).apply {
                tag = "profadd"
                contentDescription = "Add profile"
                setOnClickListener { act.newProfile() }
            }
            strip.place(add, 16f + i * pitch + (pitch - size) / 2, 16f, size, size)
            caption("Add", i, 20f, 21f, Theme.TEXT_DIM, Theme.REGULAR)
            avatars.add(add)
        }
        avatars.forEachIndexed { i, v -> Motion.pop(v, i * 40L) }
        val add = optionColumn(top0 + stripH, "prof")
        add(R.drawable.ic_lock, "Passcode", if (p.locked) "On" else "Off", SettingRow.Trail.CHEVRON) {
            if (!p.locked) {
                act.push(PasscodeScreen(act, p, PasscodeScreen.Mode.SET) { code ->
                    act.pop(); Profiles.setCode(act, p, code); act.home.rebuild(); refresh("profile"); act.toast("Passcode set")
                    // An admin gets a recovery code the first time a passcode is set.
                    if (p.admin && !p.hasRecovery) act.createRecovery(p) { refresh("profile") }
                })
            } else {
                act.push(Sheet(act, "Passcode", p.name, null, listOf(
                    Sheet.Action("Change Passcode", R.drawable.ic_lock) {
                        withCode(p) {
                            act.push(PasscodeScreen(act, p, PasscodeScreen.Mode.SET) { code ->
                                act.pop(); Profiles.setCode(act, p, code); refresh("profile"); act.toast("Passcode changed")
                            })
                        }
                    },
                    Sheet.Action("Turn Off Passcode", R.drawable.ic_close) {
                        if (!keepsKidsGate(p)) return@Action
                        withCode(p) { Profiles.setCode(act, p, null); act.home.rebuild(); refresh("profile") }
                    },
                )))
            }
        }
        if (p.admin && p.locked) {
            add(R.drawable.ic_workspace_premium, "Recovery Code", if (p.hasRecovery) "Saved" else "Not made", SettingRow.Trail.CHEVRON) {
                withCode(p) { act.createRecovery(p, if (p.hasRecovery) "Your old code no longer works. Save this new one." else "In case the passcode is ever forgotten.") { refresh("profile") } }
            }
        }
        add(R.drawable.ic_history, "Ask Who's Watching", "", SettingRow.Trail.SWITCH) {
            Profiles.askOnWake = !Profiles.askOnWake
            Profiles.save(act)
            refresh("profile")
        }.apply { checked = Profiles.askOnWake }
    }

    /**
     * Hold OK on a picture: name and picture for your own profile, and for an admin, roles,
     * passcode resets and deleting. Changing someone else's profile asks for your passcode.
     */
    private fun profileMenu(p: Profile, q: Profile) {
        val self = q.id == p.id
        val a = ArrayList<Sheet.Action>()
        val guard: (() -> Unit) -> Unit = { run -> if (self) run() else withCode(p, run) }
        if (self || p.admin) {
            a.add(Sheet.Action("Rename", R.drawable.ic_edit) {
                guard {
                    act.push(NameEntry(act, "Rename ${q.name}", q.name) { name ->
                        if (!Profiles.rename(act, q, name)) { act.toast("A name needs at least one letter or emoji"); return@NameEntry }
                        act.home.rebuild()
                        refresh("profile")
                    })
                }
            })
            a.add(Sheet.Action("Change Picture", R.drawable.ic_mood) {
                guard {
                    act.push(AvatarPicker(act, q) {
                        Profiles.save(act)
                        act.home.rebuild()
                        refresh("profile")
                    })
                }
            })
        }
        if (p.admin && Profiles.list.size > 1) {
            if (!self && q.locked) a.add(Sheet.Action("Reset Passcode", R.drawable.ic_lock) {
                confirm("Reset ${q.name}'s passcode?", "${q.name} is unlocked until a new passcode is set", "Reset Passcode", R.drawable.ic_lock, "", who = q) {
                    withCode(p) {
                        Profiles.setCode(act, q, null)
                        Locks.forget(act, q.id)
                        act.home.rebuild()
                        refresh("profile")
                        act.toast("${q.name}'s passcode is cleared")
                    }
                }
            })
            if (!q.kids) a.add(Sheet.Action(if (q.admin) "Make Standard" else "Make Admin", R.drawable.ic_workspace_premium) { changeRole(p, q) })
            if (!q.admin) a.add(Sheet.Action(if (q.kids) "Turn Off Kids" else "Make Kids Profile", R.drawable.ic_child_care) { changeKids(p, q) })
        }
        if (Profiles.list.size > 1 && (self || p.admin)) {
            a.add(Sheet.Action("Delete Profile", R.drawable.ic_delete) { deleteProfile(p, q) })
        }
        if (a.isEmpty()) { act.toast("Only an admin can change ${q.name}"); return }
        act.push(Sheet(act, q.name, if (q.admin) "Admin" else if (q.kids) "Kids" else "Standard", null, a, headerProfile = q))
    }

    /**
     * Confirms, checks [by]'s passcode, then deletes [q]. The only admin first picks or creates the
     * next admin. Deleting the current profile lands on a profile with no passcode, or asks who's
     * watching next and checks that profile's passcode.
     */
    private fun deleteProfile(by: Profile, q: Profile) {
        val self = q.id == Profiles.currentId
        val lastAdmin = q.admin && Profiles.list.count { it.admin } == 1
        val notes = listOfNotNull(
            "Pinned apps, colours and layout for ${q.name} are removed",
            if (lastAdmin) "Next, choose the new admin" else null,
        )
        confirm("Delete ${q.name}?", "This can't be undone", "Delete profile", R.drawable.ic_delete, notes.joinToString(". "), who = q) {
            withCode(by) {
                if (lastAdmin) chooseHeir(q) { heir -> finishDelete(q, self, heir) } else finishDelete(q, self, null)
            }
        }
    }

    private fun finishDelete(q: Profile, self: Boolean, heir: Profile?) {
        if (!self) { removeNow(q, null, false); return }
        val open = heir?.takeIf { !it.locked } ?: Profiles.landingAfter(q.id)
        if (open != null) { removeNow(q, open, true); return }
        // Every other profile has a passcode: someone enters theirs to finish.
        act.push(Sheet(act, "Who's watching next?", "Enter that profile's passcode to finish", null, Profiles.list.filter { it !== q }.map { o ->
            Sheet.Action(o.name, 0, "Has a passcode", o) {
                act.push(PasscodeScreen(act, o, PasscodeScreen.Mode.CHECK) { act.pop(); removeNow(q, o, true) })
            }
        }))
    }

    private fun removeNow(q: Profile, landOn: Profile?, self: Boolean) {
        if (!Profiles.remove(act, q.id, landOn)) return
        if (self) act.switchProfile(Profiles.currentId)
        act.home.rebuild()
        refresh("profile")
        act.toast("${q.name} is deleted")
    }

    /**
     * Used when the only admin is leaving: picks a profile or creates one as the next admin. A
     * profile with a passcode gets a recovery code first, shown once.
     */
    private fun chooseHeir(leaving: Profile, then: (Profile) -> Unit) {
        val actions = Profiles.list.filter { it !== leaving }.map { o ->
            Sheet.Action(o.name, 0, if (o.locked) "Has a passcode, so a recovery code is made" else "No passcode", o) {
                Profiles.setAdmin(act, o, true)
                if (o.locked) act.createRecovery(o, "This code unlocks ${o.name} if the passcode is forgotten. Keep it somewhere safe.") { then(o) }
                else then(o)
            }
        }.toMutableList()
        if (Profiles.list.size < Profiles.MAX) actions.add(Sheet.Action("New profile", R.drawable.ic_add, "Create the new admin") {
            act.push(ProfileWizard(act) { name, emoji, color ->
                val o = Profiles.add(act, name, emoji, color)
                Profiles.setAdmin(act, o, true)
                then(o)
            })
        })
        act.push(Sheet(act, "Choose the new admin", "Someone must be able to manage profiles", null, actions))
    }

    /** Makes [q] an admin or a standard profile. There is always at least one admin. */
    /**
     * True when [p] can lose its passcode or admin role. While a Kids profile exists, one admin
     * passcode must stay, so leaving Kids keeps needing one. Otherwise explains and returns false.
     */
    private fun keepsKidsGate(p: Profile): Boolean {
        if (!p.admin || Profiles.list.none { it.kids } || Profiles.lockedAdmins().any { it !== p }) return true
        act.push(Dialog(act, 0, "A Kids profile needs this passcode",
            "Leaving a Kids profile takes an admin's passcode, and ${p.name}'s is the only one. Turn off Kids first.",
            listOf(Dialog.Button("OK", primary = true)), p))
        return false
    }

    /**
     * Turns Kids on or off for [q], with [by]'s passcode. Kids needs a passcode on every other
     * profile, so no one can switch out of it to an open profile.
     */
    private fun changeKids(by: Profile, q: Profile) {
        val on = !q.kids
        if (on) {
            val open = Profiles.list.filter { it !== q && !it.kids && !it.locked }
            if (open.isNotEmpty()) {
                act.push(Dialog(act, 0, "Passcodes first",
                    "Every other profile needs a passcode before ${q.name} can be a Kids profile. Still open: ${open.joinToString(", ") { it.name }}.",
                    listOf(Dialog.Button("OK", primary = true)), q))
                return
            }
        }
        confirm(
            if (on) "Make ${q.name} a Kids profile?" else "Turn off Kids for ${q.name}?",
            if (on) "No Spotlight and no Settings. Leaving takes a passcode, and any admin's works"
                else "${q.name} gets the Spotlight and Settings back",
            if (on) "Make Kids" else "Turn Off Kids", R.drawable.ic_child_care, "", who = q,
        ) {
            withCode(by) {
                if (!Profiles.setKids(act, q, on)) return@withCode
                refresh("profile")
                act.toast(if (on) "${q.name} is now a Kids profile" else "${q.name} is a standard profile again")
            }
        }
    }

    private fun changeRole(by: Profile, q: Profile) {
        if (q.admin && !keepsKidsGate(q)) return
        if (!Roles.canDemote(Profiles.list, q)) {
            act.push(Dialog(act, 0, "${q.name} is the only admin",
                "Make another profile an admin first. There is always at least one.",
                listOf(Dialog.Button("OK", primary = true)), q))
            return
        }
        val toAdmin = !q.admin
        confirm(
            if (toAdmin) "Make ${q.name} an admin?" else "Make ${q.name} a standard profile?",
            if (toAdmin) "Admins can change any profile, reset passcodes and delete profiles"
                else "Standard profiles can change only their own name, picture and passcode",
            if (toAdmin) "Make admin" else "Make standard", R.drawable.ic_workspace_premium,
            if (!toAdmin && q.hasRecovery) "${q.name}'s recovery code stops working" else "", who = q,
        ) {
            withCode(by) {
                if (!Profiles.setAdmin(act, q, toAdmin)) return@withCode
                refresh("profile")
                act.toast(if (toAdmin) "${q.name} is now an admin" else "${q.name} is now a standard profile")
            }
        }
    }

    private fun paneSpotlight() {
        val cfg = act.cfg
        paneHeader("Spotlight")
        val (a, b) = twoUp("On", "Off", cfg.spotlight, { spotlight = true }, { spotlight = false })
        a.makeFocusable(true)
        b.makeFocusable(true)
        a.setOnClickListener { if (!cfg.spotlight) { cfg.spotlight = true; act.save(); act.home.rebuild(); refresh("spot") } }
        b.setOnClickListener { if (cfg.spotlight) { cfg.spotlight = false; act.save(); act.home.rebuild(); refresh("spot") } }
        // Your Library and its rows sit under Continue watching. Rows stay listed, greyed, while off.
        val add = optionColumn(top1 + 300f, "spotopt")
        val lib = Library.onHome(act)
        val shelves = Watch.shelves(act)
        add(R.drawable.ic_view_agenda, "Your Library", "", SettingRow.Trail.SWITCH) {
            cfg.libraryHome = if (Library.onHome(act)) 0 else 1
            // A single shelf has no switch of its own, so this one rules it.
            if (shelves.size == 1) cfg.libraryOff.remove(shelves[0].key)
            act.save(); act.home.rebuild(); refresh("spot")
        }.apply { checked = lib; dimmed = !cfg.spotlight }
        if (shelves.size > 1) for (sh in shelves) {
            val icon = when {
                sh.key.contains("recent") -> R.drawable.ic_history
                sh.key.contains("show") || sh.key.contains("series") -> R.drawable.ic_tv
                else -> R.drawable.ic_movie
            }
            add(icon, sh.title, "", SettingRow.Trail.SWITCH) {
                if (!cfg.libraryOff.remove(sh.key)) cfg.libraryOff.add(sh.key)
                act.save(); act.home.rebuild(); refresh("spot")
            }.apply { checked = sh.key !in cfg.libraryOff; dimmed = !cfg.spotlight || !lib }
        }
        add(R.drawable.ic_open_in_new, "Open Library", "", SettingRow.Trail.NONE) {
            if (Watch.signedIn(act)) act.push(LibraryScreen(act)) else act.toast("Sign in to ${Watch.name(act)} first")
        }
    }

    /** True while the pane is being emptied, so focus passing through the list opens nothing. */
    private var clearingPane = false

    private fun tileGrid(pkgs: List<String>, focusable: Boolean, onPick: ((String) -> Unit)?, top: Float = top0) {
        val cols = 5
        val tw = 128f
        val pitch = 156f
        val x0 = (PANE_W - (cols - 1) * pitch - tw) / 2
        val tiles = ArrayList<View>()
        for ((i, pkg) in pkgs.withIndex()) {
            if (i >= 10) break
            val e = act.app(pkg)
            val tile = AppTile(act).apply {
                label = e?.label ?: act.cfg.pinned.firstOrNull { it.pkg == pkg }?.label ?: pkg.substringAfterLast('.')
                icon = e?.icon
                isFocusable = focusable
                isClickable = focusable
                if (focusable) tag = "hidden$i"
                onPick?.let { pick -> setOnClickListener { pick(pkg) } }
            }
            pane.place(tile, x0 + (i % cols) * pitch, top + (i / cols) * 200f, tw, 176f)
            tiles.add(tile)
        }
        if (pkgs.size > 10) pane.place(centredText("and ${pkgs.size - 10} more", 24f, Theme.TEXT_DIM), 48f, top + 410f, PANE_W - 96f, 34f)
        Motion.stagger(tiles, 22L)
    }

    private fun panePinned() {
        val cfg = act.cfg
        paneHeader("Pinned Apps")
        if (cfg.pinned.isEmpty()) emptyState(R.drawable.ic_push_pin, "Nothing pinned yet")
        else tileGrid(cfg.pinned.map { it.pkg }, false, null)

    }

    private fun dreamSummary(): String = when {
        !ClockDream.isOn(act) -> "Off"
        ClockDream.mode(act) == ClockDream.AMBIENT -> "Ambient"
        else -> "Clock"
    }

    private fun paneDream() {
        paneHeader("Screensaver")
        val add = optionColumn(top0, "dream")
        val on = ClockDream.isOn(act)
        val mode = ClockDream.mode(act)
        fun choose(m: Int) { ClockDream.setOn(act, true); ClockDream.setMode(act, m); refresh("dream") }
        val ambient = on && mode == ClockDream.AMBIENT
        val clock = on && mode == ClockDream.CLOCK
        choice(add, R.drawable.ic_gradient, "Ambient", ambient) { choose(ClockDream.AMBIENT) }
        choice(add, R.drawable.ic_schedule, "Clock", clock) { choose(ClockDream.CLOCK) }
        choice(add, R.drawable.ic_visibility_off, "Off", !on) {
            ClockDream.setOn(act, false); refresh("dream")
        }
        if (!on) return
        val y = top0 + 3 * 112f + 40f
        if (ClockDream.isSelected(act)) {
            section("Starts after the TV's sleep timer", y)
            return
        }
        // Without Android's picker, Home shows the screensaver itself while it is open.
        section("Starts after 5 minutes on Home", y)
        val picker = ClockDream.pickerIntent(act) ?: return
        pane.place(act.label("To have it start from any app, choose Scene in Android's screensaver settings.",
            24f, Theme.TEXT_DIM, Theme.REGULAR, 3), 56f, y + 44f, PANE_W - 112f, 70f)
        val more = optionColumn(y + 124f, "dreampick")
        more(R.drawable.ic_open_in_new, "Android Screensaver Settings", "", SettingRow.Trail.OPEN) { act.start(picker) }
    }

    private fun musicSummary(): String = when {
        !NowPlaying.hasAccess(act) -> "Needs notification access"
        NowPlaying.showOnHome(act) || NowPlaying.saver(act) -> "On"
        else -> "Off"
    }

    private fun paneMusic() {
        paneHeader("Now Playing")
        pane.place(MusicSaver(act, compact = true), 48f, top0, PANE_W - 96f, 300f)
        val add = optionColumn(top0 + 324f, "music")
        val access = NowPlaying.hasAccess(act)
        // Every switch here needs notification access, so they wait, dimmed, until it is allowed.
        val blocked = { act.toast("Allow notification access first"); pane.findViewWithTag<View>("music0")?.requestFocus() }
        if (!access) {
            add(R.drawable.ic_shield, "Allow Notification Access", "", SettingRow.Trail.OPEN) {
                act.openNotificationAccess()
            }
        }
        fun toggle(icon: Int, title: String, key: String, on: Boolean, flip: (Boolean) -> Unit = {}) =
            add(icon, title, "", SettingRow.Trail.SWITCH) {
                if (!access) blocked() else { NowPlaying.set(act, key, !on); flip(!on); refresh("music") }
            }.apply { checked = on; dimmed = !access }
        toggle(R.drawable.ic_music_note, "Show on Home", "npHome", NowPlaying.showOnHome(act)) { act.home.nowPlayingChanged() }
        toggle(R.drawable.ic_lyrics, "Music Screensaver", "npSaver", NowPlaying.saver(act))
        toggle(R.drawable.ic_lyrics, "Lyrics", "npLyrics", NowPlaying.lyrics(act))
        add(R.drawable.ic_graphic_eq, "Visualizer", "", SettingRow.Trail.SWITCH) {
            if (!access) { blocked(); return@add }
            val on = NowPlaying.viz(act)
            if (on) { NowPlaying.set(act, "npViz", false); refresh("music") }
            else act.withMicPermission { ok -> if (ok) NowPlaying.set(act, "npViz", true); refresh("music") }
        }.apply { checked = NowPlaying.viz(act); dimmed = !access }
        add(R.drawable.ic_play_arrow, "Full Screen Preview", "", SettingRow.Trail.NONE) {
            if (!access) blocked() else act.push(MusicSaverScreen(act))
        }.dimmed = !access
    }

    private fun paneInputs() {
        paneHeader("Inputs", "This TV's HDMI inputs. Hide the row or single inputs.")
        val add = optionColumn(top1 + 20f, "inputs")
        val on = Inputs.showOnHome(act)
        add(R.drawable.ic_input, "Show on Home", "", SettingRow.Trail.SWITCH) {
            Inputs.setShowOnHome(act, !on); act.home.rebuild(); refresh("inputs")
        }.apply { checked = on }
        for (i in Inputs.list(act)) {
            add(R.drawable.ic_input, i.name, "", SettingRow.Trail.SWITCH) {
                Inputs.setHidden(act, i.id, !i.hidden); act.home.rebuild(); refresh("inputs")
            }.apply { checked = !i.hidden; dimmed = !on }
        }
    }

    private fun remoteSummary(): String {
        if (!Remap.isEnabled(act)) return "Off"
        val n = Remap.buttons(act).count { Remap.isMapped(act, it.key) }
        if (n == 0 && Remap.homeOpensScene(act)) return "Home opens Scene"
        return if (n == 0) "No buttons changed" else if (n == 1) "1 button changed" else "$n buttons changed"
    }

    private fun paneRemote() {
        paneHeader("Remote Buttons", "Choose what the app buttons on your remote open.")
        val add = optionColumn(top1 + 20f, "remote")
        val on = Remap.isEnabled(act)
        if (!on) {
            add(R.drawable.ic_accessibility_new, "Turn On in Accessibility", "", SettingRow.Trail.OPEN) {
                try {
                    act.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    act.toast("Open System settings, then Accessibility")
                }
            }
        }
        if (!on) {
            pane.place(act.label("Find Scene Remote Buttons under Services and turn it on. Scene only reacts to buttons you change here.",
                24f, Theme.TEXT_DIM, Theme.REGULAR, 3), 56f, PANE_H - 150f, PANE_W - 112f, 100f)
        }
        add(R.drawable.ic_home, "Home Opens Scene", "", SettingRow.Trail.SWITCH) {
            Remap.setHomeOpensScene(act, !Remap.homeOpensScene(act))
            refresh("remote")
        }.apply { checked = Remap.homeOpensScene(act); dimmed = !on }
        val known = Remap.knownButtons().map { it.key }
        for (b in Remap.buttons(act)) {
            fun name(n: Int) = Remap.target(act, b.key, n)?.let { Remap.label(it, act.apps) }
            val first = name(1) ?: if (Remap.hasMulti(act, b.key)) "Nothing" else "Usual action"
            val value = listOfNotNull(first, name(2)?.let { "2× $it" }, name(3)?.let { "3× $it" }).joinToString(" · ")
            add(R.drawable.ic_settings_remote, "${b.name} Button", value, SettingRow.Trail.VALUE) {
                act.push(RemapPicker(act, b, b.key !in known) { refresh("remote") })
            }.apply { dimmed = !on }
        }
        if (on) {
            add(R.drawable.ic_add, "Add Another Button", "", SettingRow.Trail.NONE) {
                act.toast("Press the remote button you want to add")
                val ask = Runnable { Remap.learning = null }
                Remap.learning = { key ->
                    act.runOnUiThread {
                        removeCallbacks(ask)
                        Remap.addLearned(act, key)
                        act.toast("Added ${Remap.keyName(key)}")
                        refresh("remote")
                    }
                }
                // Stop listening if no button is pressed.
                postDelayed(ask, 10_000L)
            }
        }
    }

    private fun homeRowSummary(): String = when {
        act.cfg.showPinned && act.cfg.showRecent -> "Pinned and recent"
        act.cfg.showPinned -> "Pinned apps"
        act.cfg.showRecent -> "Recent apps"
        else -> "None"
    }

    private fun paneSounds() {
        paneHeader("Sounds")
        val size = 400f
        pane.place(SoundPad(act).apply { tag = "pad" }, (PANE_W - size) / 2, top0 + 20f, size, size)
        pane.place(centredText("Press the arrows and OK to listen", 26f, Theme.TEXT_DIM), 48f, top0 + size + 56f, PANE_W - 96f, 36f)
    }

    private fun paneHomeRow() {
        paneHeader("Home Row")
        val add = optionColumn(top0, "homerow")
        val cfg = act.cfg
        add(R.drawable.ic_push_pin, "Pinned Apps", "", SettingRow.Trail.SWITCH) {
            act.setHomeRows(!cfg.showPinned, cfg.showRecent); refresh("homerow")
        }.apply { checked = cfg.showPinned }
        add(R.drawable.ic_history, "Recent Apps", "", SettingRow.Trail.SWITCH) {
            act.setHomeRows(cfg.showPinned, !cfg.showRecent); refresh("homerow")
        }.apply { checked = cfg.showRecent }
        if (cfg.showRecent && !Apps.hasUsageAccess(act)) {
            add(R.drawable.ic_shield, "Include Apps Opened Elsewhere", "Needs usage access for Scene", SettingRow.Trail.OPEN) {
                act.openUsageAccess()
            }
        }
        if (cfg.showRecent) {
            val empty = act.recentApps().isEmpty()
            add(R.drawable.ic_delete, "Clear Recents", "", SettingRow.Trail.NONE) {
                if (act.recentApps().isEmpty()) return@add
                confirm("Clear recent apps?", "The row empties until you open apps again", "Clear", R.drawable.ic_delete, "Pinned apps are kept") {
                    act.clearRecents(); act.toast("Recents cleared"); refresh("homerow")
                }
            }.apply { dimmed = empty }
        }
    }

    private fun paneSort() {
        paneHeader("All Apps Order")
        val add = optionColumn(top0, "sort")
        val names = arrayOf("A to Z", "Recently Installed", "Custom")
        val icons = intArrayOf(R.drawable.ic_sort_by_alpha, R.drawable.ic_history, R.drawable.ic_drag_indicator)
        for (i in names.indices) {
            val on = act.cfg.appSort == i
            choice(add, icons[i], names[i], on) {
                act.setAppSort(i)
                refresh("sort")
            }
        }
        section("First in Grid", top0 + 3 * 96f + 64f)
        tileGrid(act.sortedApps().take(5).map { it.pkg }, false, null, top0 + 3 * 96f + 112f)
    }

    private fun startName(): String? = act.cfg.startApp.takeIf { it.isNotEmpty() }?.let { act.app(it)?.label ?: it }

    /**
     * Every row under Pinned apps: your own rows, then the ready-made ones that fill and sort
     * themselves by kind of app. Off ready-made rows stay listed, greyed, so they can be turned on.
     */
    private fun paneRows() {
        paneHeader("App Rows", "Rows under Pinned apps. Ready-made ones fill themselves.")
        val ph = 290f
        pane.place(CategoryPreview(act), 48f, top1, PANE_W - 96f, ph)
        val add = optionColumn(top1 + ph + 24f, "rows")
        for ((ri, r) in act.cfg.rows.withIndex()) {
            if (r.auto.isNotEmpty()) continue
            add(R.drawable.ic_view_agenda, r.name, appCount(act.rowApps(r).size), SettingRow.Trail.NONE) {
                act.rowMenu(ri) { refresh("rows") }
            }
        }
        if (act.cfg.rows.size < Config.MAX_ROWS) add(R.drawable.ic_add, "New Row", "", SettingRow.Trail.NONE) { act.newRow { refresh("rows") } }
        for (k in AppCategory.KINDS) {
            val on = act.cfg.rows.any { it.auto == k.key }
            add(k.icon, k.name, "", SettingRow.Trail.SWITCH) {
                val r = act.cfg.rows.firstOrNull { it.auto == k.key }
                if (r != null) act.cfg.rows.remove(r)
                else if (act.cfg.rows.size >= Config.MAX_ROWS) { act.toast("Up to ${Config.MAX_ROWS} rows"); return@add }
                else act.cfg.rows.add(AppRow(k.name, 2, auto = k.key))
                act.save(); act.home.rebuild(); refresh("rows")
            }.apply {
                checked = on
                dimmed = !on
            }
        }
    }

    private fun paneLocks() {
        paneHeader("Locked Apps", "These ask for a passcode during their hours.")
        val locked = act.cfg.locked.entries.toList()
        if (locked.isEmpty()) { emptyState(R.drawable.ic_lock, "Hold OK on an app and choose Lock", 300f); return }
        val add = optionColumn(top1 + 20f, "locks")
        // Without a passcode the locks can't ask for one, so the list waits, dimmed.
        val owner = AppLocks.owner() != null
        for ((pkg, w) in locked) {
            val label = act.app(pkg)?.label ?: pkg
            add(R.drawable.ic_lock, label, AppLocks.WINDOWS[w].name, SettingRow.Trail.VALUE) { act.lockPicker(pkg, label) { refresh("locks") } }.dimmed = !owner
        }
        if (AppLocks.owner() == null) pane.place(act.label("No profile has a passcode, so these apps open freely. Set one in Profiles.",
            24f, Theme.TEXT_DIM, Theme.REGULAR, 2), 56f, PANE_H - 120f, PANE_W - 112f, 70f)
    }

    private fun paneStart() {
        paneHeader("Open on Start", "Opens one app after the TV starts. Scene stays your Home.")
        val cfg = act.cfg
        val add = optionColumn(top1 + 20f, "start")
        val name = startName()
        choice(add, R.drawable.ic_close, "Nothing", name == null) { cfg.startApp = ""; act.save(); refresh("start") }
        if (name != null) choice(add, R.drawable.ic_power_settings_new, name, true) { }
        add(R.drawable.ic_apps, if (name != null) "Choose Another App" else "Choose an App", "", SettingRow.Trail.CHEVRON) {
            act.push(AppPicker(act, "Open on start", "Opens after the TV starts", cfg.startApp) { e -> cfg.startApp = e.pkg; act.save(); refresh("start") })
        }
        add(R.drawable.ic_schedule, "Also After Sleep", "", SettingRow.Trail.SWITCH) {
            cfg.startWake = !cfg.startWake; act.save(); refresh("start")
        }.checked = cfg.startWake
    }

    private fun iconAlias() = android.content.ComponentName(act, "${act.packageName}.LauncherIcon")

    /** True when Scene's icon shows in the TV's app launcher. */
    private fun iconShown() =
        act.packageManager.getComponentEnabledSetting(iconAlias()) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun showIcon(on: Boolean) = act.packageManager.setComponentEnabledSetting(iconAlias(),
        if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        android.content.pm.PackageManager.DONT_KILL_APP)

    /** Scene's banner as the app launcher shows it, faded while hidden. */
    private fun paneAppIcon() {
        paneHeader("Show in App Launcher", "Adds Scene to the TV's own list of apps.")
        val on = iconShown()
        val w = 400f
        val h = 225f
        val y = top1 + 60f
        val banner = ImageView(act).apply {
            setImageResource(R.drawable.banner)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, Theme.px(18f)) }
            }
            alpha = if (on) 1f else 0.3f
        }
        pane.place(banner, (PANE_W - w) / 2, y, w, h)
        pane.place(centredText(if (on) "Shown in the app launcher" else "Hidden from the app launcher", 26f, Theme.TEXT_DIM), 48f, y + h + 32f, PANE_W - 96f, 40f)
    }

    private fun paneHidden() {
        val shown = hiddenInstalled()
        if (shown.isEmpty()) {
            paneHeader("Hidden Apps")
            emptyState(R.drawable.ic_visibility_off, "No hidden apps")
            return
        }
        paneHeader("Hidden Apps")
        tileGrid(shown, true, onPick = { pkg -> act.unhide(pkg); refresh("hidden") }, top = top0)

    }

    /** Hidden apps that are still installed. Others, such as the Stremio boot stub on a TV without it, are left out. */
    private fun hiddenInstalled(): List<String> = act.cfg.hidden.filter { act.app(it) != null }

    private fun paneCwHidden() {
        val n = act.hiddenContinueCount
        paneHeader("Hidden Titles")
        if (n == 0) {
            emptyState(R.drawable.ic_movie_off, "Nothing hidden from Continue watching")
            return
        }
        emptyState(R.drawable.ic_movie_off, if (n == 1) "1 title hidden on this TV" else "$n titles hidden on this TV")

    }

    /** Watch With: one app feeds Continue watching, the spotlight, search and the library. */
    private fun paneWatch() {
        val now = Watch.source(act)
        paneHeader("Watch With", "Continue watching, search and the library come from one app.")
        pane.place(WatchPreview(act, now), 48f, top1, PANE_W - 96f, 128f)
        val add = optionColumn(top1 + 152f, "watch")
        for (s in Source.values()) {
            choice(add, s.icon, s.label, s == now) {
                if (s == Watch.source(act)) return@choice
                val was = Watch.source(act)
                val lose = if (Watch.signedIn(act) && !(was == Source.STREMIO && Demo.on)) "Scene signs out of ${was.label} on this TV" else ""
                confirm("Watch with ${s.label}?", "Every profile on this TV changes over", "Use ${s.label}", s.icon, lose) {
                    WatchSignIn.start(act, s)
                }
            }.apply {
                brandIcon = true
                // Stremio is the tested path; the server apps are newer.
                pill = if (s == Source.STREMIO) "Recommended" else "Experimental"
                pillAccent = s == Source.STREMIO
            }
        }
    }

    private fun paneAccount() {
        when (Watch.source(act)) {
            Source.JELLYFIN -> { paneServerAccount(Source.JELLYFIN); return }
            Source.PLEX -> { paneServerAccount(Source.PLEX); return }
            Source.STREMIO -> {}
        }
        if (!Stremio.signedIn(act)) {
            paneHeader("Stremio Account")
            signedOutPane(R.drawable.ic_stremio, "Bring Stremio to Home", "Scan a code with your phone. No typing.",
                "Sign In with Phone", R.drawable.ic_qr_code_2) { act.openSignIn() }
            return
        }
        val email = Stremio.email(act) ?: "Signed in"
        paneHeader("Stremio Account")
        val card = surfaceCard(48f, top0, PANE_W - 96f, 160f)
        val logo = Panel(act, 48f, { Theme.elev }, false)
        card.place(logo, 32f, 32f, 96f, 96f)
        logo.place(ImageView(act).apply { setImageResource(R.drawable.ic_stremio) }, 20f, 20f, 56f, 56f)
        card.place(act.label(email, 30f, Theme.TEXT, Theme.MEDIUM), 156f, 42f, PANE_W - 96f - 190f, 42f)
        card.place(act.label("${act.continueItems.size} titles in progress", 24f, Theme.TEXT_DIM), 156f, 92f, 500f, 34f)
        val refresh = PillButton(act, "Refresh Now", R.drawable.ic_sync, false)
        refresh.tag = "acct0"
        refresh.setOnClickListener { act.refreshContinue(force = true); act.toast("Refreshing your library") }
        val out = PillButton(act, "Sign Out", R.drawable.ic_logout, false)
        out.setOnClickListener {
            confirm("Sign out of Stremio?", "For every profile on this TV", "Sign out", R.drawable.ic_logout,
                "Continue watching empties until you sign in again") { act.signedOut(); refresh("account") }
        }
        val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        buttons.addView(refresh, LinearLayout.LayoutParams(-2, Theme.ipx(80f)).apply { rightMargin = Theme.ipx(20f) })
        buttons.addView(out, LinearLayout.LayoutParams(-2, Theme.ipx(80f)))
        pane.place(buttons, 48f, top0 + 196f, PANE_W - 96f, 80f)
        Motion.stagger(listOf(card, buttons), 60L)
    }

    /** Jellyfin or Plex account: who and where, what it shows, and ways out. */
    private fun paneServerAccount(src: Source) {
        paneHeader("${src.label} Account")
        if (!Watch.signedIn(act)) {
            signedOutPane(src.icon, "Bring ${src.label} to Home", "Connect once and Scene keeps your progress in step.",
                "Connect", R.drawable.ic_link) { WatchSignIn.start(act, src) }
            return
        }
        val card = surfaceCard(48f, top0, PANE_W - 96f, 160f)
        val logo = Panel(act, 48f, { Theme.elev }, false)
        card.place(logo, 32f, 32f, 96f, 96f)
        logo.place(ImageView(act).apply { setImageResource(src.icon) }, 20f, 20f, 56f, 56f)
        card.place(act.label(Watch.account(act) ?: "Connected", 30f, Theme.TEXT, Theme.MEDIUM), 156f, 42f, PANE_W - 96f - 190f, 42f)
        card.place(act.label("${act.continueItems.size} titles in progress", 24f, Theme.TEXT_DIM), 156f, 92f, 500f, 34f)
        val add = optionColumn(top0 + 184f, "acct")
        add(R.drawable.ic_sync, "Refresh Now", "", SettingRow.Trail.NONE) {
            act.refreshContinue(force = true); act.toast("Refreshing your library")
        }
        if (src == Source.JELLYFIN) {
            add(R.drawable.ic_skip_next, "Include Next Up", "", SettingRow.Trail.SWITCH) {
                Jellyfin.setNextUp(act, !Jellyfin.nextUp(act))
                act.refreshContinue(force = true); refresh("account")
            }.checked = Jellyfin.nextUp(act)
        } else {
            add(R.drawable.ic_storage, "Server", Plex.serverName(act) ?: "", SettingRow.Trail.OPEN) { choosePlexServer() }
        }
        add(R.drawable.ic_logout, if (src == Source.PLEX) "Sign Out" else "Disconnect", "", SettingRow.Trail.NONE) {
            confirm(if (src == Source.PLEX) "Sign out of Plex?" else "Disconnect from Jellyfin?", "For every profile on this TV", if (src == Source.PLEX) "Sign out" else "Disconnect",
                R.drawable.ic_logout, "Continue watching comes from Stremio again") { act.signedOut(); refresh("account") }
        }
    }

    /** Plex: moves to another server on the same account. */
    private fun choosePlexServer() {
        act.toast("Looking for servers")
        Net.run({ Plex.otherServers(act) }) { list ->
            if (list.isNullOrEmpty()) { act.toast("No other Plex servers on this account"); return@run }
            val a = list.map { s ->
                Sheet.Action(s.name, R.drawable.ic_storage, if (s.owned) "Your server" else "Shared with you", checked = s.name == Plex.serverName(act)) {
                    Net.run({ Plex.reconnect(act, s) }) { ok ->
                        if (ok == true) { act.sourceChanged(Source.PLEX); act.toast("Connected to ${s.name}"); refresh("account") }
                        else act.toast("Couldn't reach ${s.name}")
                    }
                }
            }
            act.push(Sheet(act, "Plex server", "Continue watching and the library come from it", null, a))
        }
    }

    private fun paneBackup(export: Boolean) {
        paneHeader(if (export) "Export Settings" else "Restore Settings")
        val y = infoCard(listOf("Profile" to Profiles.current.name, "File" to Store.BACKUP, "Folder" to "Android/data/…/files"), top0)
        pane.place(BackupArt(act, export), 48f, y + 40f, PANE_W - 96f, 330f)
        if (backupStatus.isNotEmpty()) pane.place(act.label(backupStatus, 25f, Theme.accent, Theme.MEDIUM, 2), 56f, y + 390f, PANE_W - 112f, 70f)

    }

    private fun paneSetup() {
        paneHeader("Run Setup Again", "Walks through each choice again. Your apps and profiles stay.")
        pane.place(SetupArt(act), 24f, top1 + 40f, PANE_W - 48f, 440f)
        pane.place(centredText("Each step can be skipped", 26f, Theme.TEXT_DIM), 48f, top1 + 500f, PANE_W - 96f, 36f)
    }

    private fun paneReset() {
        paneHeader("Reset Home", "Only ${Profiles.current.name}'s Home changes.")
        val gap = 32f
        val colW = (PANE_W - 96f - gap) / 2
        val cards = ArrayList<View>()
        val resets = listOf("True Dark and glass", "Motion and focus", "Clock and weather", "Home row and recents", "Hidden apps", "All apps order")
        val keeps = listOf("Accent colour", "Pinned apps", "App names", "Weather location", "Stremio sign-in", "Other profiles")
        // Both cards take the taller list's height.
        val cardH = 92f + maxOf(resets.size, keeps.size) * 48f + 24f
        fun column(x: Float, title: String, icon: Int, items: List<String>) {
            val card = surfaceCard(x, top1 + 8f, colW, cardH)
            card.place(ImageView(act).apply { setImageDrawable(act.icon(icon, Theme.accent)) }, 32f, 32f, 36f, 36f)
            card.place(act.label(title, 30f, Theme.TEXT, Theme.SEMIBOLD), 84f, 28f, colW - 110f, 42f)
            var y = 96f
            for (s in items) {
                card.place(act.label(s, 24f, Theme.TEXT_DIM), 32f, y, colW - 64f, 36f)
                y += 48f
            }
            cards.add(card)
        }
        column(48f, "Resets", R.drawable.ic_refresh, resets)
        column(48f + colW + gap, "Keeps", R.drawable.ic_check, keeps)
        Motion.stagger(cards, 60L)

    }

    /** Shows the support link as a QR code. */
    private fun paneKofi() {
        paneHeader("Support Scene", "Scene is free and ad-free. A coffee keeps it growing.")
        pane.place(KofiCard(), 48f, 212f, PANE_W - 96f, 452f)
        pane.place(centredText("Thank you for using Scene", 26f, Theme.TEXT_DIM), 48f, 692f, PANE_W - 96f, 36f)
    }

    /** Ko-fi card: the QR code on the left, and how to use it on the right. */
    private inner class KofiCard : View(act) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val qr = act.getDrawable(R.drawable.qr_kofi)!!
        private val heart = act.icon(R.drawable.ic_favorite, Theme.onAccent)
        private val title = textPaint(34f, Theme.TEXT, Theme.BOLD)
        private val link = textPaint(24f, Theme.accent, Theme.MEDIUM)
        private val step = textPaint(22f, Theme.TEXT_DIM)
        private val num = textPaint(18f, Theme.TEXT, Theme.SEMIBOLD)

        override fun onDraw(c: android.graphics.Canvas) {
            drawCard(c, p, width, height, Theme.elev)
            fun x(v: Float) = Theme.px(v)
            // White QR card with an accent ring.
            val ql = x(60f); val qt = x(76f); val qs = x(300f); val qr0 = x(28f)
            p.color = Theme.withAlpha(Theme.accent, 0.55f)
            c.drawRoundRect(ql - x(6f), qt - x(6f), ql + qs + x(6f), qt + qs + x(6f), qr0 + x(6f), qr0 + x(6f), p)
            p.color = android.graphics.Color.WHITE
            p.setShadowLayer(x(51f), 0f, x(24f), 0x80000000.toInt())
            c.drawRoundRect(ql, qt, ql + qs, qt + qs, qr0, qr0, p)
            p.clearShadowLayer()
            qr.setBounds((ql + x(18f)).toInt(), (qt + x(18f)).toInt(), (ql + qs - x(18f)).toInt(), (qt + qs - x(18f)).toInt())
            qr.draw(c)
            // Badge, title, link and three steps, centred on the card's height.
            val sx = x(420f)
            var y = (height - x(338f)) / 2
            val br = x(36f)
            Theme.accentFill(p, sx, y, sx + br * 2, y + br * 2)
            p.setShadowLayer(x(22f), 0f, x(10f), Theme.withAlpha(Theme.accent, 0.4f))
            c.drawCircle(sx + br, y + br, br, p)
            p.clearShadowLayer()
            p.shader = null
            val hs = x(38f)
            heart.setBounds((sx + br - hs / 2).toInt(), (y + br - hs / 2).toInt(), (sx + br + hs / 2).toInt(), (y + br + hs / 2).toInt())
            heart.draw(c)
            y += x(96f)
            c.drawText("Buy me a coffee", sx, baseline(title, y + x(22f)), title)
            y += x(48f)
            c.drawText("ko-fi.com/robbydock", sx, baseline(link, y + x(16f)), link)
            y += x(62f)
            val lines = listOf("Open your phone camera", "Point it at the code", "Tip what feels right")
            for ((i, s) in lines.withIndex()) {
                val cy = y + x(17f)
                p.color = Theme.float
                c.drawCircle(sx + x(17f), cy, x(17f), p)
                val n = "${i + 1}"
                c.drawText(n, sx + x(17f) - num.measureText(n) / 2, baseline(num, cy), num)
                c.drawText(s, sx + x(48f), baseline(step, cy), step)
                y += x(50f)
            }
        }
    }

    private fun paneAbout() {
        paneHeader("About Scene")
        val am = act.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val own = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        // Device card: its name, then versions.
        val card = Panel(act, 36f, { Theme.surface })
        pane.place(card, 48f, top0, PANE_W - 96f, 300f)
        val tx = 40f
        card.place(act.label(DeviceCatalog.name(), 34f, Theme.accent, Theme.BOLD), tx, 44f, PANE_W - 96f - tx - 28f, 46f)
        val facts = listOf(
            "Brand" to Build.BRAND.replaceFirstChar { it.uppercase() },
            "Android" to Build.VERSION.RELEASE,
            "Security patch" to Build.VERSION.SECURITY_PATCH,
            "Scene" to versionName(),
        )
        for ((i, kv) in facts.withIndex()) {
            val fy = 108f + i * 40f
            card.place(act.label(kv.first, 24f, Theme.TEXT_DIM, Theme.MEDIUM), tx, fy, 190f, 34f)
            card.place(act.label(kv.second, 24f, Theme.TEXT, Theme.MEDIUM), tx + 190f, fy, PANE_W - 96f - tx - 218f, 34f)
        }
        var y = top0 + 340f

        // Memory: this launcher, everything else, free.
        val mb = 1024L * 1024
        val homeMem = own.totalPss * 1024L
        section("Memory  ·  ${mi.availMem / mb} MB free of ${mi.totalMem / mb} MB", y)
        pane.place(UsageBar(act).apply {
            parts = listOf(
                UsageBar.Part("Scene ${homeMem / mb} MB", homeMem, Theme.accent),
                UsageBar.Part("Other apps", (mi.totalMem - mi.availMem - homeMem).coerceAtLeast(0), Theme.withAlpha(Theme.TEXT, 0.45f)),
                UsageBar.Part("Free", mi.availMem, Theme.withAlpha(Theme.TEXT, 0.12f)),
            )
        }, 56f, y + 44f, PANE_W - 112f, 80f)
        y += 160f

        // Storage: filled in off the main thread.
        val storeTitle = act.label("Storage", 24f, Theme.TEXT_DIM, Theme.SEMIBOLD)
        pane.place(storeTitle, 56f, y, PANE_W - 112f, 32f)
        val storeBar = UsageBar(act)
        pane.place(storeBar, 56f, y + 44f, PANE_W - 112f, 80f)
        val clear = PillButton(act, "Clear artwork cache", R.drawable.ic_delete, false).apply { tag = "about0" }
        pane.place(clear, 48f, y + 148f, -1f, 72f)
        val fillStorage = {
            Net.run({
                val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                Triple(st.totalBytes, st.availableBytes, Images.diskUsage())
            }) { r ->
                if (r == null || current != "about") return@run
                val (total, free, cache) = r
                val gb = 1024.0 * 1024 * 1024
                storeTitle.text = "Storage  ·  %.1f GB free of %.1f GB".format(free / gb, total / gb)
                storeBar.parts = listOf(
                    UsageBar.Part("Artwork ${cache.first / mb} MB", cache.first, Theme.accent),
                    UsageBar.Part("Apps and data", (total - free - cache.first).coerceAtLeast(0), Theme.withAlpha(Theme.TEXT, 0.45f)),
                    UsageBar.Part("Free", free, Theme.withAlpha(Theme.TEXT, 0.12f)),
                )
            }
        }
        fillStorage()
        clear.setOnClickListener {
            Images.trimMemory()
            Net.run({ Images.clearDisk() }) { act.toast("Artwork cache cleared"); fillStorage() }
        }
        paneHint("Material Symbols  ·  Open-Meteo  ·  Cinemeta")
    }
}
