package org.awana.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.UserManager
import androidx.test.platform.app.InstrumentationRegistry
import org.awana.kiosk.policy.DevicePolicy
import java.io.File

/**
 * The `:sample` APK the provisioning tests install, and what getting rid of it
 * again takes.
 */
object SamplePayload {

    const val PACKAGE = "org.awana.kiosk.sample"

    /** Where [DeploymentServer] serves it from, and what its package spec points at. */
    const val PATH = "/apks/$PACKAGE.apk"

    /** Copies the APK out of the test APK's assets into the app's cache. */
    fun stage(context: Context): File {
        val apk = staged(context)
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(FILE_NAME)
            .use { input -> apk.outputStream().use { input.copyTo(it) } }
        return apk
    }

    /** Safe to call from a teardown that ran before [stage] did. */
    fun discardStaged(context: Context) {
        staged(context).delete()
    }

    private fun staged(context: Context) = File(context.cacheDir, FILE_NAME)

    fun isInstalled(context: Context): Boolean =
        context.packageManager.getInstalledPackages(0).any { it.packageName == PACKAGE }

    /**
     * Uninstall protection is applied to every package in the config, so three
     * separate layers have to come off before the sample can be removed between
     * tests — which is itself the feature working.
     */
    fun remove(context: Context) {
        val policy = DevicePolicy(context)
        if (policy.isDeviceOwner) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            runCatching { dpm.setUninstallBlocked(policy.admin, PACKAGE, false) }
            runCatching { dpm.setUserControlDisabledPackages(policy.admin, emptyList()) }
            runCatching { dpm.clearUserRestriction(policy.admin, UserManager.DISALLOW_UNINSTALL_APPS) }
        }
        val output = shell("pm uninstall $PACKAGE")
        check(!isInstalled(context) || output.contains("Unknown package")) {
            "could not remove the sample payload between tests: $output"
        }
    }

    /**
     * Reads the command's output to completion. `executeShellCommand` runs
     * asynchronously, so closing the descriptor straight away kills the command
     * before it has done anything.
     */
    fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            .use { it.readBytes().decodeToString() }

    private const val FILE_NAME = "sample-payload.apk"
}
