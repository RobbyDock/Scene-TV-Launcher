package com.robbydock.scene

/**
 * Admin and standard profiles. Admins can change any profile, reset passcodes, delete profiles
 * and choose other admins. There is always at least one admin.
 */
object Roles {
    /** Makes the first profile an admin when none is marked. */
    fun ensureAdmin(list: List<Profile>) {
        if (list.isNotEmpty() && list.none { it.admin }) list.first().admin = true
    }

    /** False when [p] is the only admin. */
    fun canDemote(list: List<Profile>, p: Profile) = !p.admin || list.count { it.admin } > 1

    /**
     * Sets the role of [p]. A demoted profile loses its recovery code. False when this would
     * leave no admin.
     */
    fun setAdmin(list: List<Profile>, p: Profile, admin: Boolean): Boolean {
        if (p.admin == admin) return true
        if (!admin && !canDemote(list, p)) return false
        p.admin = admin
        if (!admin) { p.recoveryHash = ""; p.recoverySalt = "" }
        return true
    }

    /** Other admins who could reset [p]'s passcode. */
    fun othersWhoCanReset(list: List<Profile>, p: Profile) = list.filter { it.admin && it !== p }
}
