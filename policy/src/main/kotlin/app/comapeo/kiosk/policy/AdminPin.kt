package app.comapeo.kiosk.policy

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashing and verification for the per-deployment admin PIN.
 *
 * A 4-6 digit PIN has too little entropy for the KDF to matter much against an
 * attacker holding the file, which would already require root. The rate limiter
 * in [PinGate] is the control that actually protects the device.
 */
object AdminPin {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val PREFIX = "pbkdf2_sha256"

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

/**
 * Rate limiter for PIN entry. Backs off geometrically after
 * [FREE_ATTEMPTS] failures and holds the lockout across process death.
 *
 * Deliberately uses elapsed realtime rather than wall clock: the DPC sets
 * `setAutoTimeEnabled`, so wall clock can jump backwards on first sync and
 * would hand an attacker a free reset.
 *
 * A reboot does clear the running timer, since elapsed realtime restarts. It
 * does not clear the failure count, so reboot-spamming still escalates into the
 * long backoffs within a few attempts.
 */
class PinGate(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("admin-pin", Context.MODE_PRIVATE)

    /** Milliseconds until another attempt is allowed; 0 when unlocked. */
    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        val now = SystemClock.elapsedRealtime()
        // Elapsed realtime restarts at boot, so a deadline set before the last
        // reboot reads as a far-future value it never actually was.
        if (now < prefs.getLong(KEY_SET_AT, 0L)) return 0L
        return (until - now).coerceAtLeast(0L)
    }

    fun check(pin: String, encoded: String): Boolean {
        if (lockoutRemainingMs() > 0) return false
        return if (AdminPin.verify(pin, encoded)) {
            prefs.edit().remove(KEY_FAILURES).remove(KEY_LOCKED_UNTIL).apply()
            true
        } else {
            recordFailure()
            false
        }
    }

    fun failureCount(): Int = prefs.getInt(KEY_FAILURES, 0)

    private fun recordFailure() {
        val failures = failureCount() + 1
        val editor = prefs.edit().putInt(KEY_FAILURES, failures)
        if (failures > FREE_ATTEMPTS) {
            val steps = failures - FREE_ATTEMPTS
            val backoff = (BASE_BACKOFF_MS shl (steps - 1).coerceAtMost(MAX_SHIFT))
                .coerceAtMost(MAX_BACKOFF_MS)
            val now = SystemClock.elapsedRealtime()
            editor.putLong(KEY_LOCKED_UNTIL, now + backoff).putLong(KEY_SET_AT, now)
        }
        editor.apply()
    }

    companion object {
        private const val KEY_FAILURES = "failures"
        private const val KEY_LOCKED_UNTIL = "locked_until"
        private const val KEY_SET_AT = "locked_set_at"

        const val FREE_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 15_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L
        private const val MAX_SHIFT = 8
    }
}
