package org.awana.kiosk.policy

import org.awana.kiosk.shared.KioskConfig
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/** What one pass over the policy set did, and what a trainer has to be told about. */
data class PolicyResult(
    val applied: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val permissionFailures: List<String> = emptyList(),
) {
    operator fun plus(other: PolicyResult) = PolicyResult(
        applied + other.applied,
        failures + other.failures,
        permissionFailures + other.permissionFailures,
    )
}

/**
 * Wrapper over [DevicePolicyManager] holding the whole policy set.
 *
 * Every method is idempotent and independently re-runnable, because a partial
 * provisioning failure in the field has to be recoverable from the admin screen
 * rather than by factory reset.
 */
class DevicePolicy(context: Context) {

    private val appContext = context.applicationContext
    private val dpm =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val userManager =
        appContext.getSystemService(Context.USER_SERVICE) as UserManager
    private val wifi = WifiAdmin(appContext)

    val admin: ComponentName = resolveAdminComponent(appContext)

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(appContext.packageName)

    /**
     * The whole policy set, for a device whose apps are already installed. The
     * boot receiver and the admin screen's re-apply both call this; only
     * provisioning splits it either side of the payload install.
     */
    fun applyAll(config: KioskConfig): PolicyResult =
        applyBeforeInstall(config) + applyAfterInstall(config)

    /**
     * Everything that does not need the payload installed yet, and nothing that
     * could disturb the network the payload arrives over.
     */
    fun applyBeforeInstall(config: KioskConfig): PolicyResult {
        val steps = Steps()
        steps.run("lockTask", R.string.policy_failed_lock_task) { applyLockTask(config) }
        steps.run("home", R.string.policy_failed_home) { applyHome() }
        steps.run("userRestrictions", R.string.policy_failed_user_restrictions) { applyUserRestrictions() }
        steps.run("locationAndTime", R.string.policy_failed_location_and_time) { applyLocationAndTime() }
        steps.run("ownPermissions", R.string.policy_failed_own_permissions) { applyOwnPermissions() }
        steps.run("screen", R.string.policy_failed_screen) { applyScreen(config) }
        return steps.result()
    }

    /**
     * Uninstall blocking and permission grants only take for packages that
     * exist, so they run once the payload is installed; the network
     * restrictions run last of all, when nothing else needs the provisioning
     * hotspot.
     */
    fun applyAfterInstall(config: KioskConfig): PolicyResult {
        val steps = Steps()
        steps.run("uninstallProtection", R.string.policy_failed_uninstall_protection) {
            steps.failures += applyUninstallProtection(config)
        }
        steps.run("permissions", R.string.policy_failed_permissions) {
            steps.permissionFailures += applyPermissions(config)
        }
        steps.run("wifiNetworks", R.string.policy_failed_wifi_networks) {
            steps.failures += applyWifiNetworks(config)
        }
        steps.run("networkRestrictions", R.string.policy_failed_network_restrictions) {
            applyNetworkRestrictions()
        }
        return steps.result()
    }

    // --- Lock task -----------------------------------------------------------

    fun applyLockTask(config: KioskConfig) {
        // The kiosk's own package must be present or the launcher itself cannot
        // hold the lock. Settings is there so the admin screen can reach it
        // *inside* lock task: the phone stays confined the whole time, which is
        // tighter than dropping the lock to get at it. Nothing offers it to the
        // user — the launcher lists only the deployment's apps.
        val allowlist = (
            listOf(appContext.packageName) +
                listOfNotNull(settingsPackage()) +
                config.packages.map { it.packageName }
            )
            .distinct()
            .toTypedArray()
        dpm.setLockTaskPackages(admin, allowlist)
        dpm.setLockTaskFeatures(admin, lockTaskFeatures(config))
    }

