package com.robbydock.scene

import kotlin.random.Random

/** Colour sets for the ambient screensaver. Each run picks one. */
object Scenery {
    /** [orbits] holds, per glow, x speed, y speed, x phase, y phase, swell speed and swell phase. */
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

    /** Every set, and one built from [accent], in a random order. The glows share one set of orbits. */
    fun tour(accent: Int, rnd: Random = Random.Default): List<Scene> {
        // Periods of roughly 40 to 90 seconds per axis, a swell of their own, and starts spread apart.
        val orbits = (0 until 4).map { i ->
            doubleArrayOf(0.07 + rnd.nextDouble() * 0.08, 0.06 + rnd.nextDouble() * 0.08, i * 1.57 + rnd.nextDouble() * 0.6,
                i * 2.36 + rnd.nextDouble() * 0.6, 0.05 + rnd.nextDouble() * 0.06, rnd.nextDouble() * 6.28)
        }
        return (SETS + fromAccent(accent)).shuffled(rnd).map { Scene(it.name, it.base, it.glows, orbits) }
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
