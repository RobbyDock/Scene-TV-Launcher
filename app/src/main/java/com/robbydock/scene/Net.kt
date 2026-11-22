package com.robbydock.scene

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small HTTP helper. Work runs on a shared pool of three threads; results come back on the main thread. */
object Net {
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "scene-io").apply { isDaemon = true } }
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (T?) -> Unit) {
        pool.execute {
            val result = try { work() } catch (e: Exception) { null }
            main.post { done(result) }
        }
    }

    /** One thread for what is on screen now. */
    private val firstPool = Executors.newSingleThreadExecutor { r -> Thread(r, "scene-first").apply { isDaemon = true } }

    fun <T> first(work: () -> T, done: (T?) -> Unit) {
        firstPool.execute {
            val result = try { work() } catch (e: Exception) { null }
            main.post { done(result) }
        }
    }

    fun background(work: () -> Unit) {
        pool.execute { try { work() } catch (e: Exception) { } }
    }

    /** One low-priority thread for prefetching and cache upkeep. */
    private val lowPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scene-low").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun low(work: () -> Unit) {
        lowPool.execute { try { work() } catch (e: Exception) { } }
    }

    /**
     * Plain HTTP only reaches the home network: private, link-local and .local addresses.
     * Anything on the internet must use HTTPS.
     */
    fun checkAddress(url: String) {
        val u = URL(url)
        if (u.protocol == "https") return
        if (u.protocol != "http" || !isLocal(u.host)) throw IllegalStateException("Plain HTTP is only allowed on the home network")
    }

    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size == 4 && h.count { it == '.' } == 3) {
            return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
        }
        // IPv6 unique local and link-local.
        return h.startsWith("fd") || h.startsWith("fe80") || h == "::1"
    }

    fun getBytes(url: String, timeoutMs: Int = 12_000): ByteArray {
        checkAddress(url)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "Scene/1.0 (Android TV launcher)")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    fun getJson(url: String): JSONObject = JSONObject(String(getBytes(url), Charsets.UTF_8))

    class HttpError(val code: Int) : Exception("HTTP $code")

    /**
     * One request with extra [headers]. Returns the body as text; throws [HttpError] for a
     * reply outside 2xx. Used by the Jellyfin and Plex clients.
     */
    fun request(
        url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 12_000,
    ): String {
        checkAddress(url)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs + 3_000
        c.requestMethod = method
        c.setRequestProperty("User-Agent", "Scene/1.0 (Android TV launcher)")
        c.setRequestProperty("Accept", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        try {
            if (body != null) {
                c.doOutput = true
                if (!headers.containsKey("Content-Type")) c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            return c.inputStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } finally {
            c.disconnect()
        }
    }

    fun postJson(url: String, body: JSONObject): JSONObject {
        checkAddress(url)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 15_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Scene/1.0 (Android TV launcher)")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "{}"
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
