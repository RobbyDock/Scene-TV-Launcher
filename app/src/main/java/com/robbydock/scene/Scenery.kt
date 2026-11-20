package com.robbydock.scene

import kotlin.random.Random

/** Colour sets for the ambient screensaver. Each run picks one. */
object Scenery {
    /** [orbits] holds, per glow, x speed, y speed, x phase and y phase. */
    class Scene(val name: String, val base: Int, val glows: List<Int>, val orbits: List<DoubleArray>)

    private class Set(val name: String, val base: Int, val glows: List<Int>)

    private val SETS = listOf(
        Set("Dusk", 0xFF120B16.toInt(), listOf(0xFF7A2E52.toInt(), 0xFFD9674E.toInt(), 0xFF4B3A8C.toInt(), 0xFF2A1A3A.toInt())),
        Set("Ocean", 0xFF061019.toInt(), listOf(0xFF0E4D6B.toInt(), 0xFF1E8C8C.toInt(), 0xFF2B3F8F.toInt(), 0xFF0A2A3A.toInt())),
        Set("Forest", 0xFF07110B.toInt(), listOf(0xFF1F5A3A.toInt(), 0xFF6B7A2A.toInt(), 0xFFB07A2E.toInt(), 0xFF123826.toInt())),
        Set("Ember", 0xFF120806.toInt(), listOf(0xFF8C2A1A.toInt(), 0xFFD9772E.toInt(), 0xFF5A1A2E.toInt(), 0xFF3A160E.toInt())),
        Set("Aurora", 0xFF050B12.toInt(), listOf(0xFF1E7A5A.toInt(), 0xFF3A4FA8.toInt(), 0xFF7A3A9C.toInt(), 0xFF0E3A3A.toInt())),
    )

    val names get() = SETS.map { it.name } + "Accent"

    /** A random set, or one built from [accent], with fresh orbits. */
    fun pick(accent: Int, rnd: Random = Random.Default): Scene {
        val i = rnd.nextInt(SETS.size + 1)
        val set = SETS.getOrNull(i) ?: fromAccent(accent)
        // Periods of roughly 1.5 to 4 minutes per axis.
        val orbits = set.glows.map {
            doubleArrayOf(0.026 + rnd.nextDouble() * 0.04, 0.022 + rnd.nextDouble() * 0.04, rnd.nextDouble() * 6.28, rnd.nextDouble() * 6.28)
        }
        return Scene(set.name, set.base, set.glows, orbits)
    }

    /** The profile's accent with darker and shifted companions, on near-black. */
    private fun fromAccent(accent: Int): Set {
        val dark = 0xFF0A070C.toInt()
        return Set("Accent", dark, listOf(
            Theme.mix(accent, dark, 0.35f),
            Theme.mix(accent, 0xFF3A2A6A.toInt(), 0.5f),
            Theme.mix(accent, dark, 0.65f),
            Theme.mix(accent, 0xFF1A3A4A.toInt(), 0.6f),
        ))
    }
}
