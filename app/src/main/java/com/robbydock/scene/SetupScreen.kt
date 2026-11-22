package com.robbydock.scene

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * First-run setup: a short, skippable walk through weather location, Stremio sign-in or a
 * minimal layout. Every step can be changed later in Settings.
 */
@SuppressLint("ViewConstructor")
class SetupScreen(act: HomeActivity) : OverlayBase(act, 1f), BackAware, HomeActivity.Returnable {

    private enum class Step { WELCOME, PROFILE, HOME, WEATHER, VOICE, SCREENSAVER, STREMIO, DONE }

    /** The step after the profile; the home step is skipped once Scene is the home app. */
    private fun afterProfile() = if (isDefaultHome()) Step.WEATHER else Step.HOME

    private fun micGranted() = act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The step after weather; voice is skipped once the microphone is allowed. */
    private fun afterWeather() = if (micGranted()) Step.SCREENSAVER else Step.VOICE

    private var step = Step.WELCOME

    init {
        show(resumeStep())
    }

    /** The step saved before a restart, moved past any step that is already done. */
    private fun resumeStep(): Step {
        var s = Step.values().getOrElse(act.statePrefs().getInt("setupStep", 0)) { Step.WELCOME }
        if (s == Step.PROFILE && !Profiles.needsFirstProfile) s = afterProfile()
        if (s == Step.HOME && isDefaultHome()) s = Step.WEATHER
        if (s == Step.VOICE && micGranted()) s = Step.SCREENSAVER
        if (s == Step.STREMIO && Stremio.signedIn(act)) s = Step.DONE
        return s
    }

    override fun onBack(): Boolean {
        // Back steps back through setup; on the first page it closes setup.
        var prev = Step.values().getOrNull(step.ordinal - 1) ?: return false.also { finish() }
        if (prev == Step.HOME && isDefaultHome()) prev = Step.PROFILE
        if (prev == Step.PROFILE && !Profiles.needsFirstProfile) prev = Step.WELCOME
        if (prev == Step.VOICE && micGranted()) prev = Step.WEATHER
        show(prev)
        return true
    }

