package org.awana.kiosk.policy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The two ways out of the lock, and the way back from the first.
 *
 * Unlocking keeps this app the device owner, so locking again is one call and
 * needs no trainer's phone. Removing the lock gives ownership up, and Android
 * only hands it back during the setup of a factory-reset phone.
 *
 * Both are recorded on disk: an unlocked phone has to stay unlocked across a
 * reboot while someone is fixing it, and the home screen has to know which of
 * the two it is looking at.
 */
object PhoneLock {

    private const val UNLOCKED = "unlocked"
    private const val REMOVED = "lock-removed"

    fun isUnlocked(context: Context): Boolean = File(context.filesDir, UNLOCKED).exists()

    fun isRemoved(context: Context): Boolean = File(context.filesDir, REMOVED).exists()

    /** Returns what could not be lifted, in words; empty when the phone is fully open. */
    suspend fun unlock(context: Context): List<String> = withContext(Dispatchers.Default) {
        File(context.filesDir, UNLOCKED).writeText("")
        DevicePolicy(context).unlock(ConfigStore(context).load())
    }

    /**
     * Puts the whole lock back from the config on the phone. The caller still
     * has to enter lock task: that needs an activity in front.
     */
    suspend fun lock(context: Context): List<String> = withContext(Dispatchers.Default) {
        forgetUnlocked(context)
        val config = ConfigStore(context).load()
            ?: return@withContext listOf(context.getString(R.string.lock_no_config))
        val result = DevicePolicy(context).applyAll(config)
        result.failures + result.permissionFailures
    }

    /** Called when the whole policy is applied some other way, such as an update. */
    fun forgetUnlocked(context: Context) {
        File(context.filesDir, UNLOCKED).delete()
    }

    fun markRemoved(context: Context) {
        forgetUnlocked(context)
        File(context.filesDir, REMOVED).writeText("")
    }

    /** A new setup is the one thing that can follow a removal. */
    fun forgetRemoved(context: Context) {
        File(context.filesDir, REMOVED).delete()
    }
}
