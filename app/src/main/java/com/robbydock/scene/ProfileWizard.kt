package com.robbydock.scene

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * New profile in four steps: name, picture, colour, then a review. Nothing is saved until
 * "Create profile". Back goes one step back, and Back on the first step cancels.
 * [first] words it for setup's first profile.
 */
@SuppressLint("ViewConstructor")
class ProfileWizard(
    act: HomeActivity, private val first: Boolean = false,
    private val create: (name: String, emoji: String, color: Int) -> Unit,
) : OverlayBase(act, 1f), BackAware {

    private enum class Step { NAME, PICTURE, COLOUR, REVIEW }

    private var step = Step.NAME
    private var name = ""
    private var emoji = ""
    // The first profile starts on the first colour.
    private var color = if (first) 0 else Profiles.nextColor()
    private val field = editField("Name")

    init {
        field.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.filters = arrayOf(android.text.InputFilter.LengthFilter(Limits.PROFILE_NAME))
        field.setOnEditorActionListener { v, _, _ ->
            val typed = Guard.cleanText(field.text.toString(), Limits.PROFILE_NAME)
            if (typed.isEmpty()) {
                act.toast("A name needs at least one letter or emoji")
            } else {
                name = typed
                hideKeyboard(v)
                show(Step.PICTURE)
            }
            true
        }
        show(Step.NAME)
    }

    override fun onBack(): Boolean {
        val prev = Step.values().getOrNull(step.ordinal - 1) ?: return false
        show(prev)
        return true
    }

    private fun show(s: Step) {
        step = s
        removeAllViews()
        val n = s.ordinal + 1
        place(act.label("Step $n of 4", 24f, Theme.accent, Theme.SEMIBOLD).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 96f, 1920f, 34f)
        when (s) {
            Step.NAME -> stepName()
            Step.PICTURE -> stepPicture()
            Step.COLOUR -> stepColour()
            Step.REVIEW -> stepReview()
        }
    }

    private fun title(text: String, sub: String) {
        place(act.label(text, 64f, Theme.TEXT, Theme.BOLD).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 140f, 1920f, 84f)
        place(act.label(sub, 28f, Theme.TEXT_DIM).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 232f, 1920f, 40f)
    }

    private fun stepName() {
        title(if (first) "Create your profile" else "New profile", "Type a name, then press Done on the keyboard. Back cancels.")
        (field.parent as? android.view.ViewGroup)?.removeView(field)
        val pill = FieldPill(act, field)
        place(pill, (1920f - 1000f) / 2, 320f, 1000f, 104f)
        pill.place(ImageView(act).apply { setImageDrawable(act.icon(R.drawable.ic_account_circle, Theme.TEXT_DIM)) }, 36f, 30f, 44f, 44f)
        pill.place(field, 104f, 26f, 860f, 56f)
        field.setText(name)
        field.setSelection(name.length)
        post { showKeyboard(field) }
    }

    private fun preview(size: Float, x: Float, y: Float, e: String = emoji, c: Int = color): AvatarView {
        val av = AvatarView(act).apply {
            letter = Guard.initial(name); emoji = e; colorIndex = c
        }
        place(av, x, y, size, size)
        return av
    }

    private fun stepPicture() {
        title("Choose a picture", name)
        val choices = listOf("") + Profiles.EMOJI
        val cols = 12
        val size = 112f
        val pitch = 140f
        val x0 = (1920f - (cols - 1) * pitch - size) / 2
        var focus: View? = null
        val views = ArrayList<View>()
        for ((i, e) in choices.withIndex()) {
            val av = preview(size, x0 + (i % cols) * pitch, 320f + (i / cols) * pitch, e).apply {
                contentDescription = e.ifEmpty { "Initial" }
                // Inside apply, plain "emoji" refers to the avatar's own.
                setOnClickListener { this@ProfileWizard.emoji = e; show(Step.COLOUR) }
            }
            views.add(av)
            if (e == emoji) focus = av
        }
        views.forEachIndexed { i, v -> Motion.pop(v, (i % cols + i / cols) * 18L) }
        post { focus?.requestFocus() }
    }

    private fun stepColour() {
        title("Choose an accent colour", "Scene uses it for this profile")
        val big = preview(200f, (1920f - 200f) / 2, 320f)
        val count = Theme.ACCENTS.size
        val size = 72f
        val gap = 32f
        val cols = 7
        val x0 = (1920f - cols * size - (cols - 1) * gap) / 2
        var focus: View? = null
        for (i in 0 until count) {
            val dot = ColorDot(act, i).apply {
                chosen = i == color
                contentDescription = Theme.ACCENTS[i].name
                setOnFocusChangeListener { _, has -> if (has) big.colorIndex = i }
                setOnClickListener { color = i; show(Step.REVIEW) }
            }
            place(dot, x0 + (i % cols) * (size + gap), 560f + (i / cols) * (size + 28f), size, size)
            if (i == color) focus = dot
        }
        post { focus?.requestFocus() }
    }

    private fun stepReview() {
        val finalName = Profiles.nameFor(name, if (first) Profiles.list.firstOrNull() else null)
        title(if (first) "Ready when you are" else "Create this profile?", "You can change any of this later in Settings")
        val av = preview(240f, (1920f - 240f) / 2, 320f)
        Motion.pop(av)
        place(act.label(finalName, 48f, Theme.TEXT, Theme.SEMIBOLD).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 590f, 1920f, 64f)
        val note = if (finalName != name) "$name is taken, so this one is $finalName" else Theme.ACCENTS[color].name
        place(act.label(note, 26f, Theme.TEXT_DIM).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 660f, 1920f, 36f)
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
        val back = PillButton(act, "Back", 0, false).apply { setOnClickListener { show(Step.COLOUR) } }
        val ok = PillButton(act, "Create profile", R.drawable.ic_check, true).apply {
            setOnClickListener {
                act.pop()
                create(name, emoji, color)
            }
        }
        row.addView(back, LinearLayout.LayoutParams(-2, Theme.ipx(84f)))
        row.addView(ok, LinearLayout.LayoutParams(-2, Theme.ipx(84f)).apply { leftMargin = Theme.ipx(24f) })
        place(row, 0f, 760f, 1920f, 100f)
        post { ok.requestFocus() }
    }
}
