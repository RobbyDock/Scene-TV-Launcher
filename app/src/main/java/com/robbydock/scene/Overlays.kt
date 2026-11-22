package com.robbydock.scene

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Full-screen overlay base. With [haze] on, it shows a small blurred copy of the layer
 * underneath under a dim. Without it, a near-solid dim.
 */
@SuppressLint("ViewConstructor")
open class OverlayBase(protected val act: HomeActivity, private val dim: Float = 0.86f, val haze: Boolean = false) : FrameLayout(act) {

    init {
        setWillNotDraw(false)
        clipChildren = false
        isClickable = true
    }

    /** A tap outside the panel closes it, as Back does. For side panels and dialogs. */
    protected open val tapAway = false
    private var downOutside = false

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!tapAway || !isEnabled) return super.onTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> downOutside = outside(e.x, e.y)
            android.view.MotionEvent.ACTION_UP -> if (downOutside && outside(e.x, e.y)) { downOutside = false; act.handleBack() }
            android.view.MotionEvent.ACTION_CANCEL -> downOutside = false
        }
        return true
    }

    /** True when ([x], [y]) misses every panel. Children that fill the screen do not count. */
    private fun outside(x: Float, y: Float): Boolean {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility != VISIBLE || (c.width >= width * 0.98f && c.height >= height * 0.98f)) continue
            if (x >= c.left + c.translationX && x < c.right + c.translationX && y >= c.top + c.translationY && y < c.bottom + c.translationY) return false
        }
        return true
    }

    /** Blurred copy of the screen underneath, drawn scaled up behind the dim. */
    var backdrop: Bitmap? = null
        set(v) { field = v; invalidate() }
    private val soft = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fit = Matrix()

    override fun onDraw(c: Canvas) {
        val b = backdrop
        if (b != null) {
            fit.setScale(width.toFloat() / b.width, height.toFloat() / b.height)
            c.drawBitmap(b, fit, soft)
        }
        c.drawColor(Theme.withAlpha(Theme.bg, if (b != null) dim else maxOf(dim, 0.95f)))
    }

    protected fun editField(hint: String): EditText = EditText(act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.px(40f))
        setTextColor(Theme.TEXT)
        setHintTextColor(Theme.TEXT_DIM)
        this.hint = hint
        typeface = Theme.MEDIUM
        background = null
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(0, 0, 0, 0)
    }

    protected fun hideKeyboard(v: View) {
        (act.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    protected fun showKeyboard(v: View) {
        v.requestFocus()
        v.post { act.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }
    }
}

/** Search field container: rounded float surface with a ring while the field has focus. */
@SuppressLint("ViewConstructor")
class FieldPill(act: HomeActivity, private val field: EditText) : FrameLayout(act) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        setWillNotDraw(false)
        clipChildren = false
        field.setOnFocusChangeListener { _, _ -> invalidate() }
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        elevation = Theme.px(20f)
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        fill.color = Theme.float
        c.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, fill)
        if (field.isFocused) {
            val sw = Theme.px(6f)
            ring.strokeWidth = sw
            ring.color = Theme.TEXT
            c.drawRoundRect(-sw / 2, -sw / 2, width + sw / 2, h + sw / 2, h / 2 + sw / 2, h / 2 + sw / 2, ring)
        }
    }
}

// ---------------------------------------------------------------- dialog

/**
 * Centred card for a message or a choice of one or two: icon, title, a short explanation and
 * buttons along the bottom. Lists of choices use [Sheet] instead.
 */
