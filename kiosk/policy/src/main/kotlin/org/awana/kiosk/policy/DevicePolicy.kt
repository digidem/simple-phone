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

    val admin: ComponentName = resolveAdminComponent(appContext)

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(appContext.packageName)

    /**
     * Applies everything except the restrictions that could interfere with the
     * provisioning network. Call [applyNetworkRestrictions] once the device no
     * longer needs the provisioning hotspot.
     */
    fun applyAll(config: KioskConfig): List<String> {
        val applied = mutableListOf<String>()
        step(applied, "lockTask") { applyLockTask(config) }
        step(applied, "home") { applyHome() }
        step(applied, "uninstallProtection") { applyUninstallProtection(config) }
        step(applied, "userRestrictions") { applyUserRestrictions() }
        step(applied, "locationAndTime") { applyLocationAndTime() }
        step(applied, "permissions") { applyPermissions(config) }
        step(applied, "screen") { applyScreen(config) }
        return applied
    }

    // --- Lock task -----------------------------------------------------------

    fun applyLockTask(config: KioskConfig) {
        // The kiosk's own package must be present or the launcher itself cannot
        // hold the lock.
        val allowlist = (listOf(appContext.packageName) + config.packages.map { it.packageName })
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
        // Deliberately absent: OVERVIEW, KEYGUARD, and
        // BLOCK_ACTIVITY_START_IN_TASK — the last would break the system share
        // sheet and the document picker.
        return features
    }

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

    fun applyUninstallProtection(config: KioskConfig) {
        config.packages.forEach { spec ->
            runCatching { dpm.setUninstallBlocked(admin, spec.packageName, true) }
                .onFailure { Log.w(TAG, "setUninstallBlocked failed for ${spec.packageName}", it) }
        }
        // No DPM API at any level touches OEM battery managers, and there is no
        // public doze exemption. Blocking force-stop and clear-data is the
        // strongest thing available here.
        val protected = (listOf(appContext.packageName) + config.packages.map { it.packageName })
            .distinct()
        dpm.setUserControlDisabledPackages(admin, protected)
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

    // --- Location and time ---------------------------------------------------

    fun applyLocationAndTime() {
        dpm.setLocationEnabled(admin, true)
        dpm.setAutoTimeEnabled(admin, true)
        dpm.setAutoTimeZoneEnabled(admin, true)
    }

    // --- Permissions ---------------------------------------------------------

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
        // Only takes effect while no lockscreen password is set, which is the
        // provisioned state.
        runCatching { dpm.setKeyguardDisabled(admin, true) }
            .onFailure { Log.w(TAG, "setKeyguardDisabled failed", it) }
        runCatching {
            dpm.setSystemSetting(
                admin,
                Settings.System.SCREEN_OFF_TIMEOUT,
                config.screenOffTimeoutMs.toString(),
            )
        }.onFailure { Log.w(TAG, "SCREEN_OFF_TIMEOUT failed", it) }
    }

    // --- Un-provisioning -----------------------------------------------------

    /**
     * Returns the device to a normal, unmanaged state. Cannot be undone without
     * a factory reset.
     */
    fun unprovision() {
        runCatching { clearHome() }
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
        (NON_NETWORK_RESTRICTIONS + NETWORK_RESTRICTIONS).forEach {
            runCatching { dpm.clearUserRestriction(admin, it) }
        }
        runCatching { dpm.setUserControlDisabledPackages(admin, emptyList()) }
        runCatching { dpm.setKeyguardDisabled(admin, false) }
        runCatching { dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_PROMPT) }
        dpm.clearDeviceOwnerApp(appContext.packageName)
    }

    private inline fun step(into: MutableList<String>, name: String, body: () -> Unit) {
        try {
            body()
            into += name
        } catch (e: Exception) {
            Log.e(TAG, "Policy step '$name' failed", e)
        }
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
