package com.robbydock.scene

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Typing an admin's recovery code with the on-screen keyboard. */
fun RecoveryEntry(act: HomeActivity, profile: Profile, finished: () -> Unit) = NameEntry(
    act, "Recovery code", "",
    help = "Type the 8-character code, then press Done on the keyboard.",
    hint = "XXXX-XXXX", icon = R.drawable.ic_workspace_premium, maxLen = 9, secret = true,
) { text -> act.useRecovery(profile, text, finished) }

/**
 * Shows a new recovery code once: a QR code with the code written underneath. Back counts as
 * "saved".
 */
@SuppressLint("ViewConstructor")
class RecoveryCodeScreen(act: HomeActivity, code: String, intro: String, private val finished: () -> Unit) :
    OverlayBase(act, 1f), BackAware {

    private var closed = false

    init {
        place(act.label("Your recovery code", 60f, Theme.TEXT, Theme.BOLD).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 64f, 1920f, 80f)
        place(act.label(intro, 26f, Theme.TEXT_DIM).apply { gravity = Gravity.CENTER_HORIZONTAL }, 0f, 150f, 1920f, 36f)
        place(QrView(act, Recovery.display(code)), (1920f - 420f) / 2, 220f, 420f, 420f)
        place(act.label(Recovery.display(code), 80f, Theme.TEXT, Theme.BOLD).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }, 0f, 670f, 1920f, 110f)
        place(act.label("Take a photo or write this down. It's shown only once, and it unlocks this profile if the passcode is forgotten.", 24f, Theme.TEXT_DIM).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, 0f, 800f, 1920f, 34f)
        val ok = PillButton(act, "I've saved it", R.drawable.ic_check, true).apply { setOnClickListener { finish() } }
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        row.addView(ok, LinearLayout.LayoutParams(-2, Theme.ipx(84f)))
        place(row, 0f, 880f, 1920f, 84f)
        post { ok.requestFocus() }
    }

    private fun finish() {
        if (closed) return
        closed = true
        act.pop()
        finished()
    }

    override fun onBack(): Boolean { finish(); return true }
}

/** Draws a QR code on a white card with the standard quiet zone. */
@SuppressLint("ViewConstructor")
class QrView(ctx: android.content.Context, text: String) : View(ctx) {
    private val matrix = Qr.encode(text)
    private val paint = Paint().apply { isAntiAlias = false }

    init { contentDescription = "QR code for $text" }

    override fun onDraw(c: Canvas) {
        val n = matrix.size + 8
        val cell = minOf(width, height) / n
        val side = cell * n
        val x0 = (width - side) / 2f
        val y0 = (height - side) / 2f
        paint.color = Color.WHITE
        val r = Theme.px(Theme.R_CARD)
        c.drawRoundRect(x0, y0, x0 + side, y0 + side, r, r, paint)
        paint.color = Color.BLACK
        for (row in matrix.indices) for (col in matrix.indices) {
            if (!matrix[row][col]) continue
            val x = x0 + (col + 4) * cell
            val y = y0 + (row + 4) * cell
            c.drawRect(x, y, x + cell, y + cell, paint)
        }
    }
}
