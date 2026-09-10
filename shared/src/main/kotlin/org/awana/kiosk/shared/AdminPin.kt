package org.awana.kiosk.shared

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashing and verification for the per-deployment admin PIN. The provision app
 * hashes; the kiosk verifies. Changing the encoding strands every device
 * already in the field, which is why both sides compile this one file.
 *
 * A 4-6 digit PIN has too little entropy for the KDF to matter much against an
 * attacker holding the file, which would already require root. The kiosk's
 * rate limiter is the control that actually protects the device.
 */
object AdminPin {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val PREFIX = "pbkdf2_sha256"

    const val MIN_LENGTH = 4

    /** Encodes as `pbkdf2_sha256$iterations$saltB64$hashB64`. */
    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(pin, salt, ITERATIONS)
        return listOf(PREFIX, ITERATIONS.toString(), salt.b64(), derived.b64()).joinToString("$")
    }

    fun verify(pin: String, encoded: String): Boolean {
        val parts = encoded.split("$")
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = parts[2].unB64() ?: return false
        val expected = parts[3].unB64() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance(ALGORITHM)
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS))
            .encoded

    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.unB64(): ByteArray? =
        try {
            Base64.decode(this, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }
}
