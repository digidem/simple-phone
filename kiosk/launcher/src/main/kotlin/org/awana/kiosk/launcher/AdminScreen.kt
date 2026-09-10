package org.awana.kiosk.launcher

import org.awana.kiosk.shared.KioskConfig
import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.LockTaskBreakService
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.policy.SideloadFromUrl
import org.awana.kiosk.policy.WifiAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page { Menu, DeviceInfo, VisibleApps, ChangePin, Wifi, InstallUrl }

/**
 * A plain list behind the PIN. Every destructive action states its consequence
 * in words before it happens.
 */
@Composable
fun AdminScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.Menu) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (page) {
            Page.Menu -> AdminMenu(
                onNavigate = { page = it },
                onDone = onDone,
            )

            Page.DeviceInfo -> DeviceInfoPage(onBack = { page = Page.Menu })
            Page.VisibleApps -> VisibleAppsPage(onBack = { page = Page.Menu })
            Page.ChangePin -> ChangePinPage(onBack = { page = Page.Menu })
            Page.Wifi -> WifiPage(onBack = { page = Page.Menu })
            Page.InstallUrl -> InstallUrlPage(onBack = { page = Page.Menu })
        }
    }
}

@Composable
private fun AdminMenu(onNavigate: (Page) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmUnprovision by remember { mutableStateOf(false) }
    var confirmUnlock by remember { mutableStateOf(false) }

    AdminPage(title = stringResource(R.string.admin_title), onBack = onDone, testTag = TAG_ADMIN_MENU) {
        item { AdminRow(R.string.admin_device_info, TAG_ROW_DEVICE_INFO) { onNavigate(Page.DeviceInfo) } }
        item { AdminRow(R.string.admin_visible_apps, TAG_ROW_VISIBLE_APPS) { onNavigate(Page.VisibleApps) } }
        item { AdminRow(R.string.admin_change_pin, TAG_ROW_CHANGE_PIN) { onNavigate(Page.ChangePin) } }
        item { AdminRow(R.string.admin_wifi, TAG_ROW_WIFI) { onNavigate(Page.Wifi) } }
        item { HorizontalDivider() }

        item {
            AdminRow(R.string.admin_reapply_policy, TAG_ROW_REAPPLY) {
                scope.launch {
                    busy = context.getString(R.string.admin_reapply_policy)
                    result = reapplyPolicy(context)
                    busy = null
                }
            }
        }
        item { AdminRow(R.string.admin_install_url, TAG_ROW_INSTALL_URL) { onNavigate(Page.InstallUrl) } }
        item { HorizontalDivider() }

        item {
            AdminRow(R.string.admin_unlock_temporarily, TAG_ROW_UNLOCK) { confirmUnlock = true }
        }
        item {
            AdminRow(R.string.admin_unprovision, TAG_ROW_UNPROVISION, destructive = true) { confirmUnprovision = true }
        }
    }

    busy?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            text = { Text(stringResource(R.string.admin_working, label)) },
            confirmButton = {},
            modifier = Modifier.testTag(TAG_DIALOG_BUSY),
        )
    }
    result?.let { Info(text = it, onDismiss = { result = null }) }

    if (confirmUnlock) {
        Confirm(
            title = stringResource(R.string.admin_unlock_temporarily),
            body = stringResource(R.string.admin_unlock_explain),
            confirmLabel = stringResource(R.string.action_unlock),
            onConfirm = {
                confirmUnlock = false
                (context as? Activity)?.stopLockTask()
                LockTaskBreakService.start(context)
                onDone()
            },
            onDismiss = { confirmUnlock = false },
        )
    }

    if (confirmUnprovision) {
        Confirm(
            title = stringResource(R.string.admin_unprovision),
            body = stringResource(R.string.admin_unprovision_explain),
            confirmLabel = stringResource(R.string.action_unprovision),
            destructive = true,
            onConfirm = {
                confirmUnprovision = false
                (context as? Activity)?.stopLockTask()
                DevicePolicy(context).unprovision()
                ConfigStore(context).clear()
                onDone()
            },
            onDismiss = { confirmUnprovision = false },
        )
    }
}