@SuppressLint("ViewConstructor")
class Dialog(
    act: HomeActivity, icon: Int, title: String, body: String, buttons: List<Button>,
    /** Shows this profile's picture in place of [icon]. */
    profile: Profile? = null,
) : OverlayBase(act, 0.6f, haze = true) {
    override val tapAway = true
    /** [focus] marks the button focused first. */
    class Button(val label: String, val primary: Boolean = false, val focus: Boolean = false, val run: () -> Unit = {})

    init {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            val pad = Theme.ipx(56f)
            setPadding(pad, Theme.ipx(48f), pad, Theme.ipx(44f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Theme.surface)
                cornerRadius = Theme.px(Theme.R_SHEET)
            }
            elevation = Theme.px(24f)
        }
        if (profile != null) {
            val av = AvatarView(act).apply {
                glow = true
                letter = profile.initial; emoji = profile.emoji; colorIndex = profile.color; locked = profile.locked
                isFocusable = false
            }
            card.addView(av, LinearLayout.LayoutParams(Theme.ipx(104f), Theme.ipx(104f)).apply { bottomMargin = Theme.ipx(24f) })
        } else {
            card.addView(IconBadge(act, icon), LinearLayout.LayoutParams(Theme.ipx(88f), Theme.ipx(88f)).apply { bottomMargin = Theme.ipx(24f) })
        }
        card.addView(act.label(title, 40f, Theme.TEXT, Theme.SEMIBOLD, lines = 2), LinearLayout.LayoutParams(-1, -2))
        if (body.isNotEmpty()) {
            card.addView(act.label(body, 26f, Theme.TEXT_DIM, lines = 4),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.ipx(16f) })
        }
        val row = LinearLayout(act).apply { gravity = android.view.Gravity.END; clipChildren = false }
        var first: View? = null
        for ((i, b) in buttons.withIndex()) {
            val v = PillButton(act, b.label, 0, b.primary).apply { setOnClickListener { act.pop(); b.run() } }
            row.addView(v, LinearLayout.LayoutParams(-2, Theme.ipx(76f)).apply { if (i > 0) leftMargin = Theme.ipx(20f) })
            if (b.focus || (first == null && b.primary)) first = v
        }
        if (first == null) first = row.getChildAt(row.childCount - 1)
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.ipx(40f) })
        addView(card, LayoutParams(Theme.ipx(880f), -2, android.view.Gravity.CENTER))
        post { first?.requestFocus() }
    }
}

// ---------------------------------------------------------------- side sheet

@SuppressLint("ViewConstructor")
class Sheet(
    act: HomeActivity, title: String, subtitle: String, iconBitmap: Bitmap?, actions: List<Action>,
    /** Optional preview shown under the choices, such as a location card. */
    footer: View? = null, footerH: Float = 0f,
    /** Shows this profile's picture beside the title. */
    headerProfile: Profile? = null,
    /** Title sheets lead with the title's poster. An empty string shows a film icon until art arrives. */
    posterUrl: String? = null,
) : OverlayBase(act, 0.5f, haze = true) {
    override val tapAway = true
    /** [profile] shows that profile's picture in place of [icon]. */
    class Action(val label: String, val icon: Int, val sub: String = "", val profile: Profile? = null, val checked: Boolean = false, val run: () -> Unit)

    init {
        val w = 760f
        // The panel fits its rows exactly, up to full height, and sits centred on the right.
        val footerSpace = if (footer != null) footerH + 24f else 0f
        val listH = minOf(790f - footerSpace, actions.size * 108f + 34f)
        val panelH = (200f + listH + footerSpace + 16f).coerceAtMost(1000f)
        val panel = Panel(act, Theme.R_SHEET, { Theme.surface })
        place(panel, Theme.W - w - 40f, (Theme.H - panelH) / 2, w, panelH)
        var tx = 48f
        if (iconBitmap != null) {
            val tile = AppTile(act).apply { icon = iconBitmap; isFocusable = false; isClickable = false }
            panel.place(tile, 48f, 52f, 112f, 112f)
            tx = 188f
        } else if (headerProfile != null) {
            val av = AvatarView(act).apply {
                glow = true
                letter = headerProfile.initial; emoji = headerProfile.emoji; colorIndex = headerProfile.color; locked = headerProfile.locked
                isFocusable = false
            }
            panel.place(av, 48f, 52f, 112f, 112f)
            tx = 188f
        } else if (posterUrl != null) {
            val ph = SheetPoster(act)
            panel.place(ph, 48f, 52f, 76f, 112f)
            if (posterUrl.isNotEmpty()) Images.load(posterUrl, Theme.ipx(152f)) { b -> ph.art = b }
            tx = 152f
        }
        panel.place(act.label(title, 40f, Theme.TEXT, Theme.SEMIBOLD), tx, if (subtitle.isEmpty()) 88f else 66f, w - tx - 48f, 54f)
        if (subtitle.isNotEmpty()) panel.place(act.label(subtitle, 24f, Theme.TEXT_DIM), tx, 128f, w - tx - 48f, 34f)
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(Theme.ipx(40f)) }
        val list = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(Theme.ipx(14f), Theme.ipx(10f), Theme.ipx(14f), Theme.ipx(24f))
        }
        scroll.addView(list, LayoutParams(-1, -2))
        // Clips long lists to the rounded panel.
        panel.clipToOutline = true
        panel.place(act.clipBox(scroll), 10f, 200f, w - 20f, listH)
        if (footer != null) panel.place(footer, 48f, 200f + listH + 24f, w - 96f, footerH)
        var first: View? = null
        for (a in actions) {
            val row = SettingRow(act, a.icon, a.label).apply {
                this.subtitle = a.sub
                avatar = a.profile
                trail = if (a.checked) SettingRow.Trail.CHECK else SettingRow.Trail.NONE
                checked = a.checked
                divider = false
                setOnClickListener { act.pop(); a.run() }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, Theme.ipx(104f)).apply { bottomMargin = Theme.ipx(4f) })
            if (first == null) first = row
        }
        post { first?.requestFocus() }
    }
}

