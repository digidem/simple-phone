package org.awana.kiosk.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.PhoneLock
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.ProvisioningBootstrap
import org.junit.After
import org.junit.Assert.assertEquals
import org.awana.kiosk.policy.DevicePolicy
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * An empty app list is three different situations for the person holding the
 * phone, and only one of them is a problem with the deployment. This is what
 * tells them apart.
 */
@RunWith(AndroidJUnit4::class)
class HomeStateTest {

    private lateinit var context: Context

    @Before
    fun clean() {
        context = ApplicationProvider.getApplicationContext()
        wipe()
    }

    @After
    fun tidyUp() = wipe()

    private fun wipe() {
        ConfigStore(context).clear()
        File(context.filesDir, "last-report.json").delete()
        File(context.filesDir, "pending-bootstrap.json").delete()
        PhoneLock.forgetUnlocked(context)
        PhoneLock.forgetRemoved(context)
    }

    /**
     * Something on this device that actually has a launch intent. The kiosk's
     * own HOME activity does not, so it cannot stand in for a payload app.
     */
    private val launchable: String by lazy {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .map { it.packageName }
            .first { pm.getLaunchIntentForPackage(it)?.component != null }
    }

    private fun config(launcher: List<LauncherEntry>) = KioskConfig(
        deploymentId = "home-state",
        deploymentName = "Home state",
        adminPinHash = AdminPin.hash("246813"),
        packages = listOf(
            PackageSpec(launchable, "a".repeat(64)),
            PackageSpec("org.example.absent", "b".repeat(64)),
        ),
        launcher = launcher,
    )

    @Test
    fun aPhoneThatWasNeverProvisionedHasNothingToRetry() = runBlocking {
        val state = homeState(context)

        assertEquals(HomeState.NotSetUp(canSetUpAgain = false), state)
    }

    @Test
    fun aFailedSetupIsToldApartFromOneThatNeverStarted() = runBlocking {
        Provisioner(context).recordBootstrapFailure(
            "This device could not reach the trainer's phone.",
            ProvisioningBootstrap("http://192.168.43.1:8080", "a".repeat(64)),
        )

        val state = homeState(context)

        // The bootstrap was kept, so "set this phone up again" has somewhere
        // to go without another scan.
        assertEquals(HomeState.SetupUnfinished(canSetUpAgain = true), state)
    }

    @Test
    fun aFailureRecordedWithoutABootstrapOffersNoRetry() = runBlocking {
        Provisioner(context).recordBootstrapFailure("Something went wrong.")

        assertEquals(HomeState.SetupUnfinished(canSetUpAgain = false), homeState(context))
    }

    @Test
    fun removingTheLockTakesTheRetryAwayWithIt() = runBlocking {
        Provisioner(context).recordBootstrapFailure(
            "This device could not reach the trainer's phone.",
            ProvisioningBootstrap("http://192.168.43.1:8080", "a".repeat(64)),
        )

        unprovision(context)

        // Provisioning needs Device Owner, which unprovisioning gives up, so
        // the kept bootstrap must not still be offered.
        assertEquals(HomeState.SetupUnfinished(canSetUpAgain = false), homeState(context))
    }

    @Test
    fun anUnlockedPhoneSaysSoRatherThanShowingItsApps() = runBlocking {
        ConfigStore(context).save(config(listOf(LauncherEntry(launchable))))
        // The marker alone: this emulator is not the device owner, so the
        // policy half of unlocking has nothing to act on here.
        File(context.filesDir, "unlocked").writeText("")

        assertTrue(homeState(context) is HomeState.Unlocked)
    }

    @Test
    fun aPhoneWhoseLockWasRemovedSaysSoWhateverElseIsLeft() = runBlocking {
        ConfigStore(context).save(config(listOf(LauncherEntry(launchable))))
        PhoneLock.markRemoved(context)

        assertEquals(HomeState.LockRemoved, homeState(context))
    }

    @Test
    fun removingTheLockFromAPhoneItNeverOwnedIsNotRecordedAsARemoval() = runBlocking {
        // On the Device Owner emulator this would really remove the lock.
        assumeFalse(DevicePolicy(context).isDeviceOwner)
        unprovision(context)

        assertFalse(PhoneLock.isRemoved(context))
    }

    @Test
    fun aProvisionedPhoneShowingNothingIsNotAFailure() = runBlocking {
        ConfigStore(context).save(
            config(listOf(LauncherEntry(launchable, LauncherRole.HIDDEN))),
        )

        assertEquals(HomeState.NoApps, homeState(context))
    }

    @Test
    fun theHeroAndTheGridComeOutOfTheConfigsOrder() = runBlocking {
        ConfigStore(context).save(
            config(
                listOf(
                    LauncherEntry(launchable, LauncherRole.HERO, label = "Field app"),
                    LauncherEntry("org.example.absent", LauncherRole.SMALL),
                ),
            ),
        )

        val state = homeState(context) as HomeState.Apps

        assertEquals(launchable, state.hero?.packageName)
        assertEquals("Field app", state.hero?.label)
        // The second entry names a package that is not installed, so it is not
        // on the home screen however the deployment described it.
        assertTrue(state.small.isEmpty())
    }
}