private suspend fun reapplyPolicy(context: Context): String = withContext(Dispatchers.Default) {
    val config = ConfigStore(context).load()
        ?: return@withContext context.getString(R.string.admin_no_config)
    val policy = DevicePolicy(context)
    if (!policy.isDeviceOwner) {
        return@withContext context.getString(R.string.admin_not_device_owner)
    }
    val applied = policy.applyAll(config)
    policy.applyNetworkRestrictions()
    val failures = policy.applyPermissions(config)
    if (failures.isEmpty()) {
        context.getString(R.string.admin_reapply_ok, applied.size)
    } else {
        context.getString(R.string.admin_reapply_partial, failures.joinToString(", "))
    }
}

// --- Pages ---------------------------------------------------------------

@Composable
private fun DeviceInfoPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val config = remember { ConfigStore(context).load() }
    val policy = remember { DevicePolicy(context) }
    val lastReport = remember { Provisioner(context).lastReport() }

    val rows = remember {
        buildList {
            add(R.string.info_manufacturer to android.os.Build.MANUFACTURER)
            add(R.string.info_model to android.os.Build.MODEL)
            add(R.string.info_android to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            add(R.string.info_kiosk_version to kioskVersion(context))
            add(R.string.info_build_variant to (lastReport?.buildVariant ?: "unknown"))
            config?.packages?.forEach { spec ->
                add(R.string.info_app_version to "${appLabel(context, spec.packageName)}: ${installedVersion(context, spec.packageName)}")
            }
            add(R.string.info_deployment to (config?.deploymentName ?: "—"))
            add(R.string.info_deployment_id to (config?.deploymentId ?: "—"))
            add(R.string.info_device_owner to policy.isDeviceOwner.toString())
            lastReport?.hostileOem?.let { add(R.string.info_hostile_oem to it) }
            lastReport?.failures?.takeIf { it.isNotEmpty() }?.let {
                add(R.string.info_last_failures to it.joinToString("\n"))
            }
        }
    }

    AdminPage(stringResource(R.string.admin_device_info), onBack, TAG_ADMIN_DEVICE_INFO) {
        items(rows) { (labelRes, value) ->
            ListItem(
                headlineContent = { Text(stringResource(labelRes)) },
                supportingContent = { Text(value) },
            )
        }
    }
}

@Composable
private fun VisibleAppsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    var config by remember { mutableStateOf(store.load()) }

    AdminPage(stringResource(R.string.admin_visible_apps), onBack, TAG_ADMIN_VISIBLE_APPS) {
        val packages = config?.packages.orEmpty()
        if (packages.isEmpty()) {
            item { ListItem(headlineContent = { Text(stringResource(R.string.admin_no_config)) }) }
        }
        items(packages) { spec ->
            val visible = config?.visibleInLauncher?.contains(spec.packageName) == true
            ListItem(
                headlineContent = { Text(appLabel(context, spec.packageName)) },
                supportingContent = { Text(spec.packageName) },
                trailingContent = {
                    Switch(
                        checked = visible,
                        onCheckedChange = { wanted ->
                            config = store.update { current ->
                                val next = if (wanted) {
                                    current.visibleInLauncher + spec.packageName
                                } else {
                                    current.visibleInLauncher - spec.packageName
                                }
                                current.copy(visibleInLauncher = next.distinct())
                            }
                        },
                        modifier = Modifier.testTag("visible-${spec.packageName}"),
                    )
                },
            )
        }
    }
}

@Composable
private fun ChangePinPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    var message by remember { mutableStateOf<String?>(null) }

    AdminPage(stringResource(R.string.admin_change_pin), onBack, TAG_ADMIN_CHANGE_PIN) {
        item {
            NewPinFields(
                onSubmit = { pin ->
                    store.update { it.copy(adminPinHash = org.awana.kiosk.shared.AdminPin.hash(pin)) }
                    message = context.getString(R.string.pin_changed)
                },
            )
        }
    }
    message?.let { Info(it) { message = null; onBack() } }
}

