package org.awana.kiosk.launcher

import org.awana.kiosk.shared.KioskConfig
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.awana.kiosk.policy.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

object AppList {

    private const val ICON_PX = 192

    /**
     * The apps to show as icons: those listed in `visibleInLauncher` that are
     * actually installed and actually launchable.
     *
     * `packages` is deliberately wider than this, so an app can exist as a
     * share target without appearing on the home screen.
     *
     * Reading the config and rasterising icons are both slow enough to ANR the
     * launcher if done during composition, so this is a suspend function and
     * its result is held in state.
     */
    suspend fun load(context: Context): List<LaunchableApp> = withContext(Dispatchers.IO) {
        visible(context, ConfigStore(context).load())
    }

    suspend fun visible(context: Context, config: KioskConfig?): List<LaunchableApp> =
        withContext(Dispatchers.IO) {
            if (config == null) return@withContext emptyList()
            val pm = context.packageManager
            config.launcherPackages.mapNotNull { packageName ->
                val launch = pm.getLaunchIntentForPackage(packageName) ?: return@mapNotNull null
                if (launch.component == null) return@mapNotNull null
                val info = try {
                    pm.getApplicationInfo(packageName, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }
                LaunchableApp(
                    packageName = packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
                )
            }
        }
}
