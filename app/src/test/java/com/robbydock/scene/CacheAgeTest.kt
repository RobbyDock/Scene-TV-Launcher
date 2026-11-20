package com.robbydock.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Age rules for cached Home data. */
class CacheAgeTest {
    private val hour = 3600_000L

    @Test fun underADayIsFresh() {
        assertEquals(CacheAge.State.FRESH, CacheAge.state(23 * hour))
        assertEquals(CacheAge.State.FRESH, CacheAge.state(-5 * hour))
    }

    @Test fun dayToFourteenDaysIsStale() {
        assertEquals(CacheAge.State.STALE, CacheAge.state(CacheAge.DAY))
        assertEquals(CacheAge.State.STALE, CacheAge.state(CacheAge.MAX))
    }

    @Test fun overFourteenDaysIsExpired() {
        assertEquals(CacheAge.State.EXPIRED, CacheAge.state(CacheAge.MAX + 1))
    }

    @Test fun staleOfflineHidesUpNextButKeepsResume() {
        val age = 2 * CacheAge.DAY
        assertFalse(CacheAge.keepCard(age, online = false, started = false))
        assertTrue(CacheAge.keepCard(age, online = false, started = true))
        assertTrue(CacheAge.keepCard(age, online = true, started = false))
    }

    @Test fun freshOfflineKeepsEverything() {
        assertTrue(CacheAge.keepCard(hour, online = false, started = false))
    }

    @Test fun expiredHidesEverything() {
        val age = 30 * CacheAge.DAY
        assertFalse(CacheAge.keepCard(age, online = true, started = true))
        assertFalse(CacheAge.keepCard(age, online = false, started = true))
    }

    @Test fun weatherDropsAfterADay() {
        assertTrue(CacheAge.keepWeather(2 * hour))
        assertFalse(CacheAge.keepWeather(CacheAge.DAY + 1))
    }
}