@Composable
private fun WifiPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val wifi = remember { WifiAdmin(context) }
    var enabled by remember { mutableStateOf(wifi.isEnabled) }
    var message by remember { mutableStateOf<String?>(null) }

    AdminPage(stringResource(R.string.admin_wifi), onBack, TAG_ADMIN_WIFI) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.wifi_enabled)) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            wifi.setEnabled(it)
                            enabled = it
                        },
                        modifier = Modifier.testTag(TAG_WIFI_SWITCH),
                    )
                },
            )
        }
        item { HorizontalDivider() }
        item {
            // Users cannot add a network themselves once DISALLOW_CONFIG_WIFI
            // is set, so this is the only route onto a village network.
            AddNetworkFields { ssid, passphrase ->
                message = if (wifi.addNetwork(ssid, passphrase)) {
                    context.getString(R.string.wifi_added, ssid)
                } else {
                    context.getString(R.string.wifi_add_failed)
                }
            }
        }
        item { HorizontalDivider() }
        items(wifi.savedNetworks()) { ssid ->
            ListItem(headlineContent = { Text(ssid) })
        }
    }
    message?.let { Info(it) { message = null } }
}

@Composable
private fun InstallUrlPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AdminPage(stringResource(R.string.admin_install_url), onBack, TAG_ADMIN_INSTALL_URL) {
        item {
            Text(
                text = stringResource(R.string.install_url_explain),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            InstallUrlFields(enabled = !busy) { url, packageName ->
                scope.launch {
                    busy = true
                    message = SideloadFromUrl.run(context, url, packageName)
                    busy = false
                }
            }
        }
    }
    message?.let { Info(it) { message = null } }
}

// --- Shared scaffolding ---------------------------------------------------

@Composable
private fun AdminPage(
    title: String,
    onBack: () -> Unit,
    testTag: String,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().testTag(testTag)) {
        ListItem(
            headlineContent = {
                Text(title, style = MaterialTheme.typography.headlineSmall)
            },
            trailingContent = {
                TextButton(onClick = onBack, modifier = Modifier.testTag(TAG_ADMIN_BACK)) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun AdminRow(labelRes: Int, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(labelRes),
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun Info(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TAG_DIALOG_OK)) {
                Text(stringResource(R.string.action_ok))
            }
        },
        modifier = Modifier.testTag(TAG_DIALOG),
    )
}

@Composable
private fun Confirm(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(TAG_DIALOG_CONFIRM)) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag(TAG_DIALOG),
    )
}

private fun kioskVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
}.getOrDefault("unknown")

private fun installedVersion(context: Context, packageName: String): String = runCatching {
    val info = context.packageManager.getPackageInfo(packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
}.getOrDefault("not installed")

private fun appLabel(context: Context, packageName: String): String = runCatching {
    val info = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(info).toString()
}.getOrDefault(packageName)

const val TAG_ADMIN_MENU = "admin-menu"
const val TAG_ADMIN_DEVICE_INFO = "admin-device-info"
const val TAG_ADMIN_VISIBLE_APPS = "admin-visible-apps"
const val TAG_ADMIN_CHANGE_PIN = "admin-change-pin"
const val TAG_ADMIN_WIFI = "admin-wifi"
const val TAG_ADMIN_INSTALL_URL = "admin-install-url"
const val TAG_ADMIN_BACK = "admin-back"
const val TAG_WIFI_SWITCH = "wifi-switch"
const val TAG_DIALOG = "admin-dialog"
const val TAG_DIALOG_OK = "admin-dialog-ok"
const val TAG_DIALOG_CONFIRM = "admin-dialog-confirm"
const val TAG_DIALOG_BUSY = "admin-dialog-busy"
const val TAG_ROW_DEVICE_INFO = "admin-row-device-info"
const val TAG_ROW_VISIBLE_APPS = "admin-row-visible-apps"
const val TAG_ROW_CHANGE_PIN = "admin-row-change-pin"
const val TAG_ROW_WIFI = "admin-row-wifi"
const val TAG_ROW_REAPPLY = "admin-row-reapply"
const val TAG_ROW_INSTALL_URL = "admin-row-install-url"
const val TAG_ROW_UNLOCK = "admin-row-unlock"
const val TAG_ROW_UNPROVISION = "admin-row-unprovision"
