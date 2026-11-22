package com.robbydock.scene

import android.annotation.SuppressLint
import android.widget.ImageView
import android.widget.LinearLayout

/** Sign-in for each Watch With app. The app changes only once sign-in succeeds. */
object WatchSignIn {

    fun start(act: HomeActivity, src: Source) = when (src) {
        Source.STREMIO -> {
            act.sourceChanged(Source.STREMIO)
            if (!Stremio.signedIn(act)) act.openSignIn() else Unit
        }
        Source.JELLYFIN -> findJellyfin(act)
        Source.PLEX -> act.push(PlexSignIn(act))
    }

    // ---------------------------------------------------------------- Jellyfin

    /** Looks for servers on the network, then offers them with Enter Address. */
    private fun findJellyfin(act: HomeActivity) {
        act.toast("Looking for Jellyfin servers")
        Net.run({ Jellyfin.discover() }) { found ->
            val a = ArrayList<Sheet.Action>()
            for (s in found.orEmpty()) a.add(Sheet.Action(s.name, R.drawable.ic_storage, s.address) { useJellyfin(act, s.address) })
            a.add(Sheet.Action("Enter Address", R.drawable.ic_keyboard, "Such as 192.168.1.20:8096") { typeAddress(act) })
            val sub = if (found.isNullOrEmpty()) "No server answered on this network" else "Servers on this network"
            act.push(Sheet(act, "Connect to Jellyfin", sub, null, a))
        }
    }

    private fun typeAddress(act: HomeActivity) {
        act.push(NameEntry(act, "Jellyfin server address", "",
            help = "The address you use to open Jellyfin in a browser. Port 8096 is added when left out.",
            hint = "192.168.1.20:8096", icon = R.drawable.ic_link, maxLen = 120) { text -> useJellyfin(act, text) })
    }

    /** Checks the server and that Quick Connect is on, then shows the code screen. */
    private fun useJellyfin(act: HomeActivity, text: String) {
        Net.run({
            runCatching {
                val address = Jellyfin.normalise(text)
                val server = Jellyfin.check(address)
                server to Jellyfin.quickEnabled(address)
            }
        }) { r ->
            val e = r?.exceptionOrNull()
            val got = r?.getOrNull()
            when {
                got == null -> problem(act, "Couldn't reach that server", when (e) {
                    is IllegalStateException -> "Scene only connects to servers on your home network over plain HTTP. Use an https address for a server outside it."
                    is Jellyfin.Refused -> e.message ?: "That address isn't a Jellyfin server."
                    else -> "Check the address, and that the server is on. Then try again."
                })
                !got.second -> problem(act, "Turn on Quick Connect",
                    "Scene signs in with Quick Connect. In Jellyfin, open Dashboard, then General, and turn on Quick Connect. Then try again.")
                else -> act.push(JellyfinSignIn(act, got.first))
            }
        }
    }

    private fun problem(act: HomeActivity, title: String, body: String) {
        act.push(Dialog(act, R.drawable.ic_info, title, body, listOf(Dialog.Button("OK", primary = true, focus = true))))
    }

    /** Plex: picks the server once the PIN is approved. */
    internal fun choosePlex(act: HomeActivity, account: String, list: List<Plex.Server>) {
        val a = list.map { s ->
            Sheet.Action(s.name, R.drawable.ic_storage, if (s.owned) "Your server" else "Shared with you") { connectPlex(act, account, s) }
        }
        act.push(Sheet(act, "Choose a Plex server", "Continue watching and the library come from it", null, a))
    }

    internal fun connectPlex(act: HomeActivity, account: String, s: Plex.Server) {
        act.toast("Connecting to ${s.name}")
        Net.run({ Plex.connect(act, account, s) }) { ok ->
            if (ok == true) done(act, Source.PLEX, s.name)
            else problem(act, "Couldn't reach ${s.name}", "The server didn't answer on any of its addresses. Check it's on, then try again.")
        }
    }

