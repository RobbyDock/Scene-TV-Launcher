package com.robbydock.scene

/**
 * QR code encoder for short text: version 1 (21 by 21), error correction M, alphanumeric mode,
 * up to 20 characters. Follows ISO/IEC 18004. No Android calls.
 */
object Qr {
    private const val SIZE = 21
    private const val DATA_CODEWORDS = 16
    private const val EC_CODEWORDS = 10
    private const val MAX_CHARS = 20
    private const val CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    /** True when [text] fits this encoder. */
    fun fits(text: String) = text.length in 1..MAX_CHARS && text.all { it in CHARSET }

    /** Dark modules as [row][column], without the quiet zone. */
    fun encode(text: String): Array<BooleanArray> {
        require(fits(text)) { "Text doesn't fit a version 1 alphanumeric QR code" }
        val data = dataCodewords(text)
        val all = data + reedSolomon(data, EC_CODEWORDS)
        var best: Array<BooleanArray>? = null
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val m = build(all, mask)
            val score = penalty(m)
            if (score < bestScore) { bestScore = score; best = m }
        }
        return best!!
    }

    // ---- data

    private fun dataCodewords(text: String): IntArray {
        val bits = ArrayList<Boolean>()
        fun put(value: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((value ushr i) and 1 == 1) }
        put(0b0010, 4)
        put(text.length, 9)
        var i = 0
        while (i + 1 < text.length) {
            put(CHARSET.indexOf(text[i]) * 45 + CHARSET.indexOf(text[i + 1]), 11)
            i += 2
        }
        if (i < text.length) put(CHARSET.indexOf(text[i]), 6)
        val capacity = DATA_CODEWORDS * 8
        put(0, minOf(4, capacity - bits.size))
        while (bits.size % 8 != 0) bits.add(false)
        val out = IntArray(DATA_CODEWORDS)
        for (b in 0 until bits.size / 8) {
            var v = 0
            for (k in 0 until 8) v = (v shl 1) or (if (bits[b * 8 + k]) 1 else 0)
            out[b] = v
        }
        var pad = 0xEC
        for (b in bits.size / 8 until DATA_CODEWORDS) { out[b] = pad; pad = if (pad == 0xEC) 0x11 else 0xEC }
        return out
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun reedSolomon(data: IntArray, degree: Int): IntArray {
        val div = IntArray(degree).also { it[degree - 1] = 1 }
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                div[j] = gfMul(div[j], root)
                if (j + 1 < degree) div[j] = div[j] xor div[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        val rem = IntArray(degree)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, degree - 1)
            rem[degree - 1] = 0
            for (k in 0 until degree) rem[k] = rem[k] xor gfMul(div[k], factor)
        }
        return rem
    }

    // ---- matrix

    private fun build(codewords: IntArray, mask: Int): Array<BooleanArray> {
        val m = Array(SIZE) { BooleanArray(SIZE) }
        val fixed = Array(SIZE) { BooleanArray(SIZE) }
        fun set(row: Int, col: Int, dark: Boolean) { m[row][col] = dark; fixed[row][col] = true }

        for (i in 0 until SIZE) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
        for ((r0, c0) in listOf(3 to 3, 3 to SIZE - 4, SIZE - 4 to 3)) {
            for (dr in -4..4) for (dc in -4..4) {
                val r = r0 + dr; val c = c0 + dc
                if (r !in 0 until SIZE || c !in 0 until SIZE) continue
                val d = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                set(r, c, d != 2 && d != 4)
            }
        }
        drawFormat(m, fixed, mask)

        var i = 0
        val total = codewords.size * 8
        var right = SIZE - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until SIZE) {
                for (j in 0 until 2) {
                    val col = right - j
                    val upward = ((right + 1) and 2) == 0
                    val row = if (upward) SIZE - 1 - vert else vert
                    if (!fixed[row][col] && i < total) {
                        m[row][col] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                        i++
                    }
                }
            }
            right -= 2
        }
        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            if (!fixed[r][c] && maskBit(mask, r, c)) m[r][c] = !m[r][c]
        }
        return m
    }

    private fun maskBit(mask: Int, r: Int, c: Int) = when (mask) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> (r / 2 + c / 3) % 2 == 0
        5 -> r * c % 2 + r * c % 3 == 0
        6 -> (r * c % 2 + r * c % 3) % 2 == 0
        else -> ((r + c) % 2 + r * c % 3) % 2 == 0
    }

    /** Format bits for level M and [mask], with BCH error correction, in both copies. */
    private fun drawFormat(m: Array<BooleanArray>, fixed: Array<BooleanArray>, mask: Int) {
        val data = (0b00 shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 == 1
        // x is the column and y the row.
        fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; fixed[y][x] = true }
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        for (i in 0 until 8) set(SIZE - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, SIZE - 15 + i, bit(i))
        set(8, SIZE - 8, true)
    }

    /** A simplified version of the standard's mask penalty: runs, 2 by 2 blocks and dark balance. */
    private fun penalty(m: Array<BooleanArray>): Int {
        var score = 0
        for (line in 0 until SIZE) {
            for (horizontal in listOf(true, false)) {
                var run = 1
                for (k in 1 until SIZE) {
                    val a = if (horizontal) m[line][k] else m[k][line]
                    val b = if (horizontal) m[line][k - 1] else m[k - 1][line]
                    if (a == b) { run++; if (run == 5) score += 3 else if (run > 5) score++ } else run = 1
                }
            }
        }
        for (r in 0 until SIZE - 1) for (c in 0 until SIZE - 1) {
            val v = m[r][c]
            if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) score += 3
        }
        val dark = m.sumOf { row -> row.count { it } }
        val percent = dark * 100 / (SIZE * SIZE)
        score += kotlin.math.abs(percent - 50) / 5 * 10
        return score
    }
}
