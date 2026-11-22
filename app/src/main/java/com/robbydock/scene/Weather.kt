package com.robbydock.scene

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder
import java.util.TimeZone

class WeatherNow(val temp: Double, val code: Int, val day: Boolean, val high: Double, val low: Double, val at: Long)

class Place(val name: String, val region: String, val lat: Double, val lon: Double)

/** Open-Meteo weather. Fetched at most every 30 minutes and only while Home is shown. */
object Weather {
    private const val CACHE = "weather.json"
    private const val MAX_AGE = 30 * 60 * 1000L

    /** The saved reading while under a day old. A damaged file is deleted. */
    fun cached(ctx: Context): WeatherNow? {
        val text = Store.readText(ctx, CACHE) ?: return null
        val w = try { parse(JSONObject(text)) } catch (e: Exception) { clearCache(ctx); return null }
        return w?.takeIf { CacheAge.keepWeather(System.currentTimeMillis() - it.at) }
    }

    fun isStale(w: WeatherNow?) = w == null || System.currentTimeMillis() - w.at > MAX_AGE

    /** Fetches the current weather for a position. */
    fun fetch(ctx: Context, lat: Double, lon: Double): WeatherNow? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=1"
        val o = Net.getJson(url).put("fetchedAt", System.currentTimeMillis())
        Store.writeText(ctx, CACHE, o.toString())
        return parse(o)
    }

    fun clearCache(ctx: Context) = Store.delete(ctx, CACHE)

    class Day(val date: java.time.LocalDate, val code: Int, val high: Double, val low: Double)

    /** The next five days, today first. Fetched only when the forecast is opened. */
    fun forecast(lat: Double, lon: Double): List<Day> {
        val o = Net.getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5").getJSONObject("daily")
        val t = o.getJSONArray("time"); val c = o.getJSONArray("weather_code")
        val hi = o.getJSONArray("temperature_2m_max"); val lo = o.getJSONArray("temperature_2m_min")
        return (0 until t.length()).map { Day(java.time.LocalDate.parse(t.getString(it)), c.optInt(it), hi.optDouble(it), lo.optDouble(it)) }
    }

    /** Searches places by name. */
    fun searchPlaces(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val o = Net.getJson("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&language=en")
        val a = o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val region = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotEmpty() }.joinToString(", ")
            Place(r.optString("name"), region, r.optDouble("latitude"), r.optDouble("longitude"))
        }
    }

    fun hasLocationPermission(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Asks Android once for this TV's approximate position, from Wi-Fi through Google Play
     * services. Calls back on the main thread with null when it is not available.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun deviceLocation(ctx: Context, done: (android.location.Location?) -> Unit) {
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        if (lm == null || !hasLocationPermission(ctx)) { done(null); return }
        val providers = listOf("fused", android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } }
        val recent = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 6 * 3600_000L) { done(recent); return }
        val provider = providers.firstOrNull { it != android.location.LocationManager.PASSIVE_PROVIDER }
        if (provider == null || android.os.Build.VERSION.SDK_INT < 30) { done(recent); return }
        var answered = false
        val signal = android.os.CancellationSignal()
        Net.main.postDelayed({ if (!answered) { answered = true; signal.cancel(); done(recent) } }, 15_000)
        try {
            lm.getCurrentLocation(provider, signal, ctx.mainExecutor) { loc ->
                if (!answered) { answered = true; done(loc ?: recent) }
            }
        } catch (e: Exception) {
            if (!answered) { answered = true; done(recent) }
        }
    }

    /** Town name for a position, through Android's geocoder. */
    @Suppress("DEPRECATION")
    fun placeName(ctx: Context, lat: Double, lon: Double): String? = try {
        if (!android.location.Geocoder.isPresent()) null
        else android.location.Geocoder(ctx).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    } catch (e: Exception) {
        null
    }

    /** Best guess for first run without location permission: the city in the device time zone. */
    fun timeZoneCity(): String? {
        val id = TimeZone.getDefault().id
        if (!id.contains('/')) return null
        return id.substringAfterLast('/').replace('_', ' ')
    }

    private fun parse(o: JSONObject): WeatherNow? {
        val cur = o.optJSONObject("current") ?: return null
        val daily = o.optJSONObject("daily")
        return WeatherNow(
            cur.optDouble("temperature_2m"), cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1,
            daily?.optJSONArray("temperature_2m_max")?.optDouble(0) ?: Double.NaN,
            daily?.optJSONArray("temperature_2m_min")?.optDouble(0) ?: Double.NaN,
            o.optLong("fetchedAt"),
        )
    }

    fun format(c: Double, fahrenheit: Boolean): String {
        if (c.isNaN()) return "–"
        val v = if (fahrenheit) c * 9 / 5 + 32 else c
        return "${Math.round(v)}°"
    }

    /** WMO weather code to a short label and an icon. */
    fun describe(code: Int, day: Boolean): Pair<String, Int> = when (code) {
        0 -> (if (day) "Clear" else "Clear") to (if (day) R.drawable.ic_sunny else R.drawable.ic_clear_night)
        1, 2 -> "Partly cloudy" to (if (day) R.drawable.ic_partly_cloudy_day else R.drawable.ic_partly_cloudy_night)
        3 -> "Cloudy" to R.drawable.ic_cloud
        45, 48 -> "Fog" to R.drawable.ic_foggy
        in 51..57 -> "Drizzle" to R.drawable.ic_rainy
        in 61..67, in 80..82 -> "Rain" to R.drawable.ic_rainy
        in 71..77, 85, 86 -> "Snow" to R.drawable.ic_weather_snowy
        in 95..99 -> "Storms" to R.drawable.ic_thunderstorm
        else -> "–" to R.drawable.ic_cloud
    }

    /** A colour for the weather icon, so sun, rain and snow read apart at a glance. */
    fun tint(code: Int, day: Boolean): Int = when (code) {
        0, 1, 2 -> if (day) Theme.STAR else 0xFFB9C3FF.toInt()
        3 -> 0xFFB4BDCB.toInt()
        45, 48 -> 0xFFA7AFB8.toInt()
        in 51..67, in 80..82 -> 0xFF5DADFF.toInt()
        in 71..77, 85, 86 -> 0xFFD6ECFF.toInt()
        in 95..99 -> 0xFFB98CFF.toInt()
        else -> Theme.STAR
    }
}
