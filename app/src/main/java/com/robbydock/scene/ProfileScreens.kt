package com.robbydock.scene

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout

/** "Who's watching?" picker. Locked profiles ask for their D-pad passcode first. */
@SuppressLint("ViewConstructor")
class ProfilePicker(act: HomeActivity) : OverlayBase(act, 0.78f, haze = true) {

    init {
        // One row up to five tiles, then two centred rows with smaller avatars. The Add tile counts.
        val canAdd = Profiles.list.size < Profiles.MAX
        val rows = Guard.pickerRows(Profiles.list.size + if (canAdd) 1 else 0)
        val two = rows.size > 1
        val size = if (two) 160f else 200f
        val pitch = if (two) 236f else 280f
        val rowH = if (two) 280f else 0f
        val top = if (two) 300f else 440f
        place(act.label("Who's watching?", 64f, Theme.TEXT, Theme.BOLD).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, top - 220f, Theme.W, 84f)
        var focus: View? = null
        val pops = ArrayList<View>()
        // Position of tile [i]: its row and its place in that row, centred.
        fun slot(i: Int): Pair<Float, Float> {
            val r = if (i < rows[0]) 0 else 1
            val k = if (r == 0) i else i - rows[0]
            val x0 = (Theme.W - (rows[r] - 1) * pitch - size) / 2
            return (x0 + k * pitch) to (top + r * rowH)
        }
        fun caption(text: String, color: Int, x: Float, y: Float) =
            place(act.label(text, if (two) 26f else 30f, color, Theme.MEDIUM).apply { gravity = Gravity.CENTER_HORIZONTAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                x - 40f, y + size + 22f, size + 80f, 40f)
        for ((i, p) in Profiles.list.withIndex()) {
            val (x, y) = slot(i)
            val av = AvatarView(act).apply {
                glow = true
                letter = p.initial
                emoji = p.emoji
                colorIndex = p.color
                locked = p.locked
                contentDescription = p.name
                setOnClickListener { pick(p) }
            }
            place(av, x, y, size, size)
            pops.add(av)
            caption(p.name, if (p.id == Profiles.currentId) Theme.TEXT else Theme.TEXT_DIM, x, y)
            if (p.id == Profiles.currentId) {
                place(act.label("Current", 22f, Theme.accent, Theme.SEMIBOLD).apply { gravity = Gravity.CENTER_HORIZONTAL }, x - 40f, y + size + 64f, size + 80f, 30f)
                focus = av
            }
        }
        if (canAdd) {
            val (x, y) = slot(Profiles.list.size)
            val add = AvatarView(act, addMode = true).apply {
                contentDescription = "Add profile"
                setOnClickListener {
                    act.newProfile()
                }
            }
            place(add, x, y, size, size)
            pops.add(add)
            caption("Add profile", Theme.TEXT_DIM, x, y)
        }
        val manage = PillButton(act, "Manage profiles", R.drawable.ic_settings, false).apply {
            setOnClickListener { act.pop(); act.openSettings("profile") }
        }
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        row.addView(manage, LinearLayout.LayoutParams(-2, Theme.ipx(80f)))
        place(row, 0f, if (two) 900f else 830f, Theme.W, 80f)
        pops.forEachIndexed { i, v -> Motion.pop(v, 80L + i * 50L) }
        post { focus?.requestFocus() }
    }

    private fun pick(p: Profile) {
        if (p.id == Profiles.currentId) { act.pop(); return }
        if (!p.locked) { act.pop(); act.switchProfile(p.id); return }
        act.push(PasscodeScreen(act, p, PasscodeScreen.Mode.CHECK) { _ ->
            act.popTo(this)
            act.pop()
            act.switchProfile(p.id)
        })
    }
}

/**
 * D-pad passcode: four presses of up, down, left or right. Checks a code, or sets a new one
 * by asking for it twice. Five wrong tries lock entry for 30 seconds.
 */
@SuppressLint("ViewConstructor")
class PasscodeScreen(
    act: HomeActivity, private val profile: Profile, private val mode: Mode, private val done: (String) -> Unit,
) : OverlayBase(act, 1f) {

    enum class Mode { CHECK, SET }

    companion object {
        /** Passed to [done] when the passcode was cleared with the recovery code instead of entered. */
        const val RESET = "reset"
    }

    private val entered = StringBuilder()
    private var first: String? = null
    private val prompt = act.label("", 30f, Theme.TEXT_DIM, Theme.MEDIUM).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val slots = Slots(act)
    private val ring = CountdownRing(act).apply { visibility = View.INVISIBLE }
    /** Refreshes the countdown text once a second while a wait runs. */
    private val tick = object : Runnable {
        override fun run() { setPrompt(); if (waitLeft() > 0) postDelayed(this, 1000L) else ring.visibility = View.INVISIBLE }
    }

    private fun waitLeft() = if (mode == Mode.CHECK) Locks.remaining(act, profile.id) else 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        val av = AvatarView(act).apply {
            glow = true
            letter = profile.initial
            emoji = profile.emoji
            colorIndex = profile.color
            isFocusable = false
        }
        place(av, (Theme.W - 144f) / 2, 200f, 144f, 144f)
        place(act.label(if (mode == Mode.SET) "Set a passcode for ${profile.name}" else profile.name, 56f, Theme.TEXT, Theme.BOLD).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, 0f, 380f, Theme.W, 76f)
        place(prompt, 0f, 470f, Theme.W, 40f)
        place(slots, (Theme.W - 560f) / 2, 560f, 560f, 120f)
        // The ring sits around the slots' centre while a wait runs.
        place(ring, (Theme.W - 160f) / 2, 540f, 160f, 160f)
        val help = if (mode == Mode.CHECK) "Use the arrows on your remote. Press OK if you forgot it. Back cancels."
            else "Use the arrows on your remote or controller. Back cancels."
        place(act.label(help, 24f, Theme.TEXT_DIM).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 760f, Theme.W, 34f)
        startWaitIfAny()
        post { requestFocus() }
    }

