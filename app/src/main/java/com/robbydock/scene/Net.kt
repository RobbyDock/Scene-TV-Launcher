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

    fun getBytes(url: String, timeoutMs: Int = 12_000): ByteArray {
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

    fun postJson(url: String, body: JSONObject): JSONObject {
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
