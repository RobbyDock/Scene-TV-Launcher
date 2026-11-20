package com.robbydock.scene

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Admin recovery codes: 8 characters with no look-alikes, shown once, stored only as a slow
 * salted hash. No Android calls.
 */
object Recovery {
    /** No 0, O, 1, I or L. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8
    const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun generate(random: SecureRandom = SecureRandom()): String =
        String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** "ABCD-EFGH" for display. */
    fun display(code: String) = code.take(4) + "-" + code.drop(4)

    /** What the person typed, upper-cased with spaces and dashes removed. Null when it can't be a code. */
    fun normalize(input: String): String? {
        val c = input.uppercase().filter { !it.isWhitespace() && it != '-' }
        return c.takeIf { it.length == LENGTH && it.all { ch -> ch in ALPHABET } }
    }

    fun newSalt(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun hash(code: String, salt: String, iterations: Int = ITERATIONS): String {
        val spec = PBEKeySpec(code.toCharArray(), salt.toByteArray(), iterations, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return key.joinToString("") { "%02x".format(it) }
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time comparison of [input] against a stored hash. */
    fun matches(input: String, salt: String, stored: String, iterations: Int = ITERATIONS): Boolean {
        if (stored.isEmpty() || salt.isEmpty()) return false
        val code = normalize(input) ?: return false
        return MessageDigest.isEqual(hash(code, salt, iterations).toByteArray(), stored.toByteArray())
    }
}
