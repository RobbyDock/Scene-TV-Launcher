package com.robbydock.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/**
 * Sample data. While on, Home shows sample titles, apps, profiles and
 * weather, and nothing is saved or sent to Stremio. It lasts until it is turned off or Scene restarts.
 */
object Demo {
    var on = false
        private set

    const val EMAIL = "alex@example.com"
    private const val PREFIX = "demo.app."

    fun isSample(pkg: String) = pkg.startsWith(PREFIX)

    fun start() { on = true }
    fun stop() { on = false }

    private class SampleApp(val name: String, val icon: Int, val color: Int)

    private val sampleApps = listOf(
        SampleApp("Netflix", R.drawable.ic_movie, 0xFFE5484D.toInt()),
        SampleApp("ESPN", R.drawable.ic_star, 0xFF2E7DF6.toInt()),
        SampleApp("CNN", R.drawable.ic_language, 0xFF3A4A5E.toInt()),
        SampleApp("Spotify", R.drawable.ic_volume_up, 0xFFE0408A.toInt()),
        SampleApp("Crunchyroll", R.drawable.ic_mic, 0xFF8E4EC6.toInt()),
        SampleApp("Plex", R.drawable.ic_wallpaper, 0xFF12A594.toInt()),
        SampleApp("Disney+", R.drawable.ic_child_care, 0xFFF5A623.toInt()),
        SampleApp("HBO Max", R.drawable.ic_rocket_launch, 0xFF5B5BD6.toInt()),
        SampleApp("Prime Video", R.drawable.ic_timer, 0xFF30A46C.toInt()),
        SampleApp("Hulu", R.drawable.ic_travel_explore, 0xFF0B8FB8.toInt()),
        SampleApp("Pluto TV", R.drawable.ic_tv, 0xFFD1501F.toInt()),
        SampleApp("Peacock", R.drawable.ic_folder, 0xFF6E6A7C.toInt()),
        SampleApp("Paramount+", R.drawable.ic_bedtime, 0xFF34407A.toInt()),
        SampleApp("Tubi", R.drawable.ic_visibility, 0xFF46505A.toInt()),
    )

    /** Icon from files/demo-icons/<name>.png when present, for example "primevideo.png". */
    private fun iconFile(ctx: Context, name: String, size: Int): Bitmap? {
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        val f = java.io.File(ctx.getExternalFilesDir(null), "demo-icons/$key.png")
        if (!f.isFile) return null
        val b = android.graphics.BitmapFactory.decodeFile(f.path) ?: return null
        return Bitmap.createScaledBitmap(b, size, size, true)
    }

    /** Sample apps. Without an icon file, the icon is a coloured square with a white symbol. */
    fun apps(ctx: Context, size: Int): List<AppEntry> = sampleApps.mapIndexed { i, a ->
        iconFile(ctx, a.name, size)?.let { return@mapIndexed AppEntry(PREFIX + i, a.name, it) }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(a.color)
        ctx.getDrawable(a.icon)?.mutate()?.let { d ->
            d.setTint(Color.WHITE)
            val m = (size * 0.27f).toInt()
            d.setBounds(m, m, size - m, size - m)
            d.draw(c)
        }
        AppEntry(PREFIX + i, a.name, bmp)
    }

    /** Pinned row: real apps first when installed, then a few sample ones. */
    fun pins(installed: (String) -> Boolean): ArrayList<Pin> {
        val out = ArrayList<Pin>()
        for ((pkg, label) in listOf(Config.STREMIO to "Stremio", "com.google.android.youtube.tv" to "YouTube")) {
            if (installed(pkg)) out.add(Pin(pkg, label))
        }
        for (i in listOf(0, 1, 3, 10, 6)) out.add(Pin(PREFIX + i, sampleApps[i].name))
        return out
    }

    fun profiles(): List<Profile> = listOf(
        Profile("p0", "Alex", 0).apply { emoji = "🦊"; admin = true },
        Profile("p1", "Sam", 9).apply { emoji = "🐼" },
        Profile("p2", "Jordan", 11).apply { emoji = "🎮" },
        Profile("p3", "Kids", 4).apply { emoji = "🦄" },
    )

    /** Mild and clear, in Celsius. */
    fun weather() = WeatherNow(21.0, 1, true, 24.0, 15.0, System.currentTimeMillis())

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    /** Sample Continue watching items, newest first. */
    fun continueItems(): List<CwItem> {
        val now = System.currentTimeMillis()
        fun series(id: String, name: String, s: Int, e: Int, ago: Long, progress: Float = 0f, new: Int = 0) =
            CwItem(id, "series", name, poster(id), "$id:$s:$e", (progress * 44 * MIN).toLong(),
                if (progress > 0f) 44 * MIN else 0L, now - ago, newCount = new)
        fun movie(id: String, name: String, ago: Long, progress: Float) =
            CwItem(id, "movie", name, poster(id), id, (progress * 140 * MIN).toLong(), 140 * MIN, now - ago)
        return listOf(
            series("tt2805096", "Chicago P.D.", 12, 8, 2 * HOUR, new = 1),
            series("tt2261391", "Chicago Fire", 13, 9, 5 * HOUR, 0.42f),
            series("tt4655480", "Chicago Med", 10, 6, 20 * HOUR, new = 2),
            series("tt7631058", "The Lord of the Rings: The Rings of Power", 2, 3, 26 * HOUR, 0.65f),
            series("tt10986410", "Ted Lasso", 3, 4, 30 * HOUR, 0.18f),
            movie("tt1745960", "Top Gun: Maverick", 2 * 24 * HOUR, 0.55f),
            series("tt11691774", "Only Murders in the Building", 4, 2, 3 * 24 * HOUR, new = 1),
            series("tt14452776", "The Bear", 3, 5, 4 * 24 * HOUR, 0.3f),
            series("tt9288030", "Reacher", 2, 6, 5 * 24 * HOUR, 0.8f),
            series("tt5875444", "Slow Horses", 4, 2, 6 * 24 * HOUR, new = 3),
            movie("tt15239678", "Dune: Part Two", 7 * 24 * HOUR, 0.25f),
            series("tt14218830", "Abbott Elementary", 4, 11, 8 * 24 * HOUR, 0.5f),
            series("tt2467372", "Brooklyn Nine-Nine", 5, 14, 9 * 24 * HOUR, 0.12f),
            series("tt4236770", "Yellowstone", 3, 7, 10 * 24 * HOUR, 0.7f),
        )
    }

    private fun poster(id: String) = "https://images.metahub.space/poster/medium/$id/img"
}
