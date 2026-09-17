package org.awana.kiosk.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.policy.Provisioning
import org.awana.kiosk.shared.LauncherRole

/**
 * Works out which of the four home screens this phone should be showing.
 *
 * An empty app list on its own cannot tell the three failure cases apart, so
 * this reads the last setup report as well as the config: a phone with no
 * config that recorded a failure is a setup that broke, and one that recorded
 * nothing was never set up at all.
 */
suspend fun homeState(context: Context): HomeState = withContext(Dispatchers.IO) {
    val config = ConfigStore(context).load()
    val provisioner = Provisioner(context)

    if (config == null) {
        val canSetUpAgain = provisioner.pendingBootstrap() != null
        val recordedFailure = provisioner.lastReport()?.failures?.isNotEmpty() == true
        return@withContext if (recordedFailure) {
            HomeState.SetupUnfinished(canSetUpAgain)
        } else {
            HomeState.NotSetUp(canSetUpAgain)
        }
    }

    val apps = AppList.visible(context, config)
    if (apps.isEmpty()) {
        HomeState.NoApps
    } else {
        HomeState.Apps(
            hero = apps.firstOrNull { it.role == LauncherRole.HERO },
            small = apps.filter { it.role == LauncherRole.SMALL },
        )
    }
}

fun setUpAgain(context: Context): Boolean = Provisioning.setUpAgain(context)

/**
 * Clears the config whatever happens: leaving it behind after the lock is
 * partly gone puts the device in a state neither the launcher nor a trainer can
 * make sense of.
 */
suspend fun unprovision(context: Context): List<String> = withContext(Dispatchers.Default) {
    val store = ConfigStore(context)
    val config = store.load()
    val failures = try {
        DevicePolicy(context).unprovision(config)
    } catch (e: Exception) {
        listOf(context.getString(R.string.admin_unprovision_failed))
    } finally {
        store.clear()
        // Provisioning needs Device Owner, which this just gave up, so the kept
        // bootstrap can no longer be re-run: offering it would be a dead button.
        Provisioner(context).clearPendingBootstrap()
    }
    failures
}
