package com.robbydock.scene

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Quick settings side panel, opened from the gear: profile, network, Bluetooth, volume and
 * shortcuts into System settings. The Wi-Fi and Bluetooth tiles show state and open the
 * matching settings page.
 */
@SuppressLint("ViewConstructor")
class QuickPanel(act: HomeActivity) : OverlayBase(act, 0.45f, haze = true), HomeActivity.Returnable {

    private val audio = act.getSystemService(AudioManager::class.java)
    private val tiles = ArrayList<Pair<QuickTile, () -> Unit>>()
    private val clock = act.label("", 48f, Theme.TEXT, Theme.BOLD).apply { gravity = Gravity.END }
    private val date = act.label("", 24f, Theme.TEXT_DIM).apply { gravity = Gravity.END }
    private val tick = Runnable { tickClock() }

    /** Shows the time and date, then waits for the next minute. */
    private fun tickClock() {
        val now = java.util.Date()
        clock.text = android.text.format.DateFormat.getTimeFormat(act).format(now)
        date.text = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now)
        removeCallbacks(tick)
        postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000 + 50)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    init {
        val w = 760f
        val panelH = 850f
        val panel = Panel(act, Theme.R_SHEET, { Theme.surface })
        place(panel, 1920f - w - 40f, (1080f - panelH) / 2, w, panelH)

        // Profile header: avatar, name and a switch shortcut.
        val p = Profiles.current
        val av = AvatarView(act).apply {
            letter = p.initial; emoji = p.emoji; colorIndex = p.color; locked = p.locked
            contentDescription = "Switch profile"
            setOnClickListener { act.pop(); act.push(ProfilePicker(act)) }
        }
        panel.place(av, 48f, 48f, 104f, 104f)
        panel.place(act.label(p.name, 38f, Theme.TEXT, Theme.SEMIBOLD), 180f, 75f, 300f, 50f)
        panel.place(clock, w - 48f - 260f, 52f, 260f, 60f)
        panel.place(date, w - 48f - 260f, 114f, 260f, 34f)
        tickClock()

        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        panel.place(grid, 48f, 196f, w - 96f, 400f)
        val netTile = tile(R.drawable.ic_wifi, "Network", "", false) { act.openSystemSettings(Settings.ACTION_WIFI_SETTINGS) }
        val btTile = tile(R.drawable.ic_settings_remote, "Bluetooth", "", false) { act.openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        val states = {
            network().let { netTile.status = it.first; netTile.active = it.second }
            bluetooth().let { btTile.status = it.first; btTile.active = it.second }
        }
        states()
        tiles.add(netTile to states)
        val rows = listOf(
            listOf(netTile, btTile, volumeTile()),
            listOf(
                tile(R.drawable.ic_display_settings, "Display", "", false) { act.openDisplaySettings() },
                tile(R.drawable.ic_tv, "Screensaver", "", false) { act.pop(); act.openSettings("dream") },
                tile(R.drawable.ic_accessibility_new, "Accessibility", "", false) { act.openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            ),
        )
        for (r in rows) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for ((i, t) in r.withIndex()) {
                line.addView(t, LinearLayout.LayoutParams(0, Theme.ipx(180f), 1f).apply { if (i < r.size - 1) rightMargin = Theme.ipx(20f) })
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Theme.ipx(20f) })
        }

        val links = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        panel.place(links, 34f, 612f, w - 68f, 200f)
        links.addView(SettingRow(act, R.drawable.ic_dashboard_customize, "Launcher Settings").apply {
            trail = SettingRow.Trail.CHEVRON
            divider = false
            setOnClickListener { act.pop(); act.openSettings() }
        }, LinearLayout.LayoutParams(-1, Theme.ipx(96f)))
        links.addView(SettingRow(act, R.drawable.ic_settings, "System Settings").apply {
            trail = SettingRow.Trail.OPEN
            divider = true
            setOnClickListener { act.openSystemSettings(Settings.ACTION_SETTINGS) }
        }, LinearLayout.LayoutParams(-1, Theme.ipx(96f)))

        post { tiles.firstOrNull()?.first?.requestFocus() }
    }

    override fun onReturn() {
        // Refreshes the live states on return from System settings.
        for ((_, update) in tiles) update()
    }

    private fun tile(icon: Int, title: String, status: String, on: Boolean, click: () -> Unit) =
        QuickTile(act, icon, title).apply {
            this.status = status
            active = on
            setOnClickListener { click() }
        }

    private fun network(): Pair<String, Boolean> {
        val cm = act.getSystemService(ConnectivityManager::class.java) ?: return "Unknown" to false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Not connected" to false
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "Connected"
        }
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return (if (online) kind else "$kind · No internet") to online
    }

    private fun bluetooth(): Pair<String, Boolean> = try {
        val on = act.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        (if (on) "On" else "Off") to on
    } catch (e: SecurityException) {
        "Remotes and audio" to false
    }

    /** Volume: shows the level when the box sets it, and OK opens Sound settings. */
    private fun volumeTile(): QuickTile {
        val t = QuickTile(act, R.drawable.ic_volume_up, "Volume")
        val update = {
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
            val v = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: max
            // Hides the bar when the box does not set the volume.
            val tv = audio == null || audio.isVolumeFixed ||
                (v == max && audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == android.media.AudioDeviceInfo.TYPE_HDMI })
            t.status = when {
                tv -> "Set by your TV"
                v == 0 -> "Muted"
                else -> "$v of $max"
            }
            t.level = if (tv) -1f else v.toFloat() / max
        }
        update()
        t.setOnClickListener { act.openSoundSettings() }
        tiles.add(t to update)
        return t
    }
}

