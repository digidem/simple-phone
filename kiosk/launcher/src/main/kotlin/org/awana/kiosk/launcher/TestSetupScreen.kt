package org.awana.kiosk.launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.PackageSpec

/**
 * Writes a deployment out of apps already on this phone, so the launcher and
 * the admin screens can be used without a spare handset to factory reset.
 *
 * Debug builds only, and it does not pretend otherwise: nothing here makes the
 * app Device Owner, so there is no lock task, no policy and no uninstall
 * block — only the parts that do not need them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestSetupScreen(onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val installed by produceState(initialValue = emptyList<InstalledApp>()) {
        value = withContext(Dispatchers.IO) { launchableApps(context) }
    }
    var chosen by remember { mutableStateOf(emptyList<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.test_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = chosen.isNotEmpty(),
                        onClick = {
                            ConfigStore(context).save(configOf(chosen))
                            onDone()
                        },
                        modifier = Modifier.testTag(TAG_TEST_SETUP_SAVE),
                    ) { Text(stringResource(R.string.test_setup_save)) }
                },
            )
        },
        modifier = Modifier.fillMaxSize().testTag(TAG_TEST_SETUP),
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.test_setup_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.test_setup_pin, TEST_PIN),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(installed, key = { it.packageName }) { app ->
                val at = chosen.indexOf(app.packageName)
                ListItem(
                    leadingContent = {
                        Icon(
                            bitmap = app.icon,
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(app.packageName) },
                    trailingContent = {
                        when (at) {
                            -1 -> Unit
                            0 -> RoleChip(R.string.entry_role_hero)
                            else -> RoleChip(R.string.entry_role_small)
                        }
                    },
                    modifier = Modifier
                        .clickableRow {
                            chosen = if (at >= 0) {
                                chosen - app.packageName
                            } else {
                                chosen + app.packageName
                            }
                        }
                        .testTag("test-app-${app.packageName}"),
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RoleChip(label: Int) {
    SuggestionChip(onClick = {}, enabled = false, label = { Text(stringResource(label)) })
}

private fun configOf(chosen: List<String>) = KioskConfig(
    deploymentId = "local-test",
    deploymentName = "Local test",
    adminPinHash = AdminPin.hash(TEST_PIN),
    // No server, so nothing is downloaded and no certificate is ever checked.
    serverUrl = null,
    packages = chosen.map { PackageSpec(packageName = it, certSha256 = "") },
    launcher = chosen.mapIndexed { index, packageName ->
        LauncherEntry(
            packageName = packageName,
            role = if (index == 0) LauncherRole.HERO else LauncherRole.SMALL,
        )
    },
)

data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap)

private fun launchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .filter { pm.getLaunchIntentForPackage(it.packageName)?.component != null }
        .filterNot { it.packageName == context.packageName }
        .map {
            InstalledApp(
                packageName = it.packageName,
                label = pm.getApplicationLabel(it).toString(),
                icon = pm.getApplicationIcon(it).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** Only a build someone can attach a debugger to offers this. */
fun canSetUpForTesting(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable { onClick() }

private const val ICON_PX = 120

/** Stated on the screen, because nobody should have to read the source for it. */
const val TEST_PIN = "246813"

const val TAG_TEST_SETUP = "test-setup"
const val TAG_TEST_SETUP_SAVE = "test-setup-save"
