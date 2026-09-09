package app.comapeo.kiosk.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import app.comapeo.kiosk.policy.KioskConfig

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

object AppList {

    /**
     * The apps to show as icons: those listed in `visibleInLauncher` that are
     * actually installed and actually launchable.
     *
     * `packages` is deliberately wider than this, so an app can exist as a
     * share target without appearing on the home screen.
     */
    fun visible(context: Context, config: KioskConfig?): List<LaunchableApp> {
        if (config == null) return emptyList()
        val pm = context.packageManager
        return config.launcherPackages.mapNotNull { packageName ->
            val launch = pm.getLaunchIntentForPackage(packageName) ?: return@mapNotNull null
            val info = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            if (launch.component == null) return@mapNotNull null
            LaunchableApp(
                packageName = packageName,
                label = pm.getApplicationLabel(info).toString(),
                icon = pm.getApplicationIcon(info),
            )
        }
    }
}
