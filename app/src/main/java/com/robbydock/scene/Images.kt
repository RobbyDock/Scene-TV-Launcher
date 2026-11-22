package com.robbydock.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/**
 * Image loader with a disk cache in cacheDir, decoding with downsampling, RGB_565 for opaque
 * art, and a byte-capped memory cache.
 *
 * The disk cache is about 4% of free space, between 48 MB and 256 MB.
 */
object Images {
    private const val MIN_DISK = 48L * 1024 * 1024
    private const val MAX_DISK = 256L * 1024 * 1024

    private var dir: File? = null
    private var budget = MIN_DISK
    private var writesSinceTrim = 0
    private val mem = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun init(ctx: Context) {
        val d = File(ctx.cacheDir, "img").apply { mkdirs() }
        dir = d
        budget = (d.usableSpace / 25).coerceIn(MIN_DISK, MAX_DISK)
    }

    fun cached(url: String, width: Int): Bitmap? = mem.get(key(url, width))

    /**
     * Loads [url] decoded to [width] pixels wide. Callbacks run on the main thread. [exact] false
     * keeps up to twice the width, for art drawn larger than asked, such as the full-screen backdrop.
     */
    fun load(url: String?, width: Int, opaque: Boolean = true, exact: Boolean = true, done: (Bitmap?) -> Unit) {
        if (url.isNullOrEmpty()) { done(null); return }
        val k = key(url, width)
        mem.get(k)?.let { done(it); return }
        synchronized(inFlight) {
            inFlight[k]?.let { it.add(done); return }
            inFlight[k] = mutableListOf(done)
        }
        Net.run({ fetch(url, width, opaque, exact) }) { bmp ->
            if (bmp != null) mem.put(k, bmp)
            val waiting = synchronized(inFlight) { inFlight.remove(k) } ?: emptyList()
            for (cb in waiting) cb(bmp)
        }
    }

    /** Downloads [url] to disk in the background without decoding it. */
    fun prefetch(url: String?) {
        if (url.isNullOrEmpty()) return
        Net.low { file(url) }
    }

    private fun file(url: String): File? {
        val d = dir ?: return null
        val f = File(d, hash(url))
        if (!f.exists() || f.length() == 0L) {
            val bytes = Net.getBytes(url)
            val tmp = File(d, f.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            if (++writesSinceTrim >= 25) trimDisk()
        } else {
            f.setLastModified(System.currentTimeMillis())
        }
        return f
    }

    private fun fetch(url: String, width: Int, opaque: Boolean, exact: Boolean): Bitmap? {
        val f = file(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            // Halving can leave up to twice the asked width. Opaque art is scaled the rest of the way.
            val sampled = bounds.outWidth / sample
            if (opaque && exact && sampled > width) { inScaled = true; inDensity = sampled; inTargetDensity = width }
        }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    /** Removes the least recently used files until the cache fits its budget. */
    fun trimDisk() {
        val d = dir ?: return
        writesSinceTrim = 0
        Net.low {
            val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return@low
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= budget) break
                total -= f.length()
                f.delete()
            }
        }
    }

    /** Artwork cache size on disk and its budget, in bytes. */
    fun diskUsage(): Pair<Long, Long> = (dir?.listFiles()?.sumOf { it.length() } ?: 0L) to budget

    /** Deletes every cached artwork file. */
    fun clearDisk() {
        dir?.listFiles()?.forEach { it.delete() }
    }

    /** Frees decoded bitmaps. Disk files stay. */
    fun trimMemory() = mem.evictAll()

    /**
     * Small, heavily blurred render of [paint] at [w] x [h]. Three box passes approximate a
     * Gaussian.
     */
    fun blurSync(w: Int, h: Int, paint: (Canvas) -> Unit): Bitmap {
        val sw = 240
        val sh = (sw.toFloat() * h / w).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(sw.toFloat() / w, sh.toFloat() / h)
        paint(c)
        val px = IntArray(sw * sh)
        val tmp = IntArray(sw * sh)
        b.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) {
            pass(px, tmp, sw, sh, 4, true)
            pass(tmp, px, sw, sh, 4, false)
        }
        b.setPixels(px, 0, sw, 0, 0, sw, sh)
        return b
    }

    /** One sliding-window box blur pass along rows or columns. */
    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val n = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val j = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + j] else src[j * w + line]
            }
            var rs = 0; var gs = 0; var bs = 0
            for (k in -r..r) {
                val p = at(k)
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
            }
            for (i in 0 until len) {
                val idx = if (horizontal) line * w + i else i * w + line
                dst[idx] = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                val out = at(i - r)
                val inn = at(i + r + 1)
                rs += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                gs += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                bs += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun key(url: String, width: Int) = "$width|$url"

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