    internal fun done(act: HomeActivity, src: Source, who: String?) {
        act.sourceChanged(src)
        act.toast(if (who.isNullOrEmpty()) "Watching with ${src.label}" else "Signed in as $who")
    }
}

/**
 * Code screen shared by Jellyfin Quick Connect and the Plex PIN: the app's mark, three steps,
 * the code in large boxes, and a status line while it waits for approval.
 */
@SuppressLint("ViewConstructor")
abstract class CodeScreen(act: HomeActivity, src: Source, title: String, steps: List<String>, private val lifeMs: Long) :
    OverlayBase(act, 0.8f, haze = true), HomeActivity.Closable {
    private val codeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    protected val status = act.label("Getting a code…", 26f, Theme.TEXT_DIM)
    private val retry = PillButton(act, "Try again", R.drawable.ic_refresh, true)
    private var expires = 0L
    private var waiting = false
    protected var closed = false
    private val poll = Runnable { check() }
    private val clock = Runnable { tick() }

    init {
        val mark = Panel(act, 40f, { Theme.elev })
        place(mark, 330f, 330f, 420f, 420f)
        mark.place(ImageView(act).apply { setImageResource(src.icon) }, 90f, 90f, 240f, 240f)
        place(act.label(title, 64f, Theme.TEXT, Theme.BOLD), 870f, 268f, 980f, 80f)
        for ((i, s) in steps.withIndex()) {
            val y = 380f + i * 78f
            val dot = Panel(act, 28f, { Theme.elev }, false)
            place(dot, 870f, y, 56f, 56f)
            dot.place(act.label("${i + 1}", 26f, Theme.accent, Theme.SEMIBOLD).apply { gravity = android.view.Gravity.CENTER }, 0f, 0f, 56f, 56f)
            place(act.label(s, 30f, Theme.TEXT_DIM), 948f, y + 12f, 900f, 40f)
        }
        place(codeRow, 870f, 630f, 900f, 120f)
        place(status, 870f, 782f, 900f, 36f)
        place(retry, 870f, 840f, -1f, 80f)
        retry.visibility = GONE
        retry.setOnClickListener { begin() }
    }

    /** Gets a new code. Runs off the main thread. */
    protected abstract fun fetch(): String
    /** True once approved. Runs off the main thread. */
    protected abstract fun approved(): Boolean
    /** Finishes sign-in after approval. Runs off the main thread; returns false on failure. */
    protected abstract fun finish(): Boolean
    /** Back on the main thread after [finish] succeeds. */
    protected abstract fun finished()

    protected fun begin() {
        retry.visibility = GONE
        status.text = "Getting a code…"
        codeRow.removeAllViews()
        waiting = false
        Net.run({ fetch() }) { c ->
            if (closed) return@run
            if (c == null) { fail("Couldn't get a code. Check the network and try again."); return@run }
            for (ch in c) {
                val box = Panel(act, 24f, { Theme.elev }, false)
                codeRow.addView(box, LinearLayout.LayoutParams(Theme.ipx(96f), Theme.ipx(120f)).apply { rightMargin = Theme.ipx(16f) })
                box.place(act.label(ch.toString(), 64f, Theme.TEXT, Theme.BOLD).apply { gravity = android.view.Gravity.CENTER }, 0f, 0f, 96f, 120f)
            }
            expires = System.currentTimeMillis() + lifeMs
            waiting = true
            removeCallbacks(poll)
            postDelayed(poll, 3000)
            tick()
        }
    }

    private fun tick() {
        removeCallbacks(clock)
        if (closed || !waiting) return
        val left = ((expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        status.text = "Waiting for approval · code expires in ${left / 60}:${"%02d".format(left % 60)}"
        if (left == 0L) { begin(); return }
        postDelayed(clock, 1000)
    }

    private fun check() {
        if (closed || !waiting) return
        Net.run({ approved() }) { ok ->
            if (closed || !waiting) return@run
            if (ok != true) { postDelayed(poll, 3000); return@run }
            waiting = false
            removeCallbacks(clock)
            status.text = "Signing in…"
            Net.run({ finish() }) { done ->
                if (closed) return@run
                if (done != true) { fail("Sign-in failed. Try again."); return@run }
                act.pop()
                finished()
            }
        }
    }

    protected fun fail(msg: String) {
        waiting = false
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
}

/** Jellyfin Quick Connect: the code is approved in another signed-in Jellyfin app. */
@SuppressLint("ViewConstructor")
class JellyfinSignIn(act: HomeActivity, private val server: Jellyfin.Server) : CodeScreen(act, Source.JELLYFIN, "Sign in to ${server.name}", listOf(
    "Open Jellyfin on your phone or computer",
    "Go to your profile, then Quick Connect",
    "Enter this code and choose Authorize",
), 10 * 60_000L) {
    private var secret = ""

    init { begin() }

    override fun fetch(): String = Jellyfin.quickStart(act, server.address).also { secret = it.secret }.code
    override fun approved() = Jellyfin.quickApproved(act, server.address, secret)
    override fun finish(): Boolean { Jellyfin.quickFinish(act, server, secret); return true }
    override fun finished() = WatchSignIn.done(act, Source.JELLYFIN, Jellyfin.account(act))
}

/** Plex PIN: the code is entered at plex.tv/link while signed in to Plex. */
@SuppressLint("ViewConstructor")
class PlexSignIn(act: HomeActivity) : CodeScreen(act, Source.PLEX, "Sign in to Plex", listOf(
    "Open plex.tv/link on your phone or computer",
    "Sign in to your Plex account",
    "Enter this code and choose Link",
), 15 * 60_000L) {
    private var pin: Plex.Pin? = null
    private var token: String? = null
    private var servers: List<Plex.Server> = emptyList()

    init { begin() }

    override fun fetch(): String = Plex.pinStart(act).also { pin = it }.code
    override fun approved(): Boolean = pin?.let { Plex.pinCheck(act, it) }?.also { token = it } != null
    override fun finish(): Boolean {
        servers = Plex.servers(act, token ?: return false)
        return true
    }
    override fun finished() {
        val t = token ?: return
        when (servers.size) {
            0 -> act.push(Dialog(act, R.drawable.ic_info, "No Plex server found",
                "This Plex account has no Plex Media Server of its own or shared with it.", listOf(Dialog.Button("OK", primary = true, focus = true))))
            1 -> WatchSignIn.connectPlex(act, t, servers[0])
            else -> WatchSignIn.choosePlex(act, t, servers)
        }
    }
}

/** Settings preview: the chosen app's mark, its name and the account in use. */
@SuppressLint("ViewConstructor")
class WatchPreview(private val act: HomeActivity, private val src: Source) : android.view.View(act) {
    private val mark = act.getDrawable(src.icon)
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val name = textPaint(30f, Theme.TEXT, Theme.SEMIBOLD)
    private val sub = textPaint(21f, Theme.TEXT_DIM)
    private val box = android.graphics.RectF()

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        fill.color = Theme.surface
        box.set(0f, 0f, w, h)
        c.drawRoundRect(box, Theme.px(40f), Theme.px(40f), fill)
        val m = Theme.px(64f)
        val left = Theme.px(36f); val top = (h - m) / 2
        mark?.setBounds(left.toInt(), top.toInt(), (left + m).toInt(), (top + m).toInt())
        mark?.draw(c)
        c.drawText(src.label, left + m + Theme.px(22f), top + Theme.px(30f), name)
        c.drawText(if (Watch.signedIn(act) && Watch.source(act) == src) (Watch.account(act) ?: "Signed in") else "Not signed in", left + m + Theme.px(22f), top + Theme.px(60f), sub)
    }
}
