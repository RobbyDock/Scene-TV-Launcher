package com.robbydock.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hostile and odd input for the checks in Guard. All data here is made up. */
class GuardTest {

    // ---- names

    @Test fun emptyAndBlankNamesBecomeEmpty() {
        assertEquals("", Guard.cleanText("", 20))
        assertEquals("", Guard.cleanText("     ", 20))
        assertEquals("", Guard.cleanText("\t\n  ", 20))
    }

    @Test fun zeroWidthOnlyNameBecomesEmpty() {
        assertEquals("", Guard.cleanText("​‌‍﻿", 20))
    }

    @Test fun controlCharactersAreRemoved() {
        assertEquals("Ann", Guard.cleanText("A\u0000n\u0007n", 20))
    }

    @Test fun innerWhitespaceCollapsesAndEndsTrim() {
        assertEquals("Mary Jane", Guard.cleanText("  Mary \t\n  Jane  ", 20))
    }

    @Test fun longNameIsCutToCap() {
        val name = "a".repeat(200)
        assertEquals(20, Guard.cleanText(name, 20).length)
    }

    @Test fun emojiOnlyNameIsKeptWhole() {
        val fox = "🦊" // fox face, a surrogate pair
        assertEquals(fox + fox, Guard.cleanText("$fox $fox".replace(" ", ""), 20))
        // Cutting never leaves half a surrogate pair.
        val cut = Guard.cleanText(fox.repeat(30), 20)
        assertEquals(40, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    @Test fun rightToLeftNameIsKept() {
        val hebrew = "דנה"
        assertEquals(hebrew, Guard.cleanText(" $hebrew ", 20))
    }

    @Test fun initialIsFirstVisibleCharacter() {
        assertEquals("?", Guard.initial(""))
        assertEquals("?", Guard.initial("   "))
        assertEquals("S", Guard.initial("sam"))
        // e plus a combining accent stays one character.
        assertEquals("É", Guard.initial("émile"))
        assertEquals("🦊", Guard.initial("🦊 fox"))
    }

    @Test fun duplicateNamesGetANumber() {
        assertEquals("Sam", Guard.uniqueName("Sam", listOf("Mark")))
        assertEquals("Sam 2", Guard.uniqueName("sam", listOf("Sam")).replaceFirstChar { it.uppercase() })
        assertEquals("Sam 3", Guard.uniqueName("Sam", listOf("Sam", "SAM 2")))
        val long = "b".repeat(20)
        val unique = Guard.uniqueName(long, listOf(long))
        assertTrue(unique.length <= Limits.PROFILE_NAME)
        assertTrue(unique.endsWith(" 2"))
    }

    // ---- package names

    @Test fun packageNames() {
        assertTrue(Guard.isPackageName("com.stremio.one"))
        assertTrue(Guard.isPackageName("org.example.app_2"))
        assertFalse(Guard.isPackageName(""))
        assertFalse(Guard.isPackageName("noDots"))
        assertFalse(Guard.isPackageName("com..double"))
        assertFalse(Guard.isPackageName("1com.bad"))
        assertFalse(Guard.isPackageName("com.bad/.Activity"))
        assertFalse(Guard.isPackageName("../../etc/passwd"))
        assertFalse(Guard.isPackageName("com.x;rm -rf"))
        assertFalse(Guard.isPackageName("a." + "b".repeat(300)))
    }

    // ---- backup files

    private fun nested(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    @Test fun depthLimit() {
        assertTrue(Guard.depthOk(nested(16)))
        assertFalse(Guard.depthOk(nested(17)))
        assertFalse(Guard.depthOk(nested(100_000)))
    }

    @Test fun bracketsInsideStringsDoNotCount() {
        val text = """{"name":"${"[".repeat(50)}","q":"a\"[[[[[[[[[[[[[[[[[["}"""
        assertTrue(Guard.depthOk(text))
    }

    @Test fun backupOverOneMegabyteIsRefusedBeforeParsing() {
        assertNotNull(Guard.backupProblem(Limits.BACKUP_BYTES + 1, null))
        assertNotNull(Guard.backupProblem(Limits.BACKUP_BYTES + 1, "{}"))
    }

    @Test fun backupChecks() {
        assertNull(Guard.backupProblem(2, "{}"))
        assertNotNull(Guard.backupProblem(10, null))
        assertNotNull(Guard.backupProblem(40, nested(17)))
    }

    @Test fun hugeProfileListIsOnlyAFewChecksAway() {
        // 394,858 profiles: refused on size alone.
        val one = """{"id":"p1","name":"Kid","color":0},"""
        val bytes = one.length.toLong() * 394_858
        assertNotNull(Guard.backupProblem(bytes, null))
    }

    // ---- picker layout

    @Test fun pickerRows() {
        assertEquals(emptyList<Int>(), Guard.pickerRows(0))
        assertEquals(listOf(1), Guard.pickerRows(1))
        assertEquals(listOf(5), Guard.pickerRows(5))
        assertEquals(listOf(3, 3), Guard.pickerRows(6))
        assertEquals(listOf(4, 3), Guard.pickerRows(7))
        assertEquals(listOf(4, 4), Guard.pickerRows(8))
        // 8 profiles at the cap: no Add tile, so 8 tiles at most.
        assertEquals(8, Guard.pickerRows(Limits.PROFILES).sum())
    }

    // ---- location

    @Test fun coordinates() {
        assertTrue(Guard.latOk(51.5) && Guard.lonOk(-0.12))
        assertFalse(Guard.latOk(91.0))
        assertFalse(Guard.lonOk(-181.0))
        assertFalse(Guard.latOk(Double.NaN))
        assertFalse(Guard.lonOk(Double.POSITIVE_INFINITY))
    }
}