    private fun isDefaultHome(): Boolean {
        // Checks the home role for Scene.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            act.getSystemService(android.app.role.RoleManager::class.java)?.let { return it.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) }
        }
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        return act.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName == act.packageName
    }

    /** Android shows its own home app picker; setup moves on whatever is chosen. */
    private fun askDefaultHome() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 29) {
            act.getSystemService(android.app.role.RoleManager::class.java)
                ?.takeIf { it.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) }
                ?.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
        } else null
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(intent ?: android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS), HomeActivity.HOME_ROLE_REQUEST)
        } catch (e: Exception) {
            act.toast("Choose Scene under System settings, Apps, Default apps")
            show(Step.WEATHER)
        }
    }

    fun homeRoleResult() {
        if (step == Step.HOME) show(Step.WEATHER)
    }

    override fun onReturn() {
        // Back from the sign-in screen: move on once signed in.
        if (step == Step.STREMIO && Stremio.signedIn(act)) show(Step.DONE)
    }

    private fun finish() {
        act.setupDone()
    }

    private fun show(s: Step) {
        step = s
        act.statePrefs().edit().putInt("setupStep", s.ordinal).apply()
        removeAllViews()
        when (s) {
            Step.WELCOME -> page(
                OnboardArt.Kind.WELCOME, "Your entertainment. One place.",
                "A quiet home screen for your TV. No ads and no recommendations. A few quick choices and you're done.",
                listOf(Choice("Get started", "About a minute", R.drawable.ic_play_arrow) { show(if (Profiles.needsFirstProfile) Step.PROFILE else afterProfile()) }),
            )
            Step.PROFILE -> page(
                OnboardArt.Kind.PROFILE, "Who's watching?",
                "Create your profile with a name, a picture and an accent colour. You'll be its admin, and you can add more people later.",
                listOf(Choice("Create your profile", "Name, picture and colour", R.drawable.ic_add) {
                    act.push(ProfileWizard(act, first = true) { name, emoji, color ->
                        Profiles.setUpFirst(act, name, emoji, color)
                        // The first profile's accent starts as its colour.
                        act.cfg.accent = color
                        act.themeChanged()
                        show(afterProfile())
                    })
                }),
            )
            Step.HOME -> page(
                OnboardArt.Kind.HOME, "Make Scene your home screen",
                "The Home button on your remote then brings you here. You can switch back any time in System settings.",
                listOf(
                    Choice("Use Scene as home screen", "Recommended", R.drawable.ic_check) { askDefaultHome() },
                    Choice("Not now", "Keep the TV's launcher", R.drawable.ic_close) { show(Step.WEATHER) },
                ),
            )
            Step.WEATHER -> page(
                OnboardArt.Kind.WEATHER, "Local weather",
                "Weather sits next to the clock on Home. Allow approximate location, or pick a city yourself.",
                listOf(
                    Choice("Use this TV's location", "Approximate only", R.drawable.ic_my_location) {
                        act.cfg.weatherOn = true
                        act.cfg.locAuto = true
                        act.save()
                        act.withPermission(Manifest.permission.ACCESS_COARSE_LOCATION) { _ ->
                            act.refreshWeather(force = true)
                            show(afterWeather())
                        }
                    },
                    Choice("Choose a city", "Search by name", R.drawable.ic_search) {
                        act.push(LocationPicker(act) {
                            act.cfg.weatherOn = true
                            act.cfg.locAuto = false
                            act.save()
                            act.refreshWeather(force = true)
                            show(afterWeather())
                        })
                    },
                    Choice("No weather", "Clock and date only", R.drawable.ic_visibility_off) {
                        act.cfg.weatherOn = false
                        act.save()
                        act.home.weatherChanged()
                        show(afterWeather())
                    },
                ),
            )
            Step.VOICE -> page(
                OnboardArt.Kind.VOICE, "Voice search",
                "Say a title, an app or a setting. Scene listens only while the mic button is on.",
                listOf(
                    Choice("Allow microphone", "Android asks once", R.drawable.ic_mic) {
                        act.withMicPermission { show(Step.SCREENSAVER) }
                    },
                    Choice("Not now", "Type to search instead", R.drawable.ic_close) { show(Step.SCREENSAVER) },
                ),
            )
            Step.SCREENSAVER -> page(
                OnboardArt.Kind.SCREENSAVER, "Screensaver",
                "When the TV sits idle, Scene can show slow, drifting colour or a quiet clock.",
                listOf(
                    Choice("Ambient colour", "Soft colour that drifts", R.drawable.ic_gradient) { dream(ClockDream.AMBIENT) },
                    Choice("Clock", "Large time and date", R.drawable.ic_schedule) { dream(ClockDream.CLOCK) },
                    Choice("No screensaver", "Leave it to the TV", R.drawable.ic_close) { ClockDream.setOn(act, false); show(Step.STREMIO) },
                ),
            )
            Step.STREMIO -> page(
                OnboardArt.Kind.STREMIO, "Stremio on Home",
                "Sign in to see Continue watching with one-press resume, or keep Home minimal with just your apps.",
                listOf(
                    if (Stremio.signedIn(act)) Choice("Show Continue watching", "Signed in", R.drawable.ic_check) { useSpotlight(true) }
                    else Choice("Sign in with your phone", "Scan a code, no typing", R.drawable.ic_qr_code_2) {
                        useSpotlight(true, advance = false)
                        act.openSignIn()
                    },
                    Choice("Minimal Home", "Apps only", R.drawable.ic_apps) { useSpotlight(false) },
                ),
            )
            Step.DONE -> page(
                OnboardArt.Kind.DONE, "You're all set",
                if (ClockDream.isOn(act) && !ClockDream.isSelected(act)) "Scene's screensaver needs a one-time permission. Settings, Screensaver shows how. You can change anything later in Settings."
                else "Everything here can be changed later in Settings.",
                listOf(Choice("Go to Home", "", R.drawable.ic_home) { finish() }),
            )
        }
    }

    private fun dream(mode: Int) {
        ClockDream.setOn(act, true)
        ClockDream.setMode(act, mode)
        show(Step.STREMIO)
    }

    private fun useSpotlight(on: Boolean, advance: Boolean = true) {
        if (act.cfg.spotlight != on) {
            act.cfg.spotlight = on
            act.save()
            act.home.rebuild()
        }
        if (advance) show(Step.DONE)
    }

    private class Choice(val label: String, val sub: String, val icon: Int, val run: () -> Unit)

    /**
     * One split page: step count, title, text and a card of choices on the left, a picture of
     * the focused choice on the right, progress along the foot.
     */
    /** Offsets that keep the 16:9 page centred on wider or taller screens. */
    private val OX get() = (Theme.W - 1920f) / 2
    private val OY get() = (Theme.H - 1080f) / 2

    private fun page(kind: OnboardArt.Kind, title: String, body: String, choices: List<Choice>) {
        place(Glow(act), 0f, 0f, Theme.W, Theme.H)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, Theme.ipx(30f))
        }
        place(col, OX + 140f, 0f, 760f, Theme.H)
        if (step != Step.DONE) {
            col.addView(act.label("Step ${step.ordinal + 1} of ${Step.values().size - 1}", 24f, Theme.accent, Theme.SEMIBOLD),
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Theme.ipx(18f) })
        }
        col.addView(act.label(title, 76f, Theme.TEXT, Theme.BOLD, 2).apply {
            letterSpacing = -1f / 76f
            lineHeight(this, 86f)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Theme.ipx(24f) })
        col.addView(act.label(body, 28f, Theme.TEXT_DIM, Theme.REGULAR, 4).apply {
            lineHeight(this, 40f)
        }, LinearLayout.LayoutParams(Theme.ipx(680f), -2).apply { bottomMargin = Theme.ipx(48f) })
        val card = object : LinearLayout(act) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) = drawCard(c, p, width, height, Theme.surface)
        }.apply {
            orientation = LinearLayout.VERTICAL
            setWillNotDraw(false)
            clipChildren = false
        }
        col.addView(card, LinearLayout.LayoutParams(Theme.ipx(700f), -2))

        val art = OnboardArt(act).apply {
            face = if (Profiles.needsFirstProfile) "" else Profiles.current.let { pr -> pr.emoji.ifEmpty { pr.name.take(1).uppercase() } }
            fahrenheit = act.cfg.fahrenheit
            spotlight = act.cfg.spotlight
        }
        place(art, OX + 980f, OY + 120f, 800f, 820f)
        art.show(kind)

        var first: View? = null
        choices.forEachIndexed { i, ch ->
            val r = SettingRow(act, ch.icon, ch.label).apply {
                subtitle = ch.sub
                trail = SettingRow.Trail.NONE
                divider = i > 0
                lean = true
                onFocused = { art.choice = i }
                setOnClickListener { ch.run() }
            }
            card.addView(r, LinearLayout.LayoutParams(-1, Theme.ipx(104f)))
            if (first == null) first = r
        }
        if (step != Step.DONE) place(Progress(step.ordinal, Step.values().size - 1), OX + 140f, OY + 968f, 800f, 30f)

        // Words rise in one after another.
        if (Theme.MEDIUM_MS > 0) {
            for (i in 0 until col.childCount) {
                val v = col.getChildAt(i)
                v.alpha = 0f
                if (Theme.moves) v.translationY = Theme.px(16f)
                v.animate().alpha(1f).translationY(0f).setStartDelay(i * 40L)
                    .setDuration(Theme.MEDIUM_MS + 160).setInterpolator(Theme.EASE).start()
            }
        }
        post { first?.requestFocus() }
    }

    private fun lineHeight(t: android.widget.TextView, h: Float) {
        if (android.os.Build.VERSION.SDK_INT >= 28) t.lineHeight = Theme.ipx(h)
        else t.setLineSpacing(0f, 1f)
    }

    /** Soft accent light behind the page, darker at the edges. */
    private class Glow(act: HomeActivity) : View(act) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(Theme.bg)
            spot(c, w * 0.72f, h * 0.46f, Theme.px(880f), Theme.withAlpha(Theme.accent, 0.16f))
            spot(c, w * 0.04f, h, Theme.px(800f), Theme.withAlpha(Theme.duo(Theme.accent), 0.10f))
            p.shader = android.graphics.RadialGradient(w / 2, h / 2, Theme.px(1150f),
                intArrayOf(0, 0, 0x66000000), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
        }

        private fun spot(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
            p.shader = android.graphics.RadialGradient(x, y, r, intArrayOf(color, color and 0x00FFFFFF), floatArrayOf(0f, 0.7f),
                android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
    }

    /** Progress: one segment per step, filled up to the current one, which is longer. */
    private inner class Progress(private val at: Int, private val count: Int) : View(act) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val h = Theme.px(6f)
            val top = (height - h) / 2
            var x = 0f
            for (i in 0 until count) {
                val w = Theme.px(if (i == at) 96f else 56f)
                if (i == at) {
                    p.shader = android.graphics.LinearGradient(x, 0f, x + w, 0f, Theme.accent, Theme.duo(Theme.accent), android.graphics.Shader.TileMode.CLAMP)
                } else {
                    p.shader = null
                    p.color = if (i < at) Theme.withAlpha(Theme.accent, 0.55f) else Theme.withAlpha(Theme.TEXT, 0.16f)
                }
                c.drawRoundRect(x, top, x + w, top + h, h / 2, h / 2, p)
                x += w + Theme.px(10f)
            }
            p.shader = null
        }
    }
}