    private fun startWaitIfAny() {
        val left = waitLeft()
        if (left > 0) {
            slots.visibility = View.INVISIBLE
            ring.visibility = View.VISIBLE
            ring.start(Locks.currentWait(act, profile.id), left)
            removeCallbacks(tick); post(tick)
        } else {
            slots.visibility = View.VISIBLE
            ring.visibility = View.INVISIBLE
        }
        setPrompt()
    }

    private fun setPrompt() {
        val wait = waitLeft()
        if (wait <= 0 && slots.visibility != View.VISIBLE) { slots.visibility = View.VISIBLE; ring.stop() }
        prompt.text = when {
            wait > 0 -> "Too many tries. Try again in ${clock(wait)}."
            mode == Mode.SET && first != null -> "Enter it again to confirm"
            mode == Mode.SET -> "Choose ${Profiles.CODE_LEN} arrow presses"
            else -> "Enter passcode"
        }
        prompt.setTextColor(if (wait > 0) Theme.accent else Theme.TEXT_DIM)
    }

    private fun clock(ms: Long): String {
        val s = (ms + 999) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    /** "Forgot passcode?": an admin can use their recovery code; another admin can reset any passcode. */
    private fun forgot() {
        val helpers = Roles.othersWhoCanReset(Profiles.list, profile)
        act.push(when {
            profile.admin && profile.hasRecovery -> Dialog(act, 0, "Forgot passcode?",
                "Enter the 8-character recovery code saved when this passcode was set.", listOf(
                    Dialog.Button("Cancel"),
                    Dialog.Button("Use recovery code", primary = true) {
                        act.push(RecoveryEntry(act, profile) { act.popTo(this); done(RESET) })
                    },
                ), profile)
            helpers.isNotEmpty() -> Dialog(act, 0, "Forgot passcode?",
                (if (profile.admin) "Another admin" else "An admin") + " can reset it in Settings, Profiles.",
                listOf(Dialog.Button("OK", primary = true)), profile)
            else -> Dialog(act, 0, "Forgot passcode?",
                "This profile has no recovery code, so its passcode can't be reset. " +
                    "To start over, clear Scene's storage in the TV's Settings, Apps. That removes every profile.",
                listOf(Dialog.Button("OK", primary = true)), profile)
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 'U'
            KeyEvent.KEYCODE_DPAD_DOWN -> 'D'
            KeyEvent.KEYCODE_DPAD_LEFT -> 'L'
            KeyEvent.KEYCODE_DPAD_RIGHT -> 'R'
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> ' '
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        if (dir == ' ') { if (mode == Mode.CHECK) forgot(); return true }
        if (waitLeft() > 0) { setPrompt(); return true }
        entered.append(dir)
        slots.set(entered.toString(), false)
        if (entered.length == Profiles.CODE_LEN) postDelayed({ complete() }, 180)
        return true
    }

    private fun complete() {
        val code = entered.toString()
        entered.clear()
        when (mode) {
            Mode.CHECK -> if (Profiles.checkCode(profile, code)) {
                Locks.right(act, profile.id)
                done(code)
            } else {
                val wait = Locks.wrong(act, profile.id)
                wrong()
                if (wait > 0) startWaitIfAny()
            }
            Mode.SET -> when {
                first == null -> { first = code; slots.set("", false); setPrompt() }
                first == code -> done(code)
                else -> { first = null; wrong(); prompt.text = "Those didn't match. Start again." }
            }
        }
    }

    private fun wrong() {
        slots.set("", true)
        setPrompt()
        if (mode == Mode.CHECK && waitLeft() <= 0) prompt.text = "Wrong passcode. Try again."
        slots.animate().translationX(Theme.px(18f)).setDuration(60).withEndAction {
            slots.animate().translationX(-Theme.px(18f)).setDuration(80).withEndAction {
                slots.animate().translationX(0f).setDuration(60).start()
            }.start()
        }.start()
    }

    /** Four rounded slots; each entered press shows as its arrow. */
    private class Slots(act: HomeActivity) : View(act) {
        private var code = ""
        private var error = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrows = mapOf(
            'U' to act.icon(R.drawable.ic_expand_less, Theme.TEXT), 'D' to act.icon(R.drawable.ic_expand_more, Theme.TEXT),
            'L' to act.icon(R.drawable.ic_keyboard_arrow_left, Theme.TEXT), 'R' to act.icon(R.drawable.ic_keyboard_arrow_right, Theme.TEXT),
        )

        fun set(c: String, err: Boolean) { code = c; error = err; invalidate() }

        override fun onDraw(c: Canvas) {
            val n = Profiles.CODE_LEN
            val s = height.toFloat()
            val gap = (width - n * s) / (n - 1)
            for (i in 0 until n) {
                val x = i * (s + gap)
                fill.color = when {
                    error -> Theme.withAlpha(Theme.accent, 0.25f)
                    i == code.length -> Theme.float
                    else -> Theme.elev
                }
                c.drawRoundRect(x, 0f, x + s, s, Theme.px(Theme.R_CARD), Theme.px(Theme.R_CARD), fill)
                if (i == code.length && !error) {
                    fill.color = Theme.accent
                    c.drawRect(x + s * 0.3f, s * 0.8f, x + s * 0.7f, s * 0.8f + Theme.px(4f), fill)
                }
                val d = arrows[code.getOrNull(i)] ?: continue
                val a = (s * 0.55f).toInt()
                d.setBounds((x + (s - a) / 2).toInt(), ((s - a) / 2).toInt(), (x + (s + a) / 2).toInt(), ((s + a) / 2).toInt())
                d.draw(c)
            }
        }
    }
}

/** Grid of emoji pictures for a profile. The first choice keeps the initial. */
@SuppressLint("ViewConstructor")
class AvatarPicker(act: HomeActivity, private val profile: Profile, private val done: () -> Unit) : OverlayBase(act, 1f) {

    init {
        place(act.label("Choose a picture", 64f, Theme.TEXT, Theme.BOLD).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 96f, Theme.W, 84f)
        place(act.label(profile.name, 28f, Theme.TEXT_DIM, Theme.MEDIUM).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 186f, Theme.W, 40f)
        val choices = listOf("") + Profiles.EMOJI
        val cols = 12
        val size = 112f
        val pitch = 140f
        val x0 = (Theme.W - (cols - 1) * pitch - size) / 2
        val y0 = 300f
        var focus: View? = null
        val views = ArrayList<View>()
        for ((i, e) in choices.withIndex()) {
            val av = AvatarView(act).apply {
                letter = profile.initial
                emoji = e
                colorIndex = profile.color
                contentDescription = e.ifEmpty { "Initial" }
                setOnClickListener {
                    profile.emoji = e
                    act.pop()
                    done()
                }
            }
            place(av, x0 + (i % cols) * pitch, y0 + (i / cols) * pitch, size, size)
            views.add(av)
            if (e == profile.emoji) focus = av
        }
        views.forEachIndexed { i, v -> Motion.pop(v, (i % cols + i / cols) * 22L) }
        post { focus?.requestFocus() }
    }
}

/** One-line text entry with the on-screen keyboard: a profile name by default, or a code. */
@SuppressLint("ViewConstructor")
class NameEntry(
    act: HomeActivity, title: String, initial: String,
    help: String = "Type a name, then press Done on the keyboard.",
    hint: String = "Name", icon: Int = R.drawable.ic_account_circle, maxLen: Int = Limits.PROFILE_NAME,
    /** Keeps entry plain, with no suggestions or learning. */
    secret: Boolean = false,
    private val done: (String) -> Unit,
) : OverlayBase(act, 0.97f) {
    private val field = editField(hint)

    init {
        place(act.label(title, 64f, Theme.TEXT, Theme.BOLD), 96f, 72f, 1200f, 80f)
        place(act.label(help, 26f, Theme.TEXT_DIM), 96f, 160f, 1400f, 36f)
        val pill = FieldPill(act, field)
        place(pill, 96f, 230f, 1100f, 104f)
        pill.place(ImageView(act).apply { setImageDrawable(act.icon(icon, Theme.TEXT_DIM)) }, 36f, 30f, 44f, 44f)
        pill.place(field, 104f, 26f, 960f, 56f)
        field.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            (if (secret) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
        if (secret) field.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        field.filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
        field.setText(initial)
        field.setSelection(initial.length)
        field.setOnEditorActionListener { v, _, _ ->
            val name = field.text.toString().trim()
            if (name.isNotEmpty()) {
                hideKeyboard(v)
                act.pop()
                done(name)
            }
            true
        }
        post { showKeyboard(field) }
    }
}