    /**
     * `SYSTEM_INFO` without `NOTIFICATIONS` is the combination that leaves the
     * battery and signal indicators visible while making the shade unreachable.
     * That is the default and the case to optimise for.
     */
    fun lockTaskFeatures(config: KioskConfig): Int {
        var features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        if (config.showNotificationShade) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
        }
        // Without this, lock task suppresses the keyguard and a PIN set in the
        // phone's settings is never asked for.
        if (config.screenLock) {
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
        }
        // Deliberately absent: OVERVIEW and BLOCK_ACTIVITY_START_IN_TASK — the
        // last would break the system share sheet and the document picker.
        return features
    }

    /**
     * Resolved rather than named: `com.android.settings` is right on AOSP and
     * wrong on plenty of the budget phones this runs on.
     */
    fun settingsPackage(): String? = appContext.packageManager
        .resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
        ?.activityInfo
        ?.packageName

    fun lockTaskPackages(): List<String> = dpm.getLockTaskPackages(admin).toList()

    fun lockTaskFeatures(): Int = dpm.getLockTaskFeatures(admin)

    // --- Home ----------------------------------------------------------------

    fun applyHome() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val launcher = launcherComponent(appContext) ?: return
        dpm.addPersistentPreferredActivity(admin, filter, launcher)
    }

    fun clearHome() {
        dpm.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
    }

    // --- Uninstall and force-stop protection ---------------------------------

    /**
     * Returns the packages it could not protect. A package that is not installed
     * yet is not one of them: the payload install runs between the two passes,
     * and the second pass is what makes the block stick.
     */
    fun applyUninstallProtection(config: KioskConfig): List<String> {
        val failures = mutableListOf<String>()
        config.packages.forEach { spec ->
            runCatching { dpm.setUninstallBlocked(admin, spec.packageName, true) }
                .onFailure {
                    Log.w(TAG, "setUninstallBlocked failed for ${spec.packageName}", it)
                    if (isInstalled(spec.packageName)) {
                        failures += appContext.getString(
                            R.string.policy_failed_uninstall_blocked,
                            spec.packageName,
                        )
                    }
                }
        }
        // No DPM API at any level touches OEM battery managers, and there is no
        // public doze exemption. Blocking force-stop and clear-data is the
        // strongest thing available here.
        val protected = (listOf(appContext.packageName) + config.packages.map { it.packageName })
            .distinct()
        dpm.setUserControlDisabledPackages(admin, protected)
        return failures
    }

    // --- User restrictions ---------------------------------------------------

    fun applyUserRestrictions() {
        NON_NETWORK_RESTRICTIONS.forEach { dpm.addUserRestriction(admin, it) }
    }

    /**
     * Applied after the provisioning payload has been fetched, so that locking
     * down Wi-Fi configuration cannot interfere with the hotspot the payload
     * arrives over.
     */
    fun applyNetworkRestrictions() {
        NETWORK_RESTRICTIONS.forEach { dpm.addUserRestriction(admin, it) }
    }

    fun hasRestriction(key: String): Boolean = userManager.hasUserRestriction(key)

    // --- Wi-Fi ---------------------------------------------------------------

    /**
     * Joins the networks the deployment ships with. A user cannot add one once
     * `DISALLOW_CONFIG_WIFI` is set, so a network missing here is a team that
     * cannot sync until someone with the admin PIN visits the device.
     */
    fun applyWifiNetworks(config: KioskConfig): List<String> {
        val saved = wifi.savedNetworks()
        return config.wifiNetworks
            .filter { it.ssid !in saved }
            .filterNot { wifi.addNetwork(it.ssid, it.passphrase) }
            .map { appContext.getString(R.string.policy_failed_wifi_network, it.ssid) }
    }

    // --- Location and time ---------------------------------------------------

    fun applyLocationAndTime() {
        dpm.setLocationEnabled(admin, true)
        dpm.setAutoTimeEnabled(admin, true)
        dpm.setAutoTimeZoneEnabled(admin, true)
    }

    // --- Permissions ---------------------------------------------------------

    /**
     * Grants this app what it needs to list saved Wi-Fi networks, which from
     * Android 10 requires location permission even for a Device Owner. The
     * admin screen shows that list; nothing here uses location for anything.
     */
    /**
     * Grants this app one of its own permissions, if it can.
     *
     * Used for the camera the moment before the scanner opens rather than at
     * provisioning time, so phones already in the field get it without being
     * set up again. False on a phone where this app is not the device owner,
     * which is the debug-only local setup.
     */
    fun grantSelf(permission: String): Boolean = runCatching {
        dpm.setPermissionGrantState(
            admin,
            appContext.packageName,
            permission,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
        )
    }.getOrDefault(false)

    fun applyOwnPermissions() {
        val granted = dpm.setPermissionGrantState(
            admin,
            appContext.packageName,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
        )
        check(granted) { "ACCESS_FINE_LOCATION was not granted to ${appContext.packageName}" }
    }

    /**
     * Pre-grants each package's runtime permissions, in the order the config
     * lists them. Returns the grants that failed, so a trainer sees them rather
     * than discovering a silent gap in the field.
     *
     * Only permissions the installed build actually requests are attempted:
     * the list was read from the APK on the trainer's phone, where a
     * `maxSdkVersion` may have kept a permission that this device's Android
     * version drops.
     */
    fun applyPermissions(config: KioskConfig): List<String> {
        dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)

        val failures = mutableListOf<String>()
        for (spec in config.packages) {
            val requested = requestedPermissions(spec.packageName) ?: continue
            for (permission in spec.permissions) {
                if (permission !in requested) continue
                val ok = runCatching {
                    dpm.setPermissionGrantState(
                        admin,
                        spec.packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }.getOrDefault(false)
                if (!ok) failures += "${spec.packageName}:$permission"
            }
        }
        return failures
    }

    private fun isInstalled(packageName: String): Boolean = requestedPermissions(packageName) != null

    /** Null when the package is not installed. */
    private fun requestedPermissions(packageName: String): Set<String>? = try {
        appContext.packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun grantState(packageName: String, permission: String): Int =
        dpm.getPermissionGrantState(admin, packageName, permission)

    // --- Screen --------------------------------------------------------------

    fun applyScreen(config: KioskConfig) {
        // Disabling only takes effect while no lockscreen password is set, so a
        // PIN someone has actually set always wins over this.
        dpm.setKeyguardDisabled(admin, !config.screenLock)
        dpm.setSystemSetting(
            admin,
            Settings.System.SCREEN_OFF_TIMEOUT,
            config.screenOffTimeoutMs.toString(),
        )
    }

    // --- Un-provisioning -----------------------------------------------------

    /**
     * Returns the device to a normal, unmanaged state and says in words what it
     * could not undo. Cannot be undone without a factory reset.
     *
     * [config] names the packages whose uninstall blocking has to be lifted;
     * without it those apps could be left undeletable after the owner is gone.
     */
    fun unprovision(config: KioskConfig?): List<String> {
        val steps = Steps()
        steps.run("home", R.string.unprovision_failed_home) { clearHome() }
        steps.run("lockTask", R.string.unprovision_failed_lock_task) {
            dpm.setLockTaskPackages(admin, emptyArray())
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
        steps.run("restrictions", R.string.unprovision_failed_restrictions) {
            (NON_NETWORK_RESTRICTIONS + NETWORK_RESTRICTIONS).forEach {
                dpm.clearUserRestriction(admin, it)
            }
        }
        config?.packages.orEmpty().forEach { spec ->
            steps.run("uninstallBlocked:${spec.packageName}", R.string.unprovision_failed_uninstall_blocked) {
                dpm.setUninstallBlocked(admin, spec.packageName, false)
            }
        }
        steps.run("userControl", R.string.unprovision_failed_uninstall_blocked) {
            dpm.setUserControlDisabledPackages(admin, emptyList())
        }
        steps.run("screen", R.string.unprovision_failed_screen) {
            dpm.setKeyguardDisabled(admin, false)
            dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_PROMPT)
        }
        // Last, because once the owner is gone none of the calls above is allowed.
        steps.run("deviceOwner", R.string.unprovision_failed_device_owner) {
            dpm.clearDeviceOwnerApp(appContext.packageName)
        }
        return steps.result().failures.distinct()
    }

    private inner class Steps {
        val applied = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val permissionFailures = mutableListOf<String>()

        /** Records the step's name, or a message a trainer can act on. */
        fun run(name: String, failureMessage: Int, body: () -> Unit) {
            try {
                body()
                applied += name
            } catch (e: Exception) {
                Log.e(TAG, "Policy step '$name' failed", e)
                failures += appContext.getString(failureMessage)
            }
        }

        fun result() = PolicyResult(applied, failures, permissionFailures)
    }

    companion object {
        private const val TAG = "DevicePolicy"

        /**
         * Set regardless of the notification shade flag. With the shade off
         * they are defence in depth; with it on they are what renders the
         * dangerous quick-settings tiles inert.
         */
        val NETWORK_RESTRICTIONS = listOf(
            UserManager.DISALLOW_AIRPLANE_MODE,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_CONFIG_WIFI,
        )

        val NON_NETWORK_RESTRICTIONS = listOf(
            UserManager.DISALLOW_CONFIG_LOCATION,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
        )

        /**
         * Deliberately not set: `DISALLOW_FACTORY_RESET` (a device that cannot
         * be recovered in the field is worse than any escape it prevents),
         * `DISALLOW_DEBUGGING_FEATURES` (keeps ADB as a recovery route), and
         * `DISALLOW_INSTALL_UNKNOWN_SOURCES` (would block third-party
         * self-updaters). `DISALLOW_APPS_CONTROL` is broader than needed and
         * implies `DISALLOW_UNINSTALL_APPS`.
         */
        val NOT_SET_BY_DESIGN = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_APPS_CONTROL,
        )

        /**
         * Finds this package's own [DeviceAdminReceiver] rather than naming it,
         * so the policy module needs no compile-time dependency on the app
         * module that declares it.
         */
        fun resolveAdminComponent(context: Context): ComponentName {
            val intent = Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED)
                .setPackage(context.packageName)
            val receiver = context.packageManager.queryBroadcastReceivers(intent, 0).firstOrNull()
                ?: error("No DeviceAdminReceiver declared in ${context.packageName}")
            return ComponentName(receiver.activityInfo.packageName, receiver.activityInfo.name)
        }

        /** This package's own HOME activity. */
        fun launcherComponent(context: Context): ComponentName? {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(context.packageName)
            val activity = context.packageManager.queryIntentActivities(intent, 0).firstOrNull()
                ?: return null
            return ComponentName(activity.activityInfo.packageName, activity.activityInfo.name)
        }
    }
}
