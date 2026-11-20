package com.robbydock.scene

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.ImageView
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
    private val dots = Dots(act)

    init {
        show(Step.WELCOME)
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
        removeAllViews()
        dots.index = s.ordinal
        place(dots, 0f, 980f, 1920f, 24f)
        when (s) {
            Step.WELCOME -> page(
                R.mipmap.ic_launcher, "Your entertainment. One place.",
                "A quiet home screen for your TV. No ads and no recommendations. A few quick choices and you're done.",
                listOf(Choice("Get started", R.drawable.ic_play_arrow, true) { show(if (Profiles.needsFirstProfile) Step.PROFILE else afterProfile()) }),
                icon = true,
            )
            Step.PROFILE -> page(
                R.drawable.ic_account_circle, "Who's watching?",
                "Create your profile: a name, a picture and an accent colour. You'll be its admin, and you can add more people later.",
                listOf(Choice("Create your profile", R.drawable.ic_add, true) {
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
                R.drawable.ic_home_app_logo, "Make Scene your home screen",
                "The Home button on your remote then brings you here. You can switch back any time in System settings.",
                listOf(
                    Choice("Use Scene as home screen", R.drawable.ic_check, true) { askDefaultHome() },
                    Choice("Not now", R.drawable.ic_close) { show(Step.WEATHER) },
                ),
            )
            Step.WEATHER -> page(
                R.drawable.ic_partly_cloudy_day, "Local weather",
                "Weather sits next to the clock. Allow approximate location, or pick a city yourself.",
                listOf(
                    Choice("Use this TV's location", R.drawable.ic_my_location, true) {
                        act.cfg.weatherOn = true
                        act.cfg.locAuto = true
                        act.save()
                        act.withPermission(Manifest.permission.ACCESS_COARSE_LOCATION) { _ ->
                            act.refreshWeather(force = true)
                            show(afterWeather())
                        }
                    },
                    Choice("Choose a city", R.drawable.ic_search) {
                        act.push(LocationPicker(act) {
                            act.cfg.weatherOn = true
                            act.cfg.locAuto = false
                            act.save()
                            act.refreshWeather(force = true)
                            show(afterWeather())
                        })
                    },
                    Choice("No weather", R.drawable.ic_visibility_off) {
                        act.cfg.weatherOn = false
                        act.save()
                        act.home.weatherChanged()
                        show(afterWeather())
                    },
                ),
            )
            Step.VOICE -> page(
                R.drawable.ic_mic, "Voice search",
                "Allow the microphone so you can say a title, an app or a setting. It listens only while the mic button is on.",
                listOf(
                    Choice("Allow microphone", R.drawable.ic_mic, true) {
                        act.withMicPermission { show(Step.SCREENSAVER) }
                    },
                    Choice("Not now", R.drawable.ic_close) { show(Step.SCREENSAVER) },
                ),
            )
            Step.SCREENSAVER -> page(
                R.drawable.ic_gradient, "Screensaver",
                "When the TV is idle, Scene can show slow, drifting colour or a quiet clock.",
                listOf(
                    Choice("Ambient colour", R.drawable.ic_gradient, true) { dream(ClockDream.AMBIENT) },
                    Choice("Clock", R.drawable.ic_schedule) { dream(ClockDream.CLOCK) },
                    Choice("No screensaver", R.drawable.ic_close) { ClockDream.setOn(act, false); show(Step.STREMIO) },
                ),
            )
            Step.STREMIO -> page(
                R.drawable.ic_stremio, "Stremio on Home",
                "Sign in to see Continue watching with one-press resume. Or keep Home minimal with just your apps.",
                brand = true,
                choices = listOf(
                    if (Stremio.signedIn(act)) Choice("Continue", R.drawable.ic_check, true) { useSpotlight(true) }
                    else Choice("Sign in with your phone", R.drawable.ic_qr_code_2, true) {
                        useSpotlight(true, advance = false)
                        act.openSignIn()
                    },
                    Choice("Minimalist view", R.drawable.ic_apps) { useSpotlight(false) },
                ),
            )
            Step.DONE -> page(
                R.drawable.ic_check, "You're all set",
                if (ClockDream.isOn(act)) "To use Scene's screensaver, pick Scene in the TV's screensaver settings. You can change anything later in Settings."
                else "You can change any of this later in Settings.",
                listOfNotNull(
                    Choice("Go to Home", R.drawable.ic_home, true) { finish() },
                    if (ClockDream.isOn(act)) Choice("Screensaver settings", R.drawable.ic_open_in_new) {
                        act.openSystemSettings(android.provider.Settings.ACTION_DREAM_SETTINGS)
                    } else null,
                ),
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

    private class Choice(val label: String, val icon: Int, val primary: Boolean = false, val run: () -> Unit)

    /** One centred page: an icon, a title, a line of text and a column of buttons. */
    private fun page(iconRes: Int, title: String, body: String, choices: List<Choice>, icon: Boolean = false, brand: Boolean = false) {
        if (brand) {
            // A brand mark keeps its own colours on a quiet surface circle.
            val badge = Panel(act, 72f, { Theme.elev }, false)
            place(badge, (1920f - 144f) / 2, 170f, 144f, 144f)
            badge.place(ImageView(act).apply { setImageResource(iconRes) }, 24f, 24f, 96f, 96f)
        } else if (icon) {
            // App icon fills its own circle, no badge behind it.
            val img = ImageView(act).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
                }
                clipToOutline = true
            }
            place(img, (1920f - 144f) / 2, 170f, 144f, 144f)
        } else {
            place(IconBadge(act, iconRes), (1920f - 160f) / 2, 162f, 160f, 160f)
        }
        place(act.label(title, 64f, Theme.TEXT, Theme.BOLD).apply { gravity = Gravity.CENTER_HORIZONTAL }, 160f, 350f, 1600f, 84f)
        place(act.label(body, 30f, Theme.TEXT_DIM, Theme.REGULAR, 2).apply { gravity = Gravity.CENTER_HORIZONTAL }, 360f, 452f, 1200f, 90f)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        place(col, 0f, 590f, 1920f, 380f)
        var first: View? = null
        for (c in choices) {
            val b = PillButton(act, c.label, c.icon, c.primary)
            b.setOnClickListener { c.run() }
            col.addView(b, LinearLayout.LayoutParams(-2, Theme.ipx(84f)).apply { bottomMargin = Theme.ipx(22f) })
            if (first == null) first = b
        }
        post { first?.requestFocus() }
    }

    /** Step indicator: one dot per step, the current one wider and in the accent. */
    private class Dots(act: HomeActivity) : View(act) {
        var index = 0
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val n = Step.values().size
            val d = Theme.px(14f)
            val wide = Theme.px(44f)
            val gap = Theme.px(14f)
            val total = (n - 1) * (d + gap) + wide
            var x = (width - total) / 2
            for (i in 0 until n) {
                val w = if (i == index) wide else d
                p.color = if (i == index) Theme.accent else Theme.withAlpha(Theme.TEXT, 0.25f)
                c.drawRoundRect(x, (height - d) / 2, x + w, (height + d) / 2, d / 2, d / 2, p)
                x += w + gap
            }
        }
    }
}