// ---------------------------------------------------------------- app picker

@SuppressLint("ViewConstructor")
/** Full-screen app grid. Pins the chosen app, or hands it to [pick] when given. */
class AppPicker(
    act: HomeActivity,
    title: String = "Pin an app",
    sub: String = "Choose an app to add to your pinned row",
    current: String = "",
    pick: ((AppEntry) -> Unit)? = null,
) : OverlayBase(act, 0.72f, haze = true) {
    init {
        place(act.label(title, 64f, Theme.TEXT, Theme.BOLD), 96f, 72f, 1200f, 80f)
        place(act.label(sub, 26f, Theme.TEXT_DIM), 96f, 160f, 1200f, 36f)
        val list = act.apps.filter { it.pkg !in act.cfg.hidden && (pick != null || !act.isPinned(it.pkg)) }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        val grid = FrameLayout(act).apply { clipChildren = false }
        val cols = 8
        val pitchX = HomeScreen.TILE + HomeScreen.TILE_GAP
        val pitchY = HomeScreen.TILE_H + 40f
        val rows = (list.size + cols - 1) / cols
        scroll.addView(grid, LayoutParams(-1, Theme.ipx(rows * pitchY + 80f)))
        place(act.clipBox(scroll), 0f, 230f, Theme.W, 850f)
        var first: View? = null
        for ((i, e) in list.withIndex()) {
            val tile = AppTile(act).apply {
                label = e.label
                icon = e.icon
                setOnClickListener { act.pop(); if (pick != null) pick(e) else act.pin(e.pkg, e.label) }
            }
            grid.place(tile, 96f + (i % cols) * pitchX, 24f + (i / cols) * pitchY, HomeScreen.TILE, HomeScreen.TILE_H)
            if (first == null || e.pkg == current) first = tile
        }
        if (list.isEmpty()) place(act.label("Every app is already pinned or hidden.", 30f, Theme.TEXT_DIM), 96f, 260f, 1200f, 40f)
        post { first?.requestFocus() }
    }
}

// ---------------------------------------------------------------- remote button picker

