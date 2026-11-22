package com.robbydock.scene

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/**
 * Updates from the project's GitHub releases. Scene looks at most once a day, only when Home opens,
 * and never in the background. An update installs only when its signing certificate matches the
 * installed app's and its version is higher; Android then asks the user to confirm.
 */
object Updates {
    private const val REPO = "RobbyDock/Scene-TV-Launcher"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    /** Largest download accepted. Scene is well under 1 MB. */
    private const val MAX_BYTES = 50L * 1024 * 1024

    class Release(val version: String, val url: String, val size: Long, val notes: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun installed(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** Checks once a day by itself. Off turns off the daily check, not Check Now. */
    fun auto(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    fun lastCheck(ctx: Context) = prefs(ctx).getLong("checked", 0L)

    /** The newest release seen, when it is newer than this app. */
    fun available(ctx: Context): Release? {
        val p = prefs(ctx)
        val v = p.getString("version", null) ?: return null
        val url = p.getString("url", null) ?: return null
        if (!newer(v, installed(ctx))) return null
        return Release(v, url, p.getLong("size", 0L), p.getString("notes", "") ?: "")
    }

    fun skipped(ctx: Context) = prefs(ctx).getString("skipped", "") ?: ""
    fun skip(ctx: Context, version: String) = prefs(ctx).edit().putString("skipped", version).apply()

    /** True when Home should show the quiet dot: a newer version the user hasn't skipped. */
    fun shouldNotify(ctx: Context): Boolean = available(ctx)?.let { it.version != skipped(ctx) } ?: false

    /** Checks when a day has passed since the last check and the daily check is on. */
    fun maybeCheck(ctx: Context, done: () -> Unit) {
        if (!auto(ctx) || System.currentTimeMillis() - lastCheck(ctx) < DAY_MS) return
        check(ctx) { done() }
    }

    /** Asks GitHub for the latest release. [done] gets false when GitHub couldn't be reached. */
    fun check(ctx: Context, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        Net.run({ fetch() }) { r ->
            val p = prefs(app).edit().putLong("checked", System.currentTimeMillis())
            if (r != null) p.putString("version", r.version).putString("url", r.url).putLong("size", r.size).putString("notes", r.notes)
            p.apply()
            done(r != null)
        }
    }

    private fun fetch(): Release? {
        val o = Net.getJson(LATEST)
        // The latest endpoint already leaves out drafts and prereleases; this guards against a change.
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty()) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (a.optString("name").endsWith(".apk") && url.startsWith("https://github.com/$REPO/releases/download/")) {
                return Release(version, url, a.optLong("size"), o.optString("body").trim())
            }
        }
        return null
    }

    /** True when version [a] is higher than [b], comparing the numbers part by part. */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Android's page where the user lets Scene install apps, or null when the TV has none. */
    fun permissionIntent(ctx: Context): Intent? =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .takeIf { it.resolveActivity(ctx.packageManager) != null }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /**
     * Downloads and checks [r], then hands it to Android's installer. [status] gets a short line for
     * each step and for any problem.
     */
    fun install(ctx: Context, r: Release, status: (String) -> Unit) {
        val app = ctx.applicationContext
        status("Downloading ${r.version}")
        Net.run({ download(app, r) }) { result ->
            when {
                result == null -> status("Couldn't download the update. Check the connection and try again.")
                result.second != null -> status(result.second!!)
                else -> try {
                    commit(app, result.first!!)
                    status("Waiting for Android to confirm")
                } catch (e: Exception) {
                    status("Android couldn't start the install.")
                }
            }
        }
    }

    /** The downloaded file, or a reason it was refused. */
    private fun download(ctx: Context, r: Release): Pair<File?, String?> {
        val f = File(ctx.cacheDir, "update.apk")
        f.delete()
        val bytes = Net.getBytes(r.url, 60_000)
        if (bytes.size > MAX_BYTES) return null to "The download is larger than expected, so Scene left it."
        f.writeBytes(bytes)
        val problem = verify(ctx, f)
        if (problem != null) { f.delete(); return null to problem }
        return f to null
    }

    /** Null when [f] is Scene, newer, and signed with the same certificate as this app. */
    private fun verify(ctx: Context, f: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val got = pm.getPackageArchiveInfo(f.path, flags) ?: return "The download isn't a working app file."
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (got.packageName != ctx.packageName) return "The download isn't Scene."
        if (versionCode(got) <= versionCode(mine)) return "The download isn't newer than this version."
        val a = certs(got)
        if (a.isEmpty() || a != certs(mine)) return "The download isn't signed like this copy of Scene, so it wasn't installed."
        return null
    }

    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    /** SHA-256 digests of the signing certificates. */
    private fun certs(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = p.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    private fun commit(ctx: Context, f: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            f.inputStream().use { input -> s.openWrite("scene.apk", 0, f.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

/** Android's installer reports here: it needs the user's confirmation, or the install failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> android.widget.Toast.makeText(ctx, "The update wasn't installed", android.widget.Toast.LENGTH_LONG).show()
        }
        File(ctx.cacheDir, "update.apk").delete()
    }
}
