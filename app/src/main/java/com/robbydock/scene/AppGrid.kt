package com.robbydock.scene

import android.annotation.SuppressLint
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Every app of a Home row or of All apps, for rows too long to show in full on Home. Tiles work as
 * on Home: OK opens, holding OK shows the app's menu. The grid reloads when that menu closes.
 */
@SuppressLint("ViewConstructor")
class AppGridScreen(
    act: HomeActivity,
    private val title: String,
    private val icon: Int,
    /** True for All apps, so the menu offers the All apps choices. */
    private val allApps: Boolean,
    /** Index of the Home row, or -1 for All apps. */
    private val row: Int,
    private val apps: () -> List<String>,
    private val note: () -> String,
) : OverlayBase(act, 1f), HomeActivity.Returnable {
    private val scroller = ScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(Theme.ipx(60f))
    }
    private val grid = FrameLayout(act).apply { clipChildren = false }
    private val sub = act.label("", 26f, Theme.TEXT_DIM, Theme.MEDIUM)
    private val cols = ((Theme.W - 2 * EDGE + GAP) / (HomeScreen.TILE + GAP)).toInt().coerceAtLeast(4)

    private companion object {
        const val EDGE = 96f
        const val GAP = 44f
        const val LINE = 40f
        /** Where the first line starts, under the title. */
        const val TOP = 220f
    }

    init {
        scroller.addView(grid, LayoutParams(-1, -2))
        place(act.clipBox(scroller), 0f, 0f, Theme.W, Theme.H)
        val scrim = View(act).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Theme.bg, Theme.bg, Theme.withAlpha(Theme.bg, 0f)))
        }
        place(scrim, 0f, 0f, Theme.W, TOP - 20f)
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(IconBadge(act, icon), LinearLayout.LayoutParams(Theme.ipx(64f), Theme.ipx(64f)).apply { rightMargin = Theme.ipx(22f) })
        head.addView(act.label(title, 60f, Theme.TEXT, Theme.BOLD), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Theme.ipx(20f) })
        head.addView(sub, LinearLayout.LayoutParams(-2, -2))
        place(head, EDGE, 48f, Theme.W - EDGE * 2, 90f)
        fill(null)
    }

    /** Lays out the tiles, keeping focus on [keep] when it is still there. */
    private fun fill(keep: String?) {
        grid.removeAllViews()
        val list = apps()
        sub.text = note()
        var focus: View? = null
        for ((i, pkg) in list.withIndex()) {
            val e = act.app(pkg)
            val tile = AppTile(act).also { it.cinema = Theme.CINEMA }.apply {
                label = e?.label ?: pkg
                this.icon = e?.icon
                locked = pkg in act.cfg.locked
                contentDescription = label
                focusKey = pkg
                setOnClickListener { act.launch(pkg) }
                setOnLongClickListener { act.appMenu(pkg, label, allApps, false, row); true }
            }
            grid.addView(tile, LayoutParams(Theme.ipx(HomeScreen.TILE), Theme.ipx(HomeScreen.TILE_H)).apply {
                leftMargin = Theme.ipx(EDGE + (i % cols) * (HomeScreen.TILE + GAP))
                topMargin = Theme.ipx(TOP + (i / cols) * (HomeScreen.TILE_H + LINE))
            })
            if (focus == null || pkg == keep) focus = tile
            if (keep == null && i < cols) Motion.pop(tile, i * 18L)
        }
        val lines = (list.size + cols - 1) / cols
        grid.minimumHeight = Theme.ipx(TOP + lines * (HomeScreen.TILE_H + LINE) + 120f)
        if (list.isEmpty()) { act.pop(); return }
        post { focus?.requestFocus() }
    }

    /** An app may have left the row or been hidden from its menu. */
    override fun onReturn() = fill(lastFocus)

    /** The app focused last, so the grid comes back where it was after a menu. */
    private var lastFocus: String? = null

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        (focused as? FocusView)?.focusKey?.let { lastFocus = it }
    }
}