/**
 * A quick settings tile: a large icon over its name. The icon fills with the accent while on.
 * [level] draws a ring around the icon from 0 to 1. [status] is spoken, not shown.
 */
@SuppressLint("ViewConstructor")
class QuickTile(ctx: Context, iconRes: Int, private val title: String) : FocusView(ctx) {
    var status = ""
        set(v) { field = v; contentDescription = if (v.isEmpty()) title else "$title, $v" }
    var active = false
        set(v) { field = v; invalidate() }
    var level = -1f
        set(v) { field = v; invalidate() }
    private val ic = ctx.icon(iconRes, Theme.TEXT)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tt = textPaint(26f, Theme.TEXT, Theme.SEMIBOLD)
    private val oval = android.graphics.RectF()
    override val focusScale = 1.05f
    override val restZ = 0.1f
    override val hotZ = 22f

    init { contentDescription = title }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = Theme.px(Theme.R_CARD)
        fill.color = if (hot()) Theme.float else Theme.elev
        c.drawRoundRect(0f, 0f, w, h, r, r, fill)
        if (active) {
            fill.color = Theme.withAlpha(Theme.accent, 0.12f)
            c.drawRoundRect(0f, 0f, w, h, r, r, fill)
        }
        val cs = Theme.px(72f)
        val cx = w / 2
        val cy = h * 0.4f
        // The gradient takes the paint's alpha, so the active circle must start opaque.
        fill.color = if (active) Theme.accent else Theme.withAlpha(Theme.accent, 0.16f)
        if (active) Theme.accentFill(fill, cx - cs / 2, cy - cs / 2, cx + cs / 2, cy + cs / 2)
        c.drawCircle(cx, cy, cs / 2, fill)
        fill.shader = null
        if (level >= 0f) {
            val rr = cs / 2 + Theme.px(8f)
            arc.strokeWidth = Theme.px(5f)
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
            arc.color = Theme.withAlpha(Theme.TEXT, 0.16f)
            c.drawArc(oval, 0f, 360f, false, arc)
            arc.color = Theme.accent
            c.drawArc(oval, -90f, 360f * level.coerceIn(0f, 1f), false, arc)
        }
        ic.setTint(if (active) Theme.onAccent else Theme.accent)
        val s = (cs * 0.5f).toInt()
        ic.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        ic.draw(c)
        val name = tt.fit(title, w - Theme.px(24f))
        c.drawText(name, cx - tt.measureText(name) / 2, baseline(tt, h * 0.8f), tt)
        drawRing(c, r)
    }
}
