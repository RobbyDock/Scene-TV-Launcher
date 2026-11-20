package com.robbydock.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

/** Lockout schedule, recovery codes and the QR encoder. All data here is made up. */
class SecurityTest {

    // ---- lockout

    @Test fun firstFourWrongTriesAreFree() {
        for (f in 0..4) assertEquals(0L, LockPolicy.waitAfter(f))
    }

    @Test fun waitDoublesFrom30sAndCapsAt15Min() {
        assertEquals(30_000L, LockPolicy.waitAfter(5))
        assertEquals(60_000L, LockPolicy.waitAfter(6))
        assertEquals(120_000L, LockPolicy.waitAfter(7))
        assertEquals(240_000L, LockPolicy.waitAfter(8))
        assertEquals(480_000L, LockPolicy.waitAfter(9))
        assertEquals(900_000L, LockPolicy.waitAfter(10))
        assertEquals(900_000L, LockPolicy.waitAfter(LockPolicy.MAX_FAILS))
    }

    @Test fun lockStateCountsAndResets() {
        val s = LockState(boot = 1)
        repeat(4) { assertEquals(0L, s.wrong(1_000L)) }
        assertEquals(0L, s.remaining(1_000L))
        assertEquals(30_000L, s.wrong(1_000L))
        assertEquals(30_000L, s.remaining(1_000L))
        assertEquals(10_000L, s.remaining(21_000L))
        s.right()
        assertEquals(0, s.fails)
        assertEquals(0L, s.remaining(21_000L))
    }

    @Test fun rebootRestartsAnActiveWait() {
        val s = LockState(boot = 1)
        repeat(6) { s.wrong(500_000L) }
        // After a reboot, time since boot is small again and the wait stays.
        s.adopt(bootCount = 2, now = 5_000L)
        assertEquals(60_000L, s.remaining(5_000L))
    }

    @Test fun sameBootKeepsTheWait() {
        val s = LockState(boot = 3)
        repeat(5) { s.wrong(10_000L) }
        s.adopt(bootCount = 3, now = 20_000L)
        assertEquals(20_000L, s.remaining(20_000L))
    }

    @Test fun rebootWithoutLockoutStaysOpen() {
        val s = LockState(boot = 1)
        repeat(3) { s.wrong(0L) }
        s.adopt(2, 100L)
        assertEquals(0L, s.remaining(100L))
    }

    // ---- recovery codes

    @Test fun codesUseOnlyClearCharacters() {
        val r = SecureRandom()
        repeat(200) {
            val c = Recovery.generate(r)
            assertEquals(Recovery.LENGTH, c.length)
            assertTrue(c.none { it in "01OIL" })
        }
        assertEquals(31, Recovery.ALPHABET.length)
    }

    @Test fun normalizeAcceptsTypingVariants() {
        assertEquals("ABCDEFGH", Recovery.normalize("abcd-efgh"))
        assertEquals("ABCDEFGH", Recovery.normalize(" ABCD EFGH "))
        assertNull(Recovery.normalize("ABCDEFG"))
        assertNull(Recovery.normalize("ABCDEFG0"))
        assertNull(Recovery.normalize(""))
    }

    @Test fun hashMatchesOnlyTheRightCode() {
        // Few iterations. The app uses Recovery.ITERATIONS.
        val salt = Recovery.newSalt()
        val h = Recovery.hash("ABCDEFGH", salt, 1000)
        assertTrue(Recovery.matches("abcd-efgh", salt, h, 1000))
        assertFalse(Recovery.matches("ABCDEFGJ", salt, h, 1000))
        assertFalse(Recovery.matches("ABCDEFGH", Recovery.newSalt(), h, 1000))
        assertFalse(Recovery.matches("ABCDEFGH", salt, "", 1000))
        assertNotEquals(h, Recovery.hash("ABCDEFGH", Recovery.newSalt(), 1000))
    }

    @Test fun fullStrengthHashIsSlowEnough() {
        val start = System.nanoTime()
        Recovery.hash("ABCDEFGH", Recovery.newSalt())
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("PBKDF2 took $ms ms", ms > 5)
    }

    // ---- QR

    @Test fun qrHasFinderPatternsAndTiming() {
        val m = Qr.encode("ABCD-EFGH")
        assertEquals(21, m.size)
        for ((r, c) in listOf(0 to 0, 0 to 14, 14 to 0)) {
            assertTrue(m[r][c]); assertTrue(m[r + 6][c + 6]); assertTrue(m[r + 3][c + 3])
            assertFalse(m[r + 1][c + 1])
        }
        for (i in 8..12) assertEquals(i % 2 == 0, m[6][i])
        assertTrue(m[13][8])
    }

    @Test fun qrRefusesWhatDoesNotFit() {
        assertFalse(Qr.fits("abc"))
        assertFalse(Qr.fits("A".repeat(21)))
        assertTrue(Qr.fits("ABCD-EFGH"))
    }

    /** Writes sample codes for an outside decoder check. */
    @Test fun qrSamplesForDecoder() {
        val dir = File(System.getProperty("java.io.tmpdir"), "scene-qr").apply { mkdirs() }
        for (text in listOf("ABCD-EFGH", "XK7M-2PQR", "HELLO WORLD", "A")) {
            val rows = Qr.encode(text).joinToString("\n") { row -> row.joinToString("") { if (it) "1" else "0" } }
            File(dir, "$text.txt").writeText(rows)
        }
    }
}
