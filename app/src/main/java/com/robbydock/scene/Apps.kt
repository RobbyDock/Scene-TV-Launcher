package com.robbydock.scene

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

class AppEntry(val pkg: String, val label: String, val icon: Bitmap?, val installed: Long = 0L)

object Apps {
    /** Resolves the launch intent from the package name. */
    fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun isInstalled(pm: PackageManager, pkg: String) = launchIntent(pm, pkg) != null

    fun label(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    class Info(val version: String, val updated: Long, val system: Boolean)

    fun info(pm: PackageManager, pkg: String): Info? = try {
        val pi = pm.getPackageInfo(pkg, 0)
        val flags = pi.applicationInfo?.flags ?: 0
        Info(pi.versionName ?: "", pi.lastUpdateTime, flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun icon(pm: PackageManager, pkg: String, size: Int): Bitmap? = try {
        toBitmap(pm.getApplicationIcon(pkg), size)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Every launchable app except Scene itself. */
    fun listAll(ctx: Context, iconSize: Int): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                val installed = try { pm.getPackageInfo(pkg, 0).firstInstallTime } catch (e: PackageManager.NameNotFoundException) { 0L }
                out.add(AppEntry(pkg, ri.loadLabel(pm).toString(), toBitmap(ri.loadIcon(pm), iconSize), installed))
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    /**
     * Renders an icon as a square bitmap. Adaptive icons draw both layers without the system
     * mask. Legacy icons are trimmed, and non-square ones sit on a tinted plate.
     */
    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            // Layers are 108 units with the visible 72 in the middle.
            val inset = (size * 0.25f).toInt()
            for (layer in arrayOf(d.background, d.foreground)) {
                layer ?: continue
                layer.setBounds(-inset, -inset, size + inset, size + inset)
                layer.draw(c)
            }
            return bmp
        }
        val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(raw))
        val trim = opaqueBounds(raw)
        if (trim == null) return raw
        val cropped = Bitmap.createBitmap(raw, trim.left, trim.top, trim.width(), trim.height())
        val square = kotlin.math.abs(trim.width() - trim.height()) <= size / 20
        if (square && cornersOpaque(cropped)) {
            c.drawBitmap(cropped, null, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawColor(plateColor(cropped))
            val inner = (size * 0.68f).toInt()
            val scale = inner.toFloat() / maxOf(trim.width(), trim.height())
            val w = (trim.width() * scale).toInt()
            val h = (trim.height() * scale).toInt()
            c.drawBitmap(cropped, null, Rect((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return bmp
    }

    private fun opaqueBounds(b: Bitmap): Rect? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var btm = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 24) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > btm) btm = y
            }
        }
        return if (r < l || btm < t) null else Rect(l, t, r + 1, btm + 1)
    }

    private fun cornersOpaque(b: Bitmap): Boolean {
        val i = maxOf(1, b.width / 14)
        val pts = arrayOf(i to i, b.width - 1 - i to i, i to b.height - 1 - i, b.width - 1 - i to b.height - 1 - i)
        return pts.all { (x, y) -> (b.getPixel(x, y) ushr 24) > 200 }
    }

    /** A dark plate tinted by the icon's average colour. */
    private fun plateColor(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = s.getPixel(x, y)
            if ((p ushr 24) < 128) continue
            rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p); n++
        }
        if (n == 0L) return Theme.elev
        val avg = Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
        return Theme.mix(Theme.elev, avg, 0.35f)
    }
}