/** Chooses the app a remote button opens, or keeps its usual action. */
@SuppressLint("ViewConstructor")
class RemapPicker(
    act: HomeActivity,
    private val button: Remap.Button,
    private val learned: Boolean,
    private val presses: Int = 1,
    private val done: () -> Unit,
) : OverlayBase(act, 0.72f, haze = true) {
    init {
        place(act.label("${button.name} button", 64f, Theme.TEXT, Theme.BOLD), 96f, 72f, 1200f, 80f)
        val note = if (presses > 1 && Remap.target(act, button.key) == null)
            "Choose what this does. With a double or triple press set, a single press only does what you pick for it."
        else "Choose what this does"
        place(act.label(note, 26f, Theme.TEXT_DIM), 96f, 160f, 1700f, 36f)

        // Single, double and triple press each have their own choice.
        val tabs = LinearLayout(act).apply { clipChildren = false; clipToPadding = false }
        var current: View? = null
        for ((n, name) in listOf(1 to "Press", 2 to "Double press", 3 to "Triple press")) {
            val set = Remap.target(act, button.key, n) != null
            val tab = PillButton(act, if (set) "$name •" else name, 0, n == presses)
            tab.setOnClickListener {
                if (n == presses) return@setOnClickListener
                act.pop()
                act.push(RemapPicker(act, button, learned, n, done))
            }
            tabs.addView(tab, LinearLayout.LayoutParams(-2, Theme.ipx(76f)).apply { rightMargin = Theme.ipx(20f) })
            if (n == presses) current = tab
        }
        place(tabs, 96f, 220f, 1700f, 90f)

        val target = Remap.target(act, button.key, presses)
        val actions = LinearLayout(act).apply { clipChildren = false; clipToPadding = false }
        fun action(text: String, icon: Int, chosen: Boolean, click: () -> Unit) {
            val b = PillButton(act, text, icon, chosen)
            b.setOnClickListener { click() }
            actions.addView(b, LinearLayout.LayoutParams(-2, Theme.ipx(76f)).apply { rightMargin = Theme.ipx(20f) })
        }
        action(if (presses == 1) "Usual action" else "Nothing", R.drawable.ic_settings_remote, target == null) { pick(null) }
        for (t in Remap.SCENE_TARGETS) action(Remap.label(t, act.apps), Remap.icon(t), target == t) { pick(t) }
        if (learned && presses == 1) action("Forget button", R.drawable.ic_delete, false) { Remap.forget(act, button.key); act.pop(); done() }
        place(actions, 96f, 320f, 1700f, 90f)

        val list = act.apps.filter { it.pkg !in act.cfg.hidden && it.pkg != act.packageName }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        val grid = FrameLayout(act).apply { clipChildren = false }
        val cols = 8
        val pitchX = HomeScreen.TILE + HomeScreen.TILE_GAP
        val pitchY = HomeScreen.TILE_H + 40f
        val rows = (list.size + cols - 1) / cols
        scroll.addView(grid, LayoutParams(-1, Theme.ipx(rows * pitchY + 80f)))
        place(act.clipBox(scroll), 0f, 420f, Theme.W, 660f)
        for ((i, e) in list.withIndex()) {
            val tile = AppTile(act).apply {
                label = e.label
                icon = e.icon
                setOnClickListener { pick(e.pkg) }
            }
            grid.place(tile, 96f + (i % cols) * pitchX, 24f + (i / cols) * pitchY, HomeScreen.TILE, HomeScreen.TILE_H)
        }
        post { current?.requestFocus() }
    }

    private fun pick(target: String?) {
        Remap.setTarget(act, button.key, target, presses)
        act.pop()
        done()
    }
}

// ---------------------------------------------------------------- location picker

@SuppressLint("ViewConstructor")
class LocationPicker(act: HomeActivity, private val done: () -> Unit) : OverlayBase(act, 0.97f) {
    private val field = editField("City name")
    private val results = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    private var pending: Runnable? = null

