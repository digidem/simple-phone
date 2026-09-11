package org.awana.kiosk.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole

data class LaunchableApp(
    val packageName: String,
    val role: LauncherRole,
    val label: String,
    /** Only shown for [LauncherRole.HERO]. */
    val subtitle: String?,
    val icon: ImageBitmap,
)

object AppList {

    private const val TAG = "AppList"
    private const val ICON_PX = 192

    /**
     * The apps to show on the home screen: the config's launcher entries that
     * are actually installed and actually launchable, in the order the
     * deployment set.
     *
     * `packages` is deliberately wider than this, so an app can exist as a
     * share target without appearing on the home screen.
     *
     * Reading the config and rasterising icons are both slow enough to ANR the
     * launcher if done during composition, so this is a suspend function and
     * its result is held in state.
     */
    suspend fun visible(context: Context, config: KioskConfig?): List<LaunchableApp> =
        withContext(Dispatchers.IO) {
            if (config == null) return@withContext emptyList()
            val pm = context.packageManager
            config.launcherEntries
                .filter { it.role != LauncherRole.HIDDEN }
                .mapNotNull { entry -> resolve(pm, entry) }
        }

    private fun resolve(pm: PackageManager, entry: LauncherEntry): LaunchableApp? {
        val launch = pm.getLaunchIntentForPackage(entry.packageName) ?: return null
        if (launch.component == null) return null
        val info = try {
            pm.getApplicationInfo(entry.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        return LaunchableApp(
            packageName = entry.packageName,
            role = entry.role,
            label = entry.label?.takeIf { it.isNotBlank() }
                ?: pm.getApplicationLabel(info).toString(),
            subtitle = entry.subtitle?.takeIf { it.isNotBlank() },
            icon = entry.icon() ?: pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
        )
    }

    /** A deployment's own icon, or null to fall back to the app's — a bad one must not lose the app. */
    private fun LauncherEntry.icon(): ImageBitmap? {
        val encoded = iconPng ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Icon for $packageName is not readable", e)
            null
        }
    }
}
