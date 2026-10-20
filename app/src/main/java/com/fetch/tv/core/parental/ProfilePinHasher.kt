package com.fetch.tv.core.parental

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2 hash of a profile PIN, stored as `v1:<iterations>:<saltHex>:<hashHex>`.
 *
 * A 4-digit PIN has a tiny keyspace, so the hash only keeps the PIN out of plain sight on
 * disk; brute force is slowed by [PinAttemptPolicy], not by the iteration count. The count
 * is kept low enough to verify quickly on low-end TV hardware.
 */
object ProfilePinHasher {
    private const val VERSION = "v1"
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val DEFAULT_ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    fun hash(pin: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(pin, salt, iterations)
        return "$VERSION:$iterations:${salt.toHex()}:${derived.toHex()}"
    }

    fun verify(pin: String, encoded: String?): Boolean {
        val parts = encoded?.split(":") ?: return false
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = parts[2].hexToBytesOrNull() ?: return false
        val expected = parts[3].hexToBytesOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
    }
}

/**
 * Lockout after repeated wrong PINs: [FREE_ATTEMPTS] tries, then a delay that doubles with
 * each further failure, capped at [MAX_LOCKOUT_MS].
 */
object PinAttemptPolicy {
    const val FREE_ATTEMPTS = 5
    const val BASE_LOCKOUT_MS = 30_000L
    const val MAX_LOCKOUT_MS = 15 * 60_000L

    fun lockoutAfterFailures(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val step = (failures - FREE_ATTEMPTS).coerceAtMost(10)
        return (BASE_LOCKOUT_MS shl step).coerceAtMost(MAX_LOCKOUT_MS)
    }

    fun retryAfterSeconds(lockedUntilMs: Long, nowMs: Long): Int {
        val remaining = lockedUntilMs - nowMs
        return if (remaining <= 0) 0 else ((remaining + 999) / 1000).toInt()
    }
}