    init {
        place(act.label("Weather location", 64f, Theme.TEXT, Theme.BOLD), 96f, 72f, 1200f, 80f)
        place(act.label("Type a city, then move down to pick it. Weather comes from Open-Meteo.", 26f, Theme.TEXT_DIM), 96f, 160f, 1400f, 36f)
        val pill = FieldPill(act, field)
        place(pill, 96f, 230f, 1100f, 104f)
        pill.place(ImageView(act).apply { setImageDrawable(act.icon(R.drawable.ic_location_on, Theme.TEXT_DIM)) }, 36f, 30f, 44f, 44f)
        pill.place(field, 104f, 26f, 960f, 56f)
        place(results, 96f, 380f, 1100f, 680f)
        Weather.timeZoneCity()?.let { field.setText(it); search(it) }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { removeCallbacks(it) }
                val q = s?.toString().orEmpty()
                pending = Runnable { search(q) }.also { postDelayed(it, 400) }
            }
        })
        field.setOnEditorActionListener { v, _, _ -> hideKeyboard(v); results.getChildAt(0)?.requestFocus(); true }
        post { showKeyboard(field) }
    }

    private fun search(q: String) {
        if (q.trim().length < 2) { results.removeAllViews(); return }
        Net.run({ Weather.searchPlaces(q) }) { list ->
            results.removeAllViews()
            if (list.isNullOrEmpty()) {
                results.addView(act.label(if (list == null) "Couldn't reach the weather service." else "No places found.", 28f, Theme.TEXT_DIM))
                return@run
            }
            for (p in list) {
                val row = SettingRow(act, R.drawable.ic_location_on, p.name).apply {
                    subtitle = p.region
                    trail = SettingRow.Trail.NONE
                    divider = false
                    setOnClickListener {
                        act.cfg.city = p.name
                        act.cfg.lat = p.lat
                        act.cfg.lon = p.lon
                        act.save()
                        Weather.clearCache(act)
                        act.refreshWeather(force = true)
                        act.pop()
                        done()
                    }
                }
                results.addView(row, LinearLayout.LayoutParams(-1, Theme.ipx(104f)).apply { bottomMargin = Theme.ipx(8f) })
            }
        }
    }
}

// ---------------------------------------------------------------- sign in

/** Stremio link-code sign-in: QR plus a short code, polled until approved. */
@SuppressLint("ViewConstructor")
class SignInScreen(act: HomeActivity, expired: Boolean = false) : OverlayBase(act, 0.8f, haze = true), HomeActivity.Closable {
    private val qr = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val codeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val status = act.label("Getting a code…", 26f, Theme.TEXT_DIM)
    private val retry = PillButton(act, "Try again", R.drawable.ic_refresh, true)
    private var code: LinkCode? = null
    private var expires = 0L
    private var closed = false
    private val poll = Runnable { check() }
    private val clock = Runnable { tick() }

    init {
        val qrPanel = Panel(act, 40f, { android.graphics.Color.WHITE })
        place(qrPanel, 330f, 330f, 420f, 420f)
        qrPanel.place(qr, 30f, 30f, 360f, 360f)
        place(act.label(if (expired) "Sign in to Stremio again" else "Sign in to Stremio", 64f, Theme.TEXT, Theme.BOLD), 870f, 268f, 980f, 80f)
        if (expired) {
            // Shown when Stremio ends the session, such as after a password change.
            place(act.label("Your Stremio sign-in has ended. This can happen after a password change. Your settings are safe.",
                28f, Theme.accent, Theme.MEDIUM, 2), 870f, 170f, 980f, 84f)
        }
        val steps = listOf("Scan the code with your phone camera", "Or open link.stremio.com and enter this code", "Approve the sign-in on your phone")
        for ((i, s) in steps.withIndex()) {
            val y = 380f + i * 78f
            val dot = Panel(act, 28f, { Theme.elev }, false)
            place(dot, 870f, y, 56f, 56f)
            dot.place(act.label("${i + 1}", 26f, Theme.accent, Theme.SEMIBOLD).apply { gravity = android.view.Gravity.CENTER }, 0f, 0f, 56f, 56f)
            place(act.label(s, 30f, Theme.TEXT_DIM), 948f, y + 12f, 900f, 40f)
        }
        place(codeRow, 870f, 630f, 600f, 120f)
        place(status, 870f, 782f, 900f, 36f)
        place(retry, 870f, 840f, -1f, 80f)
        retry.visibility = GONE
        retry.setOnClickListener { start() }
        start()
    }

