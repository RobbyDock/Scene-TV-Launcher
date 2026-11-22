package com.robbydock.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Admin rules. All profiles here are made up. */
class RolesTest {

    private fun profiles(vararg admin: Boolean) = admin.mapIndexed { i, a ->
        Profile("p$i", "P$i", 0).apply { this.admin = a }
    }

    @Test fun firstProfileBecomesAdminWhenNoneIs() {
        val l = profiles(false, false)
        Roles.ensureAdmin(l)
        assertTrue(l[0].admin)
        assertFalse(l[1].admin)
    }

    @Test fun ensureAdminKeepsExistingAdmins() {
        val l = profiles(false, true)
        Roles.ensureAdmin(l)
        assertFalse(l[0].admin)
        assertTrue(l[1].admin)
    }

    @Test fun onlyAdminCantBeDemoted() {
        val l = profiles(true, false)
        assertFalse(Roles.canDemote(l, l[0]))
        assertFalse(Roles.setAdmin(l, l[0], false))
        assertTrue(l[0].admin)
    }

    @Test fun oneOfTwoAdminsCanBeDemoted() {
        val l = profiles(true, true)
        assertTrue(Roles.setAdmin(l, l[0], false))
        assertFalse(l[0].admin)
        assertFalse(Roles.canDemote(l, l[1]))
    }

    @Test fun demotionClearsRecoveryCode() {
        val l = profiles(true, true)
        l[0].recoveryHash = "h"; l[0].recoverySalt = "s"
        Roles.setAdmin(l, l[0], false)
        assertEquals("", l[0].recoveryHash)
        assertEquals("", l[0].recoverySalt)
    }

    @Test fun promotionKeepsOthers() {
        val l = profiles(true, false)
        assertTrue(Roles.setAdmin(l, l[1], true))
        assertTrue(l[0].admin && l[1].admin)
    }

    @Test fun removingLastAdminPassesItOn() {
        val l = profiles(false, true, false).toMutableList()
        l.removeAt(1)
        Roles.ensureAdmin(l)
        assertTrue(l[0].admin)
    }

    @Test fun othersWhoCanResetExcludesSelf() {
        val l = profiles(true, true, false)
        assertEquals(listOf(l[1]), Roles.othersWhoCanReset(l, l[0]))
        assertEquals(listOf(l[0], l[1]), Roles.othersWhoCanReset(l, l[2]))
        val solo = profiles(true, false)
        assertTrue(Roles.othersWhoCanReset(solo, solo[0]).isEmpty())
    }

    @Test fun kidsProfileCantBecomeAdmin() {
        val l = profiles(true, false)
        l[1].kids = true
        assertFalse(Roles.setAdmin(l, l[1], true))
        assertFalse(l[1].admin)
    }

    @Test fun ensureAdminSkipsKidsProfiles() {
        val l = profiles(false, false)
        l[0].kids = true
        Roles.ensureAdmin(l)
        assertFalse(l[0].admin)
        assertTrue(l[1].admin)
    }

    @Test fun ensureAdminClearsKidsWhenOnlyKidsRemain() {
        val l = profiles(false)
        l[0].kids = true
        Roles.ensureAdmin(l)
        assertTrue(l[0].admin)
        assertFalse(l[0].kids)
    }
}
