package com.robbydock.scene

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Global search: Stremio titles from Cinemeta, installed apps, searches handed to other apps
 * (YouTube, Play Store, Google and any app that accepts a search), and Scene and TV settings.
 * Voice uses the system speech recognizer. The field and the result rows span the full width.
 */
@SuppressLint("ViewConstructor")
class SearchScreen(act: HomeActivity, private val startVoice: Boolean) : OverlayBase(act, 0.82f, haze = true), BackAware, HomeActivity.Closable {

    companion object {
        const val SPEECH_REQUEST = 41
        /** Popular titles added to the row at a time. */
        const val BATCH = 20
        /** Field and results share the screen's side margins. Rows scroll under soft edges. */
        const val RES_X = 96f
        val RES_W get() = Theme.W - RES_X * 2
        /** How far the field and first row move up while the keyboard shows. */
        const val KEYBOARD_LIFT = 36f
        /** Result rows starting at or below this, in the results area, sit under the keyboard. */
        const val KEYBOARD_ROW_LIMIT = 400f
    }

    /** A settings page. [action] opens a TV settings page; [settingsKey] opens a Scene settings row. */
    private class Shortcut(val title: String, val where: String, val keywords: String, val icon: Int, val action: String? = null, val settingsKey: String? = null)

    private val shortcuts = listOf(
        Shortcut("Accent Colour", "Home", "accent colour color theme palette", R.drawable.ic_palette, settingsKey = "accent"),
        Shortcut("True Dark", "Home", "dark black oled theme night", R.drawable.ic_dark_mode, settingsKey = "dark"),
        Shortcut("Glass Strength", "Home", "glass blur haze transparency", R.drawable.ic_opacity, settingsKey = "glass"),
        Shortcut("Weather Location", "Home", "weather location city temperature forecast", R.drawable.ic_location_on, settingsKey = "clock"),
        Shortcut("Temperature Unit", "Home", "celsius fahrenheit units temperature", R.drawable.ic_thermostat, settingsKey = "clock"),
        Shortcut("Clock Format", "Home", "clock time 12 24 hour format", R.drawable.ic_schedule, settingsKey = "clock"),
        Shortcut("Spotlight", "Home", "spotlight stremio continue watching hero", R.drawable.ic_movie, settingsKey = "spot"),
        Shortcut("Pinned Apps", "Home", "pin pinned favourite favorite apps", R.drawable.ic_push_pin, settingsKey = "pins"),
        Shortcut("Screensaver", "Device", "screensaver screen saver clock dream ambient burn", R.drawable.ic_schedule, settingsKey = "dream"),
        Shortcut("Sounds", "Appearance", "sound sounds click beep audio feedback", R.drawable.ic_volume_up, settingsKey = "sounds"),
        Shortcut("Home Row", "Home", "home row recent recents apps pinned none", R.drawable.ic_apps, settingsKey = "homerow"),
        Shortcut("Hidden Apps", "Home", "hide hidden apps unhide", R.drawable.ic_visibility_off, settingsKey = "hidden"),
        Shortcut("Stremio Account", "Home", "stremio account sign in login sign out", R.drawable.ic_account_circle, settingsKey = "account"),
        Shortcut("Backup & Restore", "Home", "backup export restore import", R.drawable.ic_backup, settingsKey = "export"),
        Shortcut("Network & Internet", "TV settings", "wifi wi-fi network internet ethernet connection proxy", R.drawable.ic_wifi, Settings.ACTION_WIFI_SETTINGS),
        Shortcut("Remotes & Accessories", "TV settings", "bluetooth remote controller gamepad accessories pair headphones speaker", R.drawable.ic_settings_remote, Settings.ACTION_BLUETOOTH_SETTINGS),
        Shortcut("Display", "TV settings", "display resolution hdr picture screen", R.drawable.ic_display_settings, "android.settings.DISPLAY_SETTINGS"),
        Shortcut("Sound", "TV settings", "sound audio volume surround dolby passthrough", R.drawable.ic_volume_up, "android.settings.SOUND_SETTINGS"),
        Shortcut("Apps", "TV settings", "apps applications permissions uninstall force stop cache", R.drawable.ic_apps, Settings.ACTION_APPLICATION_SETTINGS),
        Shortcut("Storage", "TV settings", "storage space disk free", R.drawable.ic_storage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Shortcut("Date & Time", "TV settings", "date time clock timezone", R.drawable.ic_schedule, Settings.ACTION_DATE_SETTINGS),
        Shortcut("Language", "TV settings", "language locale region", R.drawable.ic_language, Settings.ACTION_LOCALE_SETTINGS),
        Shortcut("Keyboard", "TV settings", "keyboard input gboard typing", R.drawable.ic_keyboard, Settings.ACTION_INPUT_METHOD_SETTINGS),
        Shortcut("Accessibility", "TV settings", "accessibility captions subtitles high contrast text", R.drawable.ic_accessibility_new, Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Shortcut("Privacy", "TV settings", "privacy ads usage diagnostics location", R.drawable.ic_shield, Settings.ACTION_PRIVACY_SETTINGS),
        Shortcut("Developer Options", "TV settings", "developer debugging adb usb", R.drawable.ic_developer_mode, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Shortcut("About This TV", "TV settings", "about device system update version restart reset", R.drawable.ic_info, Settings.ACTION_DEVICE_INFO_SETTINGS),
        Shortcut("System Settings", "TV settings", "settings system device", R.drawable.ic_settings, Settings.ACTION_SETTINGS),
    )

    /** An app that can take the query, with the intent that hands it over. */
    private class Provider(val pkg: String, val label: String, val intent: (String) -> Intent)

    private val providers: List<Provider> by lazy { findProviders() }

    private val field = editField("Search films, shows and apps")
    private val pill = FieldPill(act, field)
    private val mic = RoundButton(act, R.drawable.ic_mic, true)
    private val voicePanel = Panel(act, 40f, { Theme.float }, false)
    private val wave = Wave(act)
    private val voiceLabel = act.label("Listening… say a title or an app", 30f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val results = FrameLayout(act).apply { clipChildren = false }
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var pending: Runnable? = null
    private var generation = 0
    private var closed = false
    private val layoutWatch = android.view.ViewTreeObserver.OnGlobalLayoutListener { fitKeyboard() }
    private var imeShown = -1

    init {
        place(pill, RES_X, 72f, RES_W, 112f)
        pill.place(ImageView(act).apply { setImageDrawable(act.icon(R.drawable.ic_search, Theme.TEXT_DIM)) }, 40f, 32f, 48f, 48f)
        pill.place(field, 112f, 28f, RES_W - 260f, 56f)
        pill.place(mic, RES_W - 96f, 16f, 80f, 80f)
        // While listening, the wave and prompt cover the text field inside the search pill.
        pill.place(voicePanel, 100f, 12f, RES_W - 220f, 88f)
        voicePanel.place(wave, 28f, 16f, 130f, 56f)
        voicePanel.place(voiceLabel, 188f, 24f, RES_W - 460f, 40f)
        voicePanel.visibility = GONE
        place(results, 0f, 220f, Theme.W, 860f)
        // Rows added while the keyboard shows are hidden too.
        results.setOnHierarchyChangeListener(object : OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) { post { hideCovered() } }
            override fun onChildViewRemoved(parent: View?, child: View?) { belowKeyboard.remove(child) }
        })

        mic.setOnClickListener { if (listening) stopListening() else beginVoice() }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (listening) return
                schedule(s?.toString().orEmpty())
            }
        })
        field.setOnEditorActionListener { v, _, _ ->
            hideKeyboard(v)
            firstResult()?.requestFocus()
            true
        }
        showResults("")
        post {
            if (startVoice) { mic.requestFocus(); beginVoice() } else showKeyboard(field)
        }
        setOnApplyWindowInsetsListener { _, insets -> post { fitKeyboard() }; insets }
        viewTreeObserver.addOnGlobalLayoutListener(layoutWatch)
        for (ms in longArrayOf(300L, 700L, 1200L)) postDelayed({ fitKeyboard() }, ms)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(layoutWatch)
        super.onDetachedFromWindow()
    }

    /** Results rows hidden while the keyboard shows. */
    private val belowKeyboard = ArrayList<View>()

    /** While the keyboard shows, lifts the field and first row a little and hides the rows it would cover. */
    private fun fitKeyboard() {
        if (android.os.Build.VERSION.SDK_INT < 30 || !isAttachedToWindow) return
        val ins = rootWindowInsets ?: return
        val type = android.view.WindowInsets.Type.ime()
        // The TV keyboard floats and reports no height, only that it is shown.
        val ime = if (ins.isVisible(type)) 1 else 0
        if (ime == imeShown) return
        imeShown = ime
        val up = if (ime > 0) Theme.px(-KEYBOARD_LIFT) else 0f
        for (v in listOf<View>(pill, results)) {
            if (Theme.MEDIUM_MS == 0L) v.translationY = up
            else v.animate().translationY(up).setDuration(Theme.MEDIUM_MS).setInterpolator(Theme.EASE).start()
        }
        hideCovered()
    }

    private fun hideCovered() {
        for (v in belowKeyboard) v.visibility = VISIBLE
        belowKeyboard.clear()
        if (imeShown <= 0) return
        val limit = Theme.px(KEYBOARD_ROW_LIMIT)
        for (i in 0 until results.childCount) {
            val v = results.getChildAt(i)
            val top = (v.layoutParams as? MarginLayoutParams)?.topMargin ?: continue
            if (top >= limit && v.visibility == VISIBLE) { v.visibility = INVISIBLE; belowKeyboard.add(v) }
        }
    }

    private fun firstResult(): View? = results.findViewWithTag<View>("first")

    private fun schedule(q: String) {
        pending?.let { removeCallbacks(it) }
        pending = Runnable { showResults(q) }.also { postDelayed(it, 350) }
    }

    // ---------------------------------------------------------------- results

    private fun findProviders(): List<Provider> {
        val pm = act.packageManager
        val out = ArrayList<Provider>()
        val taken = hashSetOf(Config.STREMIO, act.packageName)
        fun label(pkg: String) = act.app(pkg)?.label ?: Apps.label(pm, pkg) ?: pkg
        val yt = "com.google.android.youtube.tv"
        if (Apps.isInstalled(pm, yt) && yt !in act.cfg.hidden) {
            out.add(Provider(yt, label(yt)) { q -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).setPackage(yt) })
            taken.add(yt)
        }
        val store = "com.android.vending"
        if (Apps.isInstalled(pm, store)) {
            out.add(Provider(store, label(store)) { q -> Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q) + "&c=apps")).setPackage(store) })
            taken.add(store)
        }
        for (e in act.apps) {
            if (e.pkg in taken || e.pkg in act.cfg.hidden) continue
            val probe = Intent(Intent.ACTION_SEARCH).setPackage(e.pkg)
            if (probe.resolveActivity(pm) != null) {
                out.add(Provider(e.pkg, e.label) { q -> Intent(Intent.ACTION_SEARCH).setPackage(e.pkg).putExtra(SearchManager.QUERY, q) })
            }
        }
        return out
    }

    private val resolvable = HashMap<String, Boolean>()

    private fun available(s: Shortcut): Boolean {
        val a = s.action ?: return true
        return resolvable.getOrPut(a) { Intent(a).resolveActivity(act.packageManager) != null }
    }

    private fun openShortcut(s: Shortcut) {
        if (s.settingsKey != null) { act.pop(); act.openSettings(s.settingsKey) } else act.openSystemSettings(s.action ?: Settings.ACTION_SETTINGS)
    }

    /**
     * A sideways row that spans the whole screen. Items start at the side margin, scroll under
     * soft faded edges, and have room above and below for focus scale and shadows.
     */
    private fun edgeRow(y: Float, itemH: Float, gap: Float, items: List<View>, tagFirst: Boolean, widthOf: (View) -> Int = { -2 }): LinearLayout {
        val padV = 28f
        val scroller = android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(Theme.ipx(RES_X))
            clipToPadding = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(Theme.ipx(RES_X), Theme.ipx(padV), Theme.ipx(RES_X), Theme.ipx(padV + 16f))
        }
        scroller.addView(row)
        results.place(scroller, 0f, y - padV, Theme.W, itemH + padV * 2 + 16f)
        for ((i, v) in items.withIndex()) {
            if (i == 0 && tagFirst) v.tag = "first"
            row.addView(v, LinearLayout.LayoutParams(widthOf(v), Theme.ipx(itemH)).apply { if (i < items.size - 1) rightMargin = Theme.ipx(gap) })
        }
        return row
    }

    /** Fills [row] with popular titles in batches, loading more as focus nears the end. */
    private fun popularFeed(row: LinearLayout, first: List<SearchHit>) {
        val buffer = ArrayDeque(first)
        val seen = HashSet(first.map { it.id })
        var page = 1
        var fetching = false
        var more: () -> Unit = {}
        val append = { n: Int ->
            repeat(minOf(n, buffer.size)) {
                val h = buffer.removeFirst()
                val card = PosterCard(act).apply {
                    title = h.name
                    year = h.year
                    setOnClickListener { act.openDetail(h.type, h.id) }
                }
                loadPoster(card, h)
                val index = row.childCount
                card.setOnFocusChangeListener { _, has -> if (has && index >= row.childCount - 8) more() }
                (row.getChildAt(row.childCount - 1)?.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = Theme.ipx(28f)
                row.addView(card, LinearLayout.LayoutParams(Theme.ipx(200f), Theme.ipx(300f)))
            }
        }
        more = {
            if (buffer.size >= BATCH) append(BATCH)
            else if (!fetching && page < 40) {
                fetching = true
                val gen = generation
                Net.run({ Stremio.popular(page) }) { hits ->
                    fetching = false
                    if (gen != generation || closed || hits.isNullOrEmpty()) return@run
                    page++
                    hits.filter { seen.add(it.id) }.forEach { buffer.addLast(it) }
                    append(BATCH)
                }
            }
        }
        append(BATCH)
    }

    private fun pillRow(y: Float, header: String, pills: List<View>, tagFirst: Boolean) {
        if (pills.isEmpty()) return
        results.place(act.label(header, 30f, Theme.TEXT, Theme.SEMIBOLD), RES_X, y, RES_W, 40f)
        edgeRow(y + 60f, 104f, 24f, pills, tagFirst)
    }

    private fun posterUrl(id: String) = "https://images.metahub.space/poster/medium/$id/img"

    /** Loads the Cinemeta poster, falling back to Metahub's copy when it is missing or broken. */
    private fun loadPoster(card: PosterCard, hit: SearchHit) {
        card.loading = true
        val fallback = if (hit.id.startsWith("tt")) posterUrl(hit.id) else null
        val first = hit.poster ?: fallback
        Images.load(first, Theme.ipx(220f)) { b ->
            if (b != null || fallback == null || first == fallback) { card.loading = false; card.art = b; return@load }
            Images.load(fallback, Theme.ipx(220f)) { b2 -> card.loading = false; card.art = b2 }
        }
    }

    /** The query on screen now. Re-sent identical text does not rebuild. */
    private var shownQuery: String? = null

    private fun showResults(query: String) {
        val q = query.trim()
        if (q == shownQuery) return
        shownQuery = q
        val gen = ++generation
        // Moves focus to the new first result after a rebuild.
        val hadFocus = results.hasFocus()
        if (hadFocus) post { if (gen == generation) firstResult()?.requestFocus() ?: field.requestFocus() }
        results.removeAllViews()
        var y = 10f
        if (q.length >= 2) {
            results.place(act.label("Films and series", 30f, Theme.TEXT, Theme.SEMIBOLD), RES_X, y, RES_W, 40f)
            val loading = act.label("Searching…", 24f, Theme.TEXT_DIM)
            results.place(loading, RES_X, y + 64f, RES_W, 34f)
            val rowY = y + 64f
            Net.run({ Stremio.search(q) }) { hits ->
                if (gen != generation || closed) return@run
                loading.visibility = GONE
                if (hits.isNullOrEmpty()) {
                    loading.text = when {
                        !act.online -> "You're offline. Apps and settings below still work."
                        hits == null -> "Couldn't reach Cinemeta."
                        else -> "No films or series found."
                    }
                    loading.visibility = VISIBLE
                    return@run
                }
                val cards = hits.map { h ->
                    PosterCard(act).apply {
                        title = h.name
                        year = h.year
                        setOnClickListener { act.openDetail(h.type, h.id) }
                    }.also { loadPoster(it, h) }
                }
                edgeRow(rowY, 300f, 28f, cards, true) { Theme.ipx(200f) }
                // Keeps something focused.
                if (findFocus() == null) post { firstResult()?.requestFocus() ?: field.requestFocus() }
            }
            y += 410f
        } else if (q.isEmpty()) {
            // Empty state: popular titles to browse, then quick actions.
            results.place(act.label("Popular on Stremio", 30f, Theme.TEXT, Theme.SEMIBOLD), RES_X, y, RES_W, 40f)
            val status = act.label(if (act.online) "Loading…" else "You're offline. Popular titles come back when the connection does.", 24f, Theme.TEXT_DIM)
            results.place(status, RES_X, y + 64f, RES_W, 34f)
            val rowY = y + 64f
            if (act.online) {
                // Placeholder posters hold the row's shape while titles load.
                val shells = List(8) { PosterCard(act).apply { loading = true; isFocusable = false } }
                edgeRow(rowY, 300f, 28f, shells, false) { Theme.ipx(200f) }
                status.visibility = GONE
                val shellRow = results.getChildAt(results.childCount - 1)
                Net.run({ Stremio.popular() }) { hits ->
                    if (gen != generation || closed) return@run
                    results.removeView(shellRow)
                    if (hits.isNullOrEmpty()) {
                        status.text = "Couldn't load popular titles. Type to search."
                        status.visibility = VISIBLE
                        return@run
                    }
                    popularFeed(edgeRow(rowY, 300f, 28f, emptyList(), false) { Theme.ipx(200f) }, hits)
                }
            }
            y += 420f
            val quick = listOf(
                ResultPill(act, "Voice Search", "Say a title or an app", null, R.drawable.ic_mic).apply { setOnClickListener { beginVoice() } },
                ResultPill(act, "Launcher Settings", "Scene", null, R.drawable.ic_dashboard_customize).apply { setOnClickListener { act.pop(); act.openSettings() } },
                ResultPill(act, "System Settings", "Android TV", null, R.drawable.ic_settings).apply { setOnClickListener { act.openSystemSettings() } },
            )
            pillRow(y, "Quick actions", quick, true)
            return
        }

        val lower = q.lowercase()
        val apps = ArrayList<View>()
        act.apps.filter { it.pkg !in act.cfg.hidden && it.label.lowercase().contains(lower) }.take(6).forEach { e ->
            apps.add(ResultPill(act, e.label, "Open app", e.icon, 0).apply { setOnClickListener { act.launch(e.pkg) } })
        }
        for (p in providers) {
            apps.add(ResultPill(act, "Search “$q” in ${p.label}", p.label, act.app(p.pkg)?.icon, R.drawable.ic_search).apply {
                setOnClickListener { act.start(p.intent(q), "${p.label} can't search right now") }
            })
        }
        pillRow(y, "Apps", apps, q.length < 2)
        if (apps.isNotEmpty()) y += 214f

        val words = lower.split(' ').filter { it.isNotEmpty() }
        val settings = shortcuts.filter { s ->
            available(s) && (s.title.lowercase().contains(lower) || words.all { w -> s.keywords.split(' ').any { it.startsWith(w) } })
        }.take(8).map { s ->
            ResultPill(act, s.title, s.where, null, s.icon).apply { setOnClickListener { openShortcut(s) } }
        }
        pillRow(y, "Settings", settings, q.length < 2 && apps.isEmpty())
    }

    // ---------------------------------------------------------------- voice

    private fun beginVoice() {
        act.withMicPermission { ok ->
            if (closed) return@withMicPermission
            if (ok && SpeechRecognizer.isRecognitionAvailable(act)) startListening() else fallbackIntent()
        }
    }

    private var retried = false

    private fun startListening() {
        // Creates a fresh recognizer each time.
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(act).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { voiceLabel.text = "Listening… say a title or an app" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = wave.level(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { voiceLabel.text = "Working…" }
            override fun onError(error: Int) {
                listening = false
                mic.active = false
                wave.level(0f)
                // Retries once on a busy or disconnected recognizer.
                if (!retried && !closed && (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT)) {
                    retried = true
                    postDelayed({ if (!closed) startListening() }, 400)
                    return
                }
                retried = false
                voiceLabel.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Press the mic to try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs a network connection."
                    else -> "Voice isn't available right now."
                }
                postDelayed({ if (!listening) voicePanel.visibility = GONE }, 2500)
            }
            override fun onResults(b: Bundle?) {
                retried = false
                listening = false
                mic.active = false
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                voiceResult(text)
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    if (it.isNotEmpty()) { field.setText(it); field.setSelection(it.length) }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listening = true
        mic.active = true
        voicePanel.visibility = VISIBLE
        r.startListening(intent)
    }

    private fun stopListening() {
        recognizer?.stopListening()
        listening = false
        mic.active = false
        voicePanel.visibility = GONE
    }

    @Suppress("DEPRECATION")
    private fun fallbackIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        try {
            act.startActivityForResult(intent, SPEECH_REQUEST)
        } catch (e: Exception) {
            act.toast("Voice isn't available. Use the remote's mic button for Google Assistant.")
        }
    }

    fun voiceResult(text: String?) {
        if (text.isNullOrBlank()) {
            voiceLabel.text = "Didn't catch that. Press the mic to try again."
            return
        }
        voicePanel.visibility = GONE
        field.setText(text)
        field.setSelection(text.length)
        showResults(text)
        postDelayed({ firstResult()?.requestFocus() ?: mic.requestFocus() }, 900)
    }

    override fun onBack(): Boolean {
        if (listening) {
            recognizer?.cancel()
            listening = false
            mic.active = false
            voicePanel.visibility = GONE
            return true
        }
        return false
    }

    override fun onClosed() {
        closed = true
        recognizer?.destroy()
        recognizer = null
        hideKeyboard(field)
    }

    /** Seven bars that follow the microphone level. */
    class Wave(act: HomeActivity) : View(act) {
        private val bars = floatArrayOf(0.3f, 0.6f, 0.9f, 0.5f, 0.8f, 0.35f, 0.65f)
        private var amp = 0.2f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun level(db: Float) {
            amp = ((db + 2f) / 12f).coerceIn(0.15f, 1f)
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            p.color = Theme.accent
            val bw = Theme.px(10f)
            val gap = (width - bw * bars.size) / (bars.size - 1)
            for ((i, b) in bars.withIndex()) {
                val h = maxOf(bw, height * b * amp)
                val x = i * (bw + gap)
                c.drawRoundRect(x, (height - h) / 2, x + bw, (height + h) / 2, bw / 2, bw / 2, p)
            }
        }
    }
}