    private fun start() {
        retry.visibility = GONE
        status.text = "Getting a code…"
        codeRow.removeAllViews()
        qr.setImageDrawable(null)
        Net.run({ Stremio.createLink() }) { c ->
            if (closed) return@run
            if (c == null) { fail("Couldn't reach Stremio. Check the network and try again."); return@run }
            code = c
            expires = System.currentTimeMillis() + 5 * 60_000L
            for (ch in c.code) {
                val box = Panel(act, 24f, { Theme.elev }, false)
                codeRow.addView(box, LinearLayout.LayoutParams(Theme.ipx(96f), Theme.ipx(120f)).apply { rightMargin = Theme.ipx(16f) })
                box.place(act.label(ch.toString(), 64f, Theme.TEXT, Theme.BOLD).apply { gravity = android.view.Gravity.CENTER }, 0f, 0f, 96f, 120f)
            }
            Images.load(c.qr, Theme.ipx(360f), opaque = false) { b -> if (b != null) qr.setImageBitmap(b) }
            removeCallbacks(poll)
            postDelayed(poll, 3000)
            tick()
        }
    }

    private fun tick() {
        removeCallbacks(clock)
        if (closed || code == null) return
        val left = ((expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        status.text = "Waiting for approval · code expires in ${left / 60}:${"%02d".format(left % 60)}"
        if (left == 0L) { start(); return }
        postDelayed(clock, 1000)
    }

    private fun check() {
        val c = code ?: return
        if (closed) return
        Net.run({ Stremio.readLink(c.code) }) { key ->
            if (closed) return@run
            if (key == null) { postDelayed(poll, 3000); return@run }
            removeCallbacks(clock)
            status.text = "Signing in…"
            Net.run({ Stremio.completeSignIn(act, key); true }) { ok ->
                if (closed) return@run
                if (ok != true) { fail("Sign-in failed. Try again."); return@run }
                act.toast(Stremio.email(act)?.let { "Signed in as $it" } ?: "Signed in")
                act.pop()
                act.refreshContinue(force = true)
                act.home.rebuild()
            }
        }
    }

    private fun fail(msg: String) {
        removeCallbacks(clock)
        removeCallbacks(poll)
        status.text = msg
        retry.visibility = VISIBLE
        retry.requestFocus()
    }

    override fun onClosed() {
        closed = true
        removeCallbacks(poll)
        removeCallbacks(clock)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = super.dispatchKeyEvent(event)
}

/** Small poster at the head of a title sheet, with a film icon while there is no art. */
class SheetPoster(ctx: android.content.Context) : View(ctx) {
    var art: Bitmap? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = android.graphics.Path()
    private val film = ctx.icon(R.drawable.ic_movie, Theme.TEXT_DIM)

    init {
        // Shadow and a faint edge, as on the cards.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, view.width, view.height, Theme.px(16f))
        }
        clipToOutline = true
        elevation = Theme.px(12f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = Theme.px(16f)
        val b = art
        if (b == null) {
            p.color = Theme.elev
            c.drawRect(0f, 0f, w, h, p)
            val s = Theme.px(40f)
            film.setBounds(((w - s) / 2).toInt(), ((h - s) / 2).toInt(), ((w + s) / 2).toInt(), ((h + s) / 2).toInt())
            film.draw(c)
        } else {
            // Cover crop, drawn at full strength.
            p.color = android.graphics.Color.WHITE
            val k = maxOf(w / b.width, h / b.height)
            val dw = b.width * k; val dh = b.height * k
            c.drawBitmap(b, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = Theme.px(2f)
        p.color = 0x14FFFFFF
        c.drawRoundRect(Theme.px(1f), Theme.px(1f), w - Theme.px(1f), h - Theme.px(1f), r, r, p)
        p.style = Paint.Style.FILL
    }
}
