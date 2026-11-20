package com.robbydock.scene

/** Age limits for cached Stremio and weather data. */
object CacheAge {
    const val DAY = 24 * 3600_000L
    /** Older than this, cached rows are hidden and the empty state shows. */
    const val MAX = 14 * DAY

    enum class State { FRESH, STALE, EXPIRED }

    /** A clock set back gives a negative age; that counts as fresh. */
    fun state(ageMs: Long): State = when {
        ageMs < DAY -> State.FRESH
        ageMs <= MAX -> State.STALE
        else -> State.EXPIRED
    }

    /**
     * Whether a cached Continue watching card may show. Expired caches show nothing. Offline, a
     * cache over a day old keeps only cards with playback to resume.
     */
    fun keepCard(ageMs: Long, online: Boolean, started: Boolean): Boolean = when (state(ageMs)) {
        State.FRESH -> true
        State.STALE -> online || started
        State.EXPIRED -> false
    }

    /** Cached weather is kept for one day. */
    fun keepWeather(ageMs: Long) = state(ageMs) == State.FRESH
}
