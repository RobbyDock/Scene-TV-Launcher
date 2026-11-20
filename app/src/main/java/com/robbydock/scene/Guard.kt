package com.robbydock.scene

import java.text.BreakIterator

/** Caps for every collection and string Scene stores. Enforced where data enters. */
object Limits {
    const val PROFILES = 8
    const val PROFILE_NAME = 20
    const val PIN_LABEL = 60
    const val HIDDEN = 1000
    const val CW_HIDDEN = 500
    const val APP_ORDER = 1000
    const val CITY = 80
    const val ID_TEXT = 200
    const val BACKUP_BYTES = 1L shl 20
    const val JSON_DEPTH = 16
    const val PACKAGE = 255
}

/** Input checks with no Android dependencies. */
object Guard {
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private const val ZWJ = 0x200D

    fun isPackageName(s: String) = s.length <= Limits.PACKAGE && PACKAGE.matches(s)

    /**
     * Cleans user text: drops control and invisible format characters (keeping the joiner used
     * inside emoji), collapses runs of whitespace, trims, and cuts to [max] visible characters
     * without splitting one.
     */
    fun cleanText(raw: String, max: Int): String {
        val sb = StringBuilder(raw.length)
        var space = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { space = true; continue }
            if (type == Character.CONTROL.toInt()) continue
            if (type == Character.FORMAT.toInt() && cp != ZWJ) continue
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.appendCodePoint(cp)
        }
        // A joiner alone is invisible.
        val text = if (sb.codePoints().allMatch { it == ZWJ }) "" else sb.toString()
        return takeGraphemes(text, max).trim()
    }

    /** First [n] user-visible characters of [s]. */
    fun takeGraphemes(s: String, n: Int): String {
        if (n <= 0) return ""
        val it = BreakIterator.getCharacterInstance().apply { setText(s) }
        var count = 0
        var end = it.first()
        while (count < n) {
            val next = it.next()
            if (next == BreakIterator.DONE) return s
            end = next
            count++
        }
        return s.substring(0, end)
    }

    /** The first visible character, upper-cased when it is a letter. Keeps an emoji whole. */
    fun initial(name: String): String {
        val g = takeGraphemes(name.trim(), 1)
        return if (g.isEmpty()) "?" else g.uppercase()
    }

    /** [name], or "[name] 2", "[name] 3" and so on when the name is taken, ignoring case. */
    fun uniqueName(name: String, taken: Collection<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        var n = 2
        while (true) {
            val suffix = " $n"
            val cand = takeGraphemes(name, Limits.PROFILE_NAME - suffix.length) + suffix
            if (cand.lowercase() !in lower) return cand
            n++
        }
    }

    /**
     * True when JSON text nests no deeper than [max] objects and arrays. Scans characters only.
     */
    fun depthOk(text: String, max: Int = Limits.JSON_DEPTH): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > max) return false
                '}', ']' -> depth--
            }
        }
        return true
    }

    /** Why a backup file can't be read, or null when its size and shape are fine to parse. */
    fun backupProblem(bytes: Long, text: String?): String? = when {
        bytes > Limits.BACKUP_BYTES -> "The backup file is larger than 1 MB."
        text == null -> "The backup file couldn't be read."
        !depthOk(text) -> "The backup file isn't a Scene backup."
        else -> null
    }

    /** Picker rows for [items] tiles, the Add tile included: one row up to 5, then two. */
    fun pickerRows(items: Int): List<Int> = when {
        items <= 0 -> emptyList()
        items <= 5 -> listOf(items)
        else -> listOf((items + 1) / 2, items / 2)
    }

    fun latOk(v: Double) = v.isFinite() && v in -90.0..90.0
    fun lonOk(v: Double) = v.isFinite() && v in -180.0..180.0
}
