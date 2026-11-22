package com.robbydock.scene

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/** Overlays that want to handle Back themselves before being closed. */
interface BackAware {
    /** Returns true when Back was handled and the overlay should stay. */
    fun onBack(): Boolean
}

/** Single activity. Home is always present; Search, Settings, Sign-in and sheets stack above it. */
class HomeActivity : Activity() {

    companion object {
        const val HOME_ROLE_REQUEST = 42
        const val TV_SETTINGS = "com.android.tv.settings"
    }

    lateinit var cfg: Config
    lateinit var root: FrameLayout
    lateinit var home: HomeScreen
    private val overlays = ArrayList<Pair<View, View?>>()

    var apps: List<AppEntry> = emptyList()
        private set
    var continueItems: List<CwItem> = emptyList()
        private set
    var continueError = false
        private set
    var continueLoading = false
        private set
    var weather: WeatherNow? = null
        private set
    private var lastContinueFetch = 0L
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    /** True while the default network reaches the internet. */
    var online = true
    /** A network is connected, even if it has no internet. */
    var linkUp = true
        private set
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        linkUp = cm.activeNetwork != null
        online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) =
                post(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED), true)
            override fun onLost(network: android.net.Network) = post(false, false)
            private fun post(now: Boolean, link: Boolean) { Net.main.post { networkChanged(now, link) } }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (e: Exception) { }
    }

    /** Offline keeps the saved Home on screen; coming back online refreshes everything once. */
    private fun networkChanged(now: Boolean, link: Boolean) {
        if (isDestroyed || (now == online && link == linkUp)) return
        val was = online
        online = now
        linkUp = link
        // Going offline re-checks the row against the cache age.
        if (!now && was) setContinue(Stremio.cachedContinue(this, false))
        home.networkChanged(now, link, now != was)
        if (now == was) return
        if (now) {
            refreshContinue(force = true)
            refreshWeather(force = true)
        }
    }

    /** Set when the screen turns off. The profile picker shows on wake. */
    private var askProfileOnResume = false
    /** Set when the screen turns off. The start app opens on wake. */
    private var openOnWake = false
    private var screenOnAt = 0L
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) { screenOnAt = SystemClock.elapsedRealtime(); return }
            if (Profiles.askOnWake && Profiles.list.size > 1) askProfileOnResume = true
            else if (cfg.startWake && cfg.startApp.isNotEmpty()) openOnWake = true
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Images.init(this)
        Profiles.load(this)
        val saved = Store.load(this)
        cfg = saved ?: defaultConfig().also { Store.save(this, it) }
        // A first run records that setup is still to do, so a restart mid-setup resumes it.
        // An install from before this flag existed counts as set up.
        if (saved == null) statePrefs().edit().putBoolean("setupDone", false).apply()
        else if (!statePrefs().contains("setupDone")) statePrefs().edit().putBoolean("setupDone", true).apply()
        // Copies the saved accent to the profile colour.
        if (Profiles.current.color != cfg.accent) { Profiles.current.color = cfg.accent; Profiles.save(this) }
        Theme.apply(cfg.accent, cfg.trueDark, cfg.glass)
        Theme.motion = cfg.motion
        // Draws beside a phone's camera cutout too, so the layout fills the screen it measured.
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        root = object : FrameLayout(this) {
            // Some phones keep part of the screen, such as a camera cutout. Lays out again once
            // for the size the window really has.
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                super.onSizeChanged(w, h, ow, oh)
                if (Theme.measured == null && Theme.sizeDiffers(w, h)) {
                    Theme.measured = android.graphics.Rect(0, 0, w, h)
                    post { recreate() }
                }
            }
        }.apply {
            setBackgroundColor(Theme.bg)
            clipChildren = false
        }
        setContentView(root)
        hideSystemBars()
        Sounds.load(this)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new -> if (old != null && new != null) Sounds.move() }
        home = HomeScreen(this)
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        NowPlaying.start(this)
        watchNetwork()
        setContinue(Stremio.cachedContinue(this, online))
        weather = Weather.cached(this)
        Stremio.pruneMeta(this)
        home.rebuild()
        home.focusDefault()
        if (!statePrefs().getBoolean("setupDone", false)) push(SetupScreen(this))
        else if (isVoiceIntent(intent)) openSearch(true)
        else if (intent?.action == Remap.ACTION) remoteAction(intent)
        else if (Profiles.askOnWake && Profiles.list.size > 1) push(ProfilePicker(this))
        else openOnBoot()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
        if (savedInstanceState == null && cfg.splash && Theme.motion != 2) showSplash()
        Profiles.recovered?.let { msg -> home.postDelayed({ toast(msg) }, 1500) }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        // Checks now, again shortly after, then every minute.
        Net.main.removeCallbacks(syncTick)
        syncLibrary()
        Net.main.postDelayed(syncTick, 20_000L)
        refreshWeather(force = false)
        ClockDream.select(this)
        // Picks up notification access granted while Scene was away.
        NowPlaying.start(this)
        // Recent apps may have changed while another app was open.
        if (cfg.showRecent) home.appsChanged()
        (overlays.lastOrNull()?.first as? QuickPanel)?.onReturn()
        // Shows permissions granted in Android's settings at once.
        (overlays.lastOrNull()?.first as? SettingsScreen)?.onResumed()
        if (openOnWake) {
            openOnWake = false
            // Only when Home is what the TV wakes to, not when it is opened later.
            if (SystemClock.elapsedRealtime() - screenOnAt < 5_000L && overlays.isEmpty() && Apps.isInstalled(packageManager, cfg.startApp)) launch(cfg.startApp)
        }
        if (askProfileOnResume) {
            askProfileOnResume = false
            if (statePrefs().getBoolean("setupDone", false) && overlays.none { it.first is ProfilePicker || it.first is SetupScreen }) {
                while (overlays.isNotEmpty()) pop()
                push(ProfilePicker(this))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Net.main.removeCallbacks(syncTick)
    }

    private var libraryStamp: Int? = null
    private val syncTick = object : Runnable {
        override fun run() {
            syncLibrary()
            Net.main.postDelayed(this, 60_000L)
        }
    }

    /** Compares a small library fingerprint and fetches the full library only when it changed. */
    private fun syncLibrary() {
        if (!online || Demo.on) return
        if (!Stremio.signedIn(this)) { refreshContinue(force = false); return }
        Net.run({ Stremio.libraryStamp(this) }) { stamp ->
            if (isDestroyed) return@run
            when {
                stamp == null -> refreshContinue(force = false, maxAge = 5 * 60_000L)
                stamp != libraryStamp -> { libraryStamp = stamp; refreshContinue(force = true) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Images.trimDisk()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Drops decoded images and keeps the disk cache.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Images.trimMemory()
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) { }
        netCallback?.let { cb -> try { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (e: Exception) { } }
        super.onDestroy()
    }

    @Deprecated("Used below API 33 only")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) handleBack() else super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home closes everything except first-run setup, which stays until it is finished.
        while (overlays.isNotEmpty() && overlays.last().first !is SetupScreen) pop()
        if (overlays.isNotEmpty()) return
        home.goTop()
        if (!statePrefs().getBoolean("setupDone", false)) return
        if (isVoiceIntent(intent)) openSearch(true)
        else if (intent.action == Remap.ACTION) remoteAction(intent)
    }

    /** Opens the part of Scene a remapped remote button asks for. */
    private fun remoteAction(i: Intent) {
        when (i.getStringExtra(Remap.EXTRA)) {
            Remap.QUICK -> push(QuickPanel(this))
            Remap.SEARCH -> openSearch(false)
            Remap.VOICE -> openSearch(true)
        }
    }

    /** Intents sent by the remote's mic. */
    private fun isVoiceIntent(i: Intent?) = i?.action in setOf(Intent.ACTION_ASSIST, Intent.ACTION_SEARCH_LONG_PRESS, Intent.ACTION_VOICE_COMMAND, "android.speech.action.WEB_SEARCH")

    /** Back: closes the top overlay, or steps Home back. Also used by a tap outside a panel. */
    fun handleBack() {
        val top = overlays.lastOrNull()?.first
        if (top != null) {
            if (top is BackAware && top.onBack()) return
            pop()
        } else if (home.moving) {
            home.cancelMove()
        } else {
            home.goTop()
        }
    }

    // ---------------------------------------------------------------- overlays

    fun push(v: View) {
        val returnTo = root.findFocus()
        val below = overlays.lastOrNull()?.first ?: home
        (below as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val o = v as? OverlayBase
        if (o?.haze == true && below.width > 0) {
            o.backdrop = try { Images.blurSync(below.width, below.height) { below.draw(it) } } catch (e: Exception) { null }
        }
        overlays.add(v to returnTo)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.requestFocus()
        animateIn(v)
    }

    /**
     * Start animation over Home: an accent dot breathes out a ring, then fades while Home
     * settles in.
     */
    private fun showSplash() {
        val splash = FrameLayout(this).apply { setBackgroundColor(Theme.bg) }
        val pulse = StartPulse(this)
        splash.place(pulse, (Theme.W - 360f) / 2, (Theme.H - 360f) / 2, 360f, 360f)
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))
        home.alpha = 0f
        if (Theme.moves) { home.scaleX = 1.03f; home.scaleY = 1.03f }
        pulse.start()
        root.postDelayed({
            home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Theme.SCREEN_MS + 120).setInterpolator(Theme.EASE).start()
            splash.animate().alpha(0f).setDuration(Theme.SCREEN_MS + 60).withEndAction { root.removeView(splash) }.start()
        }, 650)
    }

    /** Side panels slide in from the right; full screens fade up from slightly smaller. */
    private fun animateIn(v: View) {
        val ms = Theme.SCREEN_MS
        if (ms == 0L) return
        v.alpha = 0f
        if (Theme.moves) {
            if (v is Sheet || v is QuickPanel) v.translationX = Theme.px(80f)
            else { v.scaleX = 0.97f; v.scaleY = 0.97f }
        }
        v.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(ms).setInterpolator(Theme.EASE).start()
    }

    fun pop() {
        val (v, returnTo) = overlays.removeLastOrNull() ?: return
        // Removes the view from focus and input while it fades out.
        val ms = Theme.SCREEN_MS
        if (ms == 0L || v.width == 0) root.removeView(v) else {
            (v as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            v.isEnabled = false
            v.animate().cancel()
            v.animate().alpha(0f).translationX(if (Theme.moves && (v is Sheet || v is QuickPanel)) Theme.px(60f) else 0f)
                .setDuration(ms * 3 / 4).setInterpolator(Theme.EASE).withEndAction { root.removeView(v) }.start()
        }
        (v as? Closable)?.onClosed()
        val below = overlays.lastOrNull()?.first ?: home
        (below as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (returnTo != null && returnTo.isAttachedToWindow) returnTo.requestFocus() else below.requestFocus()
        (below as? Returnable)?.onReturn()
    }

    fun popTo(v: View) {
        while (overlays.isNotEmpty() && overlays.last().first !== v) pop()
    }

    interface Closable { fun onClosed() }

    /** Overlays told when the overlay above them closes. */
    interface Returnable { fun onReturn() }

    fun runSetup() {
        while (overlays.isNotEmpty()) pop()
        statePrefs().edit().remove("setupStep").apply()
        push(SetupScreen(this))
    }

    fun setupDone() {
        statePrefs().edit().putBoolean("setupDone", true).remove("setupStep").apply()
        popTo(home)
        while (overlays.isNotEmpty()) pop()
        refreshWeather(force = true)
        home.goTop()
    }

    // ---------------------------------------------------------------- config

    private fun defaultConfig(): Config {
        val c = Config()
        c.hidden.add(Config.STREMIO_BOOT)
        val pm = packageManager
        val candidates = arrayOf(
            Config.STREMIO, "com.google.android.youtube.tv", "tv.twitch.android.app", "com.nuvio.tv",
            "com.valvesoftware.steamlink", "com.plexapp.android", "org.jellyfin.androidtv", "org.xbmc.kodi",
        )
        for (pkg in candidates) {
            if (c.pinned.size >= 6) break
            if (Apps.isInstalled(pm, pkg)) c.pinned.add(Pin(pkg, Apps.label(pm, pkg) ?: pkg))
        }
        return c
    }

    fun save() { if (!Store.save(this, cfg)) storageWarning() }

    private var warnedStorage = false

    /** Says once per run that a save failed. The old settings file is kept. */
    fun storageWarning() {
        if (warnedStorage) return
        warnedStorage = true
        toast("Storage is full, changes may not be saved")
    }

    /** Applies theme changes everywhere. Overlays rebuild their own views. */
    fun themeChanged() {
        Theme.apply(cfg.accent, cfg.trueDark, cfg.glass)
        Theme.motion = cfg.motion
        save()
        root.setBackgroundColor(Theme.bg)
        home.rebuild()
        root.invalidateAll()
    }

    /** Loads another profile's Home settings and redraws everything. */
    fun switchProfile(id: String) {
        if (Demo.on) {
            Profiles.select(this, id)
            cfg.accent = Profiles.current.color
            themeChanged()
            home.goTop()
            toast("Switched to ${Profiles.current.name}")
            return
        }
        val loc = cfg
        Profiles.select(this, id)
        cfg = Store.load(this) ?: defaultConfig().apply {
            city = loc.city; lat = loc.lat; lon = loc.lon; locAuto = loc.locAuto
            accent = Profiles.current.color
        }.also { Store.save(this, it) }
        applyNames()
        // Takes the accent from the profile colour.
        cfg.accent = Profiles.current.color
        setContinue(allContinue)
        themeChanged()
        refreshWeather(force = true)
        home.goTop()
        toast("Switched to ${Profiles.current.name}")
    }

    /** Opens the new-profile wizard. The profile is made only at its last step. */
    fun newProfile() {
        if (Profiles.list.size >= Profiles.MAX) { toast("Profiles are full"); return }
        push(ProfileWizard(this) { name, emoji, color ->
            if (Profiles.list.size >= Profiles.MAX) { toast("Profiles are full"); return@ProfileWizard }
            val p = Profiles.add(this, name, emoji, color)
            while (overlays.isNotEmpty()) pop()
            switchProfile(p.id)
        })
    }

    fun replaceConfig(c: Config) {
        cfg = c
        applyNames()
        save()
        themeChanged()
        refreshWeather(force = true)
    }

    // ---------------------------------------------------------------- demo

    private var realCfg: Config? = null

    /** Swaps in sample data. Saved settings are not changed until [endDemo]. */
    fun startDemo() {
        if (Demo.on) return
        realCfg = cfg
        Demo.start()
        Profiles.startDemo(Demo.profiles())
        val pm = packageManager
        cfg = cfg.copy().apply {
            pinned = Demo.pins { Apps.isInstalled(pm, it) }
            accent = Profiles.current.color
            hidden.clear(); hidden.add(Config.STREMIO_BOOT); cwHidden.clear(); names.clear(); recents.clear(); appOrder.clear()
            showPinned = true; showRecent = false; spotlight = true; clockOn = true; showDate = true; weatherOn = true; weatherDetail = 0
            city = "Chicago"
        }
        weather = Demo.weather()
        setContinue(Demo.continueItems())
        applyNames()
        themeChanged()
        home.appsChanged()
        home.continueChanged()
        home.weatherChanged()
        toast("Demo Mode on")
    }

    fun endDemo() {
        if (!Demo.on) return
        Demo.stop()
        Profiles.endDemo()
        cfg = realCfg ?: Store.load(this) ?: cfg
        realCfg = null
        weather = Weather.cached(this)
        setContinue(Stremio.cachedContinue(this, online))
        applyNames()
        themeChanged()
        home.appsChanged()
        home.continueChanged()
        home.weatherChanged()
        refreshContinue(force = true)
        toast("Demo Mode off")
    }

    // ---------------------------------------------------------------- data

    fun loadApps() {
        val size = Theme.ipx(176f)
        Net.run({ Apps.listAll(this, size) }) { list ->
            if (list == null || isDestroyed) return@run
            systemApps = list
            applyNames()
            home.appsChanged()
        }
    }

    fun app(pkg: String) = apps.firstOrNull { it.pkg == pkg }

    /** Apps with their system labels, before this profile's custom names. */
    private var systemApps: List<AppEntry> = emptyList()

    /** Applies this profile's custom names, keeping A to Z order by the name shown. */
    private fun applyNames() {
        val base = if (Demo.on) systemApps + Demo.apps(this, Theme.ipx(176f)) else systemApps
        apps = base.map { e -> cfg.names[e.pkg]?.let { AppEntry(e.pkg, it, e.icon, e.installed) } ?: e }
            .sortedBy { it.label.lowercase() }
    }

    fun systemLabel(pkg: String) = systemApps.firstOrNull { it.pkg == pkg }?.label

    /** Sets or clears ([name] null) a custom name. False when the cleaned name is empty. */
    fun rename(pkg: String, name: String?): Boolean {
        if (name == null) cfg.names.remove(pkg) else {
            val clean = Guard.cleanText(name, Config.MAX_NAME)
            if (clean.isEmpty()) return false
            if (clean == systemLabel(pkg)) cfg.names.remove(pkg)
            else if (cfg.names.size < Config.MAX_NAMES || pkg in cfg.names) cfg.names[pkg] = clean
            else { toast("Too many renamed apps"); return false }
        }
        val i = cfg.pinned.indexOfFirst { it.pkg == pkg }
        if (i >= 0) cfg.pinned[i] = Pin(pkg, cfg.names[pkg] ?: systemLabel(pkg) ?: cfg.pinned[i].label)
        save()
        applyNames()
        home.rebuild()
        return true
    }

    fun refreshContinue(force: Boolean, maxAge: Long = 45_000L) {
        if (Demo.on) return
        if (!Stremio.signedIn(this)) {
            if (continueItems.isNotEmpty()) { setContinue(emptyList()); home.continueChanged() }
            return
        }
        // Offline keeps the saved list.
        if (!online) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastContinueFetch < maxAge) return
        lastContinueFetch = now
        continueLoading = continueItems.isEmpty()
        if (continueLoading) home.continueChanged()
        Net.run({ Stremio.fetchContinue(this) }) { list ->
            if (isDestroyed) return@run
            continueLoading = false
            if (list != null) {
                continueError = false
                // Keeps new-episode marks from the last check until the next one finishes.
                val old = allContinue.associateBy { it.id }
                val kept = list.map { l -> old[l.id]?.takeIf { it.isNew }?.let { o -> l.withNew(o.newCount, if (l.started) null else o.videoId, o.lastWatched) } ?: l }
                val keepNew = allContinue.filter { it.isNew && list.none { l -> l.id == it.id } }
                setContinue((kept + keepNew).sortedByDescending { it.lastWatched })
                checkNewEpisodes(list)
            } else {
                continueError = continueItems.isEmpty()
                if (!Stremio.signedIn(this)) {
                    // Stremio ended the session, for example after a password change.
                    continueError = false
                    setContinue(emptyList())
                    if (overlays.none { it.first is SetupScreen || it.first is SignInScreen }) push(SignInScreen(this, expired = true))
                }
            }
            home.continueChanged()
        }
    }

    private var lastLocate = 0L

    /** Full screen, like a TV home screen. Devices that show bars, such as phones and tablets, can still swipe them in. */
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP && event.repeatCount == 0 &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER) &&
            currentFocus !is SoundPad) Sounds.ok(this)
        // OK sinks the focused item while it is held.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            val f = currentFocus as? FocusView
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) f?.press(true)
            else if (event.action == android.view.KeyEvent.ACTION_UP) f?.press(false)
        }
        // The Menu key on remotes that have one opens the same options as holding OK.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            val f = currentFocus
            if (f != null && f.isLongClickable) {
                if (event.action == android.view.KeyEvent.ACTION_UP && event.repeatCount == 0) f.performLongClick()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun statePrefs() = getSharedPreferences("state", MODE_PRIVATE)

    /**
     * Weather follows this TV's location when allowed. The permission is asked once; the saved
     * city stays as the fallback when it is denied or no position is available.
     */
    fun refreshWeather(force: Boolean) {
        if (!cfg.weatherOn || Demo.on) return
        if (cfg.locAuto) {
            if (!Weather.hasLocationPermission(this)) {
                // Skips the prompt during first-run setup.
                if (statePrefs().getBoolean("setupDone", false) && !statePrefs().getBoolean("locAsked", false)) {
                    statePrefs().edit().putBoolean("locAsked", true).apply()
                    withPermission(Manifest.permission.ACCESS_COARSE_LOCATION) { ok -> if (ok) refreshWeather(force = true) else fetchWeather(force) }
                    return
                }
            } else if (force || SystemClock.elapsedRealtime() - lastLocate > 6 * 3600_000L || lastLocate == 0L) {
                lastLocate = SystemClock.elapsedRealtime()
                Weather.deviceLocation(this) { loc ->
                    if (loc == null || isDestroyed) { fetchWeather(force); return@deviceLocation }
                    val moved = !cfg.hasLocation || Math.abs(cfg.lat - loc.latitude) > 0.05 || Math.abs(cfg.lon - loc.longitude) > 0.05
                    cfg.lat = loc.latitude
                    cfg.lon = loc.longitude
                    Net.run({ Weather.placeName(this, loc.latitude, loc.longitude) }) { name ->
                        if (isDestroyed) return@run
                        if (!name.isNullOrEmpty()) cfg.city = name
                        save()
                        fetchWeather(force || moved)
                    }
                }
                return
            }
        }
        fetchWeather(force)
    }

    private fun fetchWeather(force: Boolean) {
        if (!cfg.hasLocation) {
            val guess = Weather.timeZoneCity() ?: return
            Net.run({ Weather.searchPlaces(guess).firstOrNull() }) { place ->
                if (place == null || cfg.hasLocation) return@run
                cfg.city = place.name; cfg.lat = place.lat; cfg.lon = place.lon
                save()
                fetchWeather(force = true)
            }
            return
        }
        if (!force && !Weather.isStale(weather)) return
        val lat = cfg.lat
        val lon = cfg.lon
        Net.run({ Weather.fetch(this, lat, lon) }) { w ->
            if (w == null || isDestroyed) return@run
            weather = w
            home.weatherChanged()
        }
    }

    fun signedOut() {
        if (Demo.on) { toast("Not available in Demo Mode"); return }
        Stremio.signOut(this)
        setContinue(emptyList())
        home.continueChanged()
    }

    /** Looks for new episodes of followed series at low priority, then updates the row once. */
    private fun checkNewEpisodes(base: List<CwItem>) {
        Net.low {
            val merged = try { Stremio.withNewEpisodes(this, base) } catch (e: Exception) { null } ?: return@low
            Net.main.post {
                if (isDestroyed) return@post
                setContinue(merged)
                home.continueChanged()
            }
        }
    }

    /** Runs a library change off the main thread, then refreshes the row. */
    private fun libraryWrite(working: String, work: () -> String) {
        if (Demo.on) { toast("Not available in Demo Mode"); return }
        toast(working)
        Net.run({ try { work() } catch (e: Stremio.WriteFailed) { "Couldn't change it: ${e.message}" } }) { msg ->
            if (isDestroyed) return@run
            toast(msg ?: "Couldn't reach Stremio")
            refreshContinue(force = true)
        }
    }

    fun markWatched(item: CwItem) = libraryWrite("Marking as watched") { Stremio.markWatched(this, item) }

    fun setNotify(item: CwItem, on: Boolean) = libraryWrite(if (on) "Turning on new episode alerts" else "Turning off new episode alerts") {
        Stremio.setNotify(this, item.id, on)
        if (on) "New episode alerts on" else "New episode alerts off"
    }

    fun setInLibrary(item: CwItem, add: Boolean) = libraryWrite(if (add) "Adding to library" else "Removing from library") {
        Stremio.setInLibrary(this, item.id, add)
        if (add) "Added to your library" else "Removed from your library"
    }

    private var allContinue: List<CwItem> = emptyList()

    private fun cwKey(item: CwItem) = "${item.id}|${item.videoId ?: ""}"

    private fun setContinue(list: List<CwItem>) {
        allContinue = list
        continueItems = list.filter { cwKey(it) !in cfg.cwHidden }
    }

    val hiddenContinueCount get() = allContinue.count { cwKey(it) in cfg.cwHidden }

    fun hideContinue(item: CwItem) {
        cfg.cwHidden.add(cwKey(item))
        save()
        setContinue(allContinue)
        home.continueChanged()
    }

    fun unhideAllContinue() {
        cfg.cwHidden.clear()
        save()
        setContinue(allContinue)
        home.continueChanged()
    }

    // ---------------------------------------------------------------- actions

    private var toastView: TextView? = null

    /** Shows a short message in a pill at the bottom of the screen. */
    fun toast(s: String) {
        toastView?.let { root.removeView(it) }
        val t = TextView(this).apply {
            text = s
            setTextColor(Theme.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.px(26f))
            typeface = Theme.MEDIUM
            gravity = android.view.Gravity.CENTER
            maxWidth = Theme.ipx(1100f)
            setPadding(Theme.ipx(40f), Theme.ipx(20f), Theme.ipx(40f), Theme.ipx(20f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Theme.float)
                cornerRadius = Theme.px(40f)
            }
            alpha = 0f
            translationY = Theme.px(16f)
            isFocusable = false
        }
        root.addView(t, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Theme.ipx(72f)
        })
        toastView = t
        t.animate().alpha(1f).translationY(0f).setDuration(180).withEndAction {
            t.animate().alpha(0f).setStartDelay(2600).setDuration(220).withEndAction {
                root.removeView(t)
                if (toastView === t) toastView = null
            }
        }
    }

    // ---------------------------------------------------------------- recovery codes

    /** Runs [work] off the main thread, then [then] on it. */
    private fun <T> background(work: () -> T, then: (T) -> Unit) {
        Thread {
            val r = work()
            runOnUiThread { if (!isDestroyed) then(r) }
        }.start()
    }

    /**
     * Makes a new recovery code for the admin [p], replacing any old one, and shows it once.
     * [then] runs after the person confirms they saved it.
     */
    fun createRecovery(p: Profile, intro: String = "In case the passcode is ever forgotten.", then: () -> Unit = {}) {
        val code = Recovery.generate()
        background({ val salt = Recovery.newSalt(); salt to Recovery.hash(code, salt) }) { (salt, hash) ->
            p.recoverySalt = salt
            p.recoveryHash = hash
            if (!Profiles.save(this)) storageWarning()
            push(RecoveryCodeScreen(this, code, intro, then))
        }
    }

    /**
     * Checks a typed recovery code for [p]. Wrong codes count towards the same lockout as the
     * passcode. When it matches, the old code stops working: a new one is shown, then a new
     * passcode is asked for. [finished] runs at the end.
     */
    fun useRecovery(p: Profile, input: String, finished: () -> Unit) {
        val wait = Locks.remaining(this, p.id)
        if (wait > 0) { toast("Too many tries. Try again in ${(wait + 59_999) / 60_000} min."); return }
        background({ Profiles.checkRecovery(p, input) }) { ok ->
            if (!ok) {
                val w = Locks.wrong(this, p.id)
                toast(if (w > 0) "That code isn't right. Try again in ${(w + 59_999) / 60_000} min." else "That code isn't right.")
                return@background
            }
            Locks.right(this, p.id)
            Profiles.setCode(this, p, null)
            createRecovery(p, "Your old code no longer works. Save this new one.") {
                push(PasscodeScreen(this, p, PasscodeScreen.Mode.SET) { code ->
                    pop()
                    Profiles.setCode(this, p, code)
                    home.rebuild()
                    toast("New passcode set")
                    finished()
                })
            }
        }
    }

    /** Opens [pkg], asking for the passcode first when it is locked now. */
    fun launch(pkg: String) {
        if (!AppLocks.needsCode(cfg, pkg)) { launchNow(pkg); return }
        val owner = AppLocks.owner() ?: run { launchNow(pkg); return }
        push(PasscodeScreen(this, owner, PasscodeScreen.Mode.CHECK) { pop(); AppLocks.unlocked(pkg); launchNow(pkg) })
    }

    private fun launchNow(pkg: String) {
        if (Demo.isSample(pkg)) { toast("Sample app"); return }
        val i = Apps.launchIntent(packageManager, pkg)
        if (i == null) { if (pkg == Config.STREMIO) installStremio() else toast("Not installed"); return }
        try { startActivity(i); noteRecent(pkg) } catch (e: Exception) { toast("Cannot open this app") }
    }

    /** Moves [pkg] to the front of this profile's recent apps. Home redraws when it is next shown. */
    private fun noteRecent(pkg: String) {
        if (pkg == packageName) return
        cfg.recents.remove(pkg)
        cfg.recents.add(0, pkg)
        cfg.recentsRemoved.remove(pkg)
        while (cfg.recents.size > Config.MAX_RECENTS) cfg.recents.removeAt(cfg.recents.size - 1)
        save()
    }

    /** Recent apps to show: Android's usage history when allowed, else apps opened from Scene. Installed and not hidden. */
    fun recentApps(): List<String> {
        val used = Apps.recentlyUsed(this)
        val source = if (used.isNotEmpty()) used.filter { (pkg, at) -> at > (cfg.recentsRemoved[pkg] ?: 0L) }.map { it.first } else cfg.recents
        return source.filter { it != packageName && it !in cfg.hidden && it != Config.STREMIO_BOOT && (apps.isEmpty() || app(it) != null) }
            .take(Config.MAX_RECENTS)
    }

    /** Opens Android's usage access page so Scene can see apps opened outside it. */
    fun openUsageAccess() {
        val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        if (!start(i, "")) openSystemSettings(Settings.ACTION_SETTINGS)
    }

    fun setHomeRows(pinned: Boolean, recent: Boolean) {
        cfg.showPinned = pinned
        cfg.showRecent = recent
        save()
        home.rebuild()
    }

    fun clearRecents() {
        val now = System.currentTimeMillis()
        for (p in recentApps()) cfg.recentsRemoved[p] = now
        cfg.recents.clear()
        save()
        home.rebuild()
    }

    fun start(i: Intent, failure: String = "Not available on this device"): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) {
        if (failure.isNotEmpty()) toast(failure)
        false
    }

    fun openDetail(type: String, id: String, videoId: String? = null) {
        if (!Apps.isInstalled(packageManager, Config.STREMIO)) { installStremio(); return }
        if (start(Stremio.detailIntent(type, id, videoId), "Stremio is not installed")) noteRecent(Config.STREMIO)
    }

    /** Plays the title's trailer in Stremio. */
    fun openTrailer(ytId: String, title: String) {
        if (!Apps.isInstalled(packageManager, Config.STREMIO)) { installStremio(); return }
        if (start(Stremio.trailerIntent(ytId, title), "Stremio can't play the trailer")) noteRecent(Config.STREMIO)
    }

    /** Opens Stremio's Play Store page so it can be installed. */
    fun installStremio() {
        toast("Stremio isn't installed. Opening the Play Store.")
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Config.STREMIO}"))
        if (!start(market, "")) start(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${Config.STREMIO}")), "The Play Store isn't available")
    }

    /**
     * Opens a system settings page. Tries the Android TV Settings package first.
     */
    fun openSystemSettings(action: String = Settings.ACTION_SETTINGS) {
        // Clears the old Settings task.
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        for (pkg in arrayOf(TV_SETTINGS, null)) {
            try {
                startActivity(Intent(action).addFlags(flags).apply { if (pkg != null) setPackage(pkg) })
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
        start(Intent(Settings.ACTION_SETTINGS))
    }

    /** A dedicated Sound page when the box has one, otherwise Display & Sound. */
    fun openSoundSettings() {
        val i = Intent(Settings.ACTION_SOUND_SETTINGS)
        val own = packageManager.queryIntentActivities(i, 0).firstOrNull { it.activityInfo.packageName != TV_SETTINGS }
        if (own != null) {
            try {
                startActivity(i.setClassName(own.activityInfo.packageName, own.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // falls back below
            }
        }
        openSystemSettings(Settings.ACTION_SOUND_SETTINGS)
    }

    /** Display settings. Android TV keeps them on its Display & Sound page. */
    fun openDisplaySettings() {
        val i = Intent("android.settings.DISPLAY_SETTINGS")
        if (i.resolveActivity(packageManager) != null) openSystemSettings(i.action!!)
        else openSystemSettings(Settings.ACTION_SOUND_SETTINGS)
    }

    fun appInfo(pkg: String) = start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    fun openSearch(voice: Boolean) = push(SearchScreen(this, voice))
    fun openSettings(focus: String? = null) = push(SettingsScreen(this, focus))
    fun openSignIn() = push(SignInScreen(this))

    fun withMicPermission(done: (Boolean) -> Unit) = withPermission(Manifest.permission.RECORD_AUDIO, done)

    fun withPermission(perm: String, done: (Boolean) -> Unit) {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            done(true); return
        }
        permissionCallback = done
        requestPermissions(arrayOf(perm), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        permissionCallback?.invoke(ok)
        permissionCallback = null
    }

    @Deprecated("Activity result for the speech fallback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HOME_ROLE_REQUEST) {
            (overlays.lastOrNull()?.first as? SetupScreen)?.homeRoleResult()
            return
        }
        if (requestCode == SearchScreen.SPEECH_REQUEST) {
            val text = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            (overlays.lastOrNull()?.first as? SearchScreen)?.voiceResult(text)
        }
    }

    // ---------------------------------------------------------------- pin management

    fun isPinned(pkg: String) = cfg.pinned.any { it.pkg == pkg }

    fun pin(pkg: String, label: String) {
        if (isPinned(pkg)) return
        if (cfg.pinned.size >= Config.MAX_PINS) { toast("Pinned apps are full"); return }
        cfg.pinned.add(Pin(pkg, label))
        save()
        home.rebuild("pin:$pkg")
    }

    fun unpin(pkg: String) {
        cfg.pinned.removeAll { it.pkg == pkg }
        save()
        home.rebuild()
    }

    fun hide(pkg: String) {
        cfg.hidden.add(pkg)
        cfg.pinned.removeAll { it.pkg == pkg }
        cfg.recents.remove(pkg)
        save()
        home.rebuild()
    }

    fun unhide(pkg: String) {
        cfg.hidden.remove(pkg)
        save()
        home.rebuild()
    }

    /** All apps in the chosen order, without hidden apps. */
    fun sortedApps(): List<AppEntry> {
        val list = apps.filter { it.pkg !in cfg.hidden }
        return when (cfg.appSort) {
            1 -> list.sortedByDescending { it.installed }
            2 -> {
                val rank = HashMap<String, Int>()
                cfg.appOrder.forEachIndexed { i, p -> rank[p] = i }
                list.sortedWith(compareBy<AppEntry> { rank[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
            }
            else -> list
        }
    }

    fun setAppSort(mode: Int) {
        if (mode == 2 && cfg.appSort != 2) cfg.appOrder = ArrayList(sortedApps().map { it.pkg })
        cfg.appSort = mode
        save()
        home.rebuild("sort")
    }

    /** Picker for the All apps order. */
    fun sortMenu() {
        val names = arrayOf("A to Z", "Recently installed", "Custom")
        val subs = arrayOf("By app name", "Newest apps first", "Your own order. Hold OK on an app and choose Move.")
        val icons = intArrayOf(R.drawable.ic_sort, R.drawable.ic_history, R.drawable.ic_drag_indicator)
        val a = names.indices.map { i ->
            Sheet.Action(names[i] + if (cfg.appSort == i) "  ·  Current" else "", icons[i], subs[i]) { setAppSort(i) }
        }
        push(Sheet(this, "Sort All apps", "Pinned apps keep their own order", null, a))
    }

    /** Closes overlays and picks up the first app, ready to move. */
    fun arrange() {
        while (overlays.isNotEmpty()) pop()
        home.post { home.arrange() }
    }

    /** Long-press menu for an app tile. [fromGrid] is true for All apps tiles. */
    fun appMenu(pkg: String, label: String, fromGrid: Boolean = false, fromRecent: Boolean = false, row: Int = -1) {
        val info = Apps.info(packageManager, pkg)
        val a = ArrayList<Sheet.Action>()
        a.add(Sheet.Action("Open", R.drawable.ic_open_in_new, "Start $label") { launch(pkg) })
        a.add(Sheet.Action("Rename", R.drawable.ic_edit, "Only for ${Profiles.current.name}") {
            push(NameEntry(this, "Rename $label", label, help = "Type a name, then press Done. Up to ${Config.MAX_NAME} characters.",
                icon = R.drawable.ic_edit, maxLen = Config.MAX_NAME) { name ->
                if (rename(pkg, name)) toast("Renamed to ${cfg.names[pkg] ?: name.trim()}")
            })
        })
        if (pkg in cfg.names) a.add(Sheet.Action("Reset name", R.drawable.ic_refresh, "Back to ${systemLabel(pkg) ?: "the app's own name"}") {
            rename(pkg, null)
        })
        if (fromGrid) {
            a.add(Sheet.Action("Move", R.drawable.ic_drag_indicator, "Pick it up, move with the arrows, press OK to drop") { home.startMove(true, pkg) })
            a.add(Sheet.Action("Sort All apps", R.drawable.ic_sort_by_alpha, arrayOf("A to Z", "Recently installed", "Custom")[cfg.appSort]) { sortMenu() })
        }
        if (isPinned(pkg)) {
            if (!fromGrid && !fromRecent && row < 0 && cfg.pinned.size > 1) {
                a.add(Sheet.Action("Move", R.drawable.ic_drag_indicator, "Pick it up, move with the arrows, press OK to drop") { home.startMove(false, pkg) })
            }
            a.add(Sheet.Action("Unpin", R.drawable.ic_close, "Remove from Pinned apps. It stays in All apps.") { unpin(pkg) })
        } else {
            a.add(Sheet.Action("Pin to home", R.drawable.ic_push_pin, "Add to the end of Pinned apps") { pin(pkg, label) })
        }
        a.addAll(appControlActions(pkg, label, row))
        if (fromRecent) a.add(Sheet.Action("Remove from recents", R.drawable.ic_history, "It shows again after you next open it") {
            cfg.recents.remove(pkg); cfg.recentsRemoved[pkg] = System.currentTimeMillis(); save(); home.rebuild()
        })
        a.add(Sheet.Action("Hide from Home", R.drawable.ic_visibility_off, "Leaves Home, All apps and search. Undo in Settings.") { hide(pkg) })
        if (Apps.isInstalled(packageManager, "com.android.vending")) {
            a.add(Sheet.Action("View in Play Store", R.drawable.ic_shop, "Updates, reviews and details") {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage("com.android.vending"), "Play Store is not available")
            })
        }
        a.add(Sheet.Action("App info", R.drawable.ic_info, "Storage, cache, permissions and force stop") { appInfo(pkg) })
        if (info != null && !info.system) {
            a.add(Sheet.Action("Uninstall", R.drawable.ic_delete, "Android asks you to confirm first") {
                start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")), "Can't uninstall this app")
            })
        }
        val sub = buildList {
            // Long version names keep only their number part.
            info?.version?.takeIf { it.isNotEmpty() }?.let { v -> add("v" + v.split(' ', '-', '_').first().take(14)) }
            if (info != null && info.updated > 0) add(java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(info.updated)))
            if (info?.system == true) add("System")
        }.joinToString("  ·  ")
        push(Sheet(this, label, sub, app(pkg)?.icon, a))
    }

    /** Options for what is playing: open the player, control it, or show the music screensaver. */
    fun nowPlayingMenu() {
        val s = NowPlaying.song ?: return
        val playing = NowPlaying.state == 1
        val a = listOf(
            Sheet.Action("Open ${s.app}", R.drawable.ic_open_in_new, "Back to the player") { NowPlaying.open(this) },
            Sheet.Action(if (playing) "Pause" else "Play", if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow) { NowPlaying.playPause() },
            Sheet.Action("Previous", R.drawable.ic_skip_previous) { NowPlaying.previous() },
            Sheet.Action("Next", R.drawable.ic_skip_next) { NowPlaying.next() },
            Sheet.Action("Music Screensaver", R.drawable.ic_lyrics, "Album art, lyrics and a visualizer") { push(MusicSaverScreen(this)) },
        )
        push(Sheet(this, s.title, listOf(s.artist, s.app).filter { it.isNotEmpty() }.joinToString("  ·  "), s.art, a))
    }

    /** Long-press menu for a Continue watching card. */
    fun continueMenu(item: CwItem) {
        val meta = Stremio.cachedMeta(item.id)
        val a = ArrayList<Sheet.Action>()
        val where = if (item.season != null) "S${item.season} E${item.episode}" else "the film"
        val series = item.type == "series"
        if (!Apps.isInstalled(packageManager, Config.STREMIO)) {
            a.add(Sheet.Action("Get Stremio", R.drawable.ic_shop, "Install Stremio from the Play Store to watch") { installStremio() })
        } else if (item.started) {
            a.add(Sheet.Action("Resume", R.drawable.ic_play_arrow, "Carry on with $where in Stremio") { openDetail(item.type, item.id, item.videoId) })
        } else {
            a.add(Sheet.Action(if (item.isNew) "Play new episode" else "Play", R.drawable.ic_play_arrow, "Start $where in Stremio") { openDetail(item.type, item.id, item.videoId) })
        }
        val next = meta?.let { Stremio.nextVideo(it, item) }
        if (next != null && item.started && Apps.isInstalled(packageManager, Config.STREMIO)) a.add(Sheet.Action("Next episode", R.drawable.ic_skip_next, "S${next.season} E${next.episode}" + (next.title.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")) { openDetail(item.type, item.id, next.id) })
        if (Stremio.signedIn(this)) {
            a.add(Sheet.Action(if (series) "Mark episode watched" else "Mark as watched", R.drawable.ic_check_circle,
                if (series) "Ticks $where in Stremio and moves on to the next episode" else "Counts as watched in Stremio and leaves Continue watching") { markWatched(item) })
            if (item.inLibrary) {
                a.add(Sheet.Action("Remove from library", R.drawable.ic_bookmark_added, "Removes it from your Stremio library and Continue watching") { setInLibrary(item, false) })
            } else {
                a.add(Sheet.Action("Add to library", R.drawable.ic_bookmark_add, "Keeps it in your Stremio library" + if (series) " and tells you about new episodes" else "") { setInLibrary(item, true) })
            }
            // The Cinema spotlight has no alerts button, so the choice lives here.
            if (Theme.CINEMA && series && item.inLibrary) {
                if (item.notify) a.add(Sheet.Action("Stop new episode alerts", R.drawable.ic_notifications_active, "No more alerts for new episodes") { setNotify(item, false) })
                else a.add(Sheet.Action("Get new episode alerts", R.drawable.ic_notifications_off, "Tells you when a new episode is out") { setNotify(item, true) })
            }
        }
        if (series) a.add(Sheet.Action("Episode list", R.drawable.ic_list, "All seasons and episodes in Stremio") { openDetail(item.type, item.id) })
        else a.add(Sheet.Action("Details", R.drawable.ic_info, "Cast, streams and more in Stremio") { openDetail(item.type, item.id) })
        meta?.trailer?.let { yt -> a.add(Sheet.Action("Trailer", R.drawable.ic_movie, "Plays in Stremio") { openTrailer(yt, item.name) }) }
        a.add(Sheet.Action("Hide from Continue watching", R.drawable.ic_visibility_off, "Only on this TV, until you watch more of it") { hideContinue(item) })
        a.add(Sheet.Action("Refresh library", R.drawable.ic_sync, "Fetch the latest progress from Stremio") { refreshContinue(force = true); toast("Refreshing your library") })
        val sub = buildList {
            add(if (series) "Series" else "Film")
            meta?.year?.takeIf { it.isNotEmpty() }?.let { add(it) }
            if (item.isNew) add(if (item.newCount == 1) "1 new episode" else "${item.newCount} new episodes")
            if (item.started) add("${Math.round(item.progress * 100)}% watched")
        }.joinToString("  ·  ")
        push(Sheet(this, item.name, sub, null, a, posterUrl = item.poster ?: ""))
    }
}
