package org.awana.kiosk.launcher

import org.awana.kiosk.shared.DeviceLabel
import org.awana.kiosk.shared.KioskConfig
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.awana.kiosk.design.okColors
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.withEntry
import org.awana.kiosk.policy.DeviceFacts
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.LockTaskBreakService
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.policy.SideloadFromUrl
import org.awana.kiosk.policy.WifiAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page { Menu, About, Change, Update, Wrong, VisibleApps, ChangePin, Wifi, InstallUrl }

/**
 * Three doors behind the PIN: what this phone is, what can be changed about it,
 * and what to do when something is wrong.
 *
 * Recovery lives behind the third so a trainer looking for Wi-Fi never lands
 * beside "remove the lock". Every destructive action states its consequence in
 * words before it happens.
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

            Page.About -> AboutPage(onBack = { page = Page.Menu })
            Page.Change -> ChangePage(onNavigate = { page = it }, onBack = { page = Page.Menu })
            Page.Update -> UpdateScreen(onBack = { page = Page.Menu })
            Page.Wrong -> SomethingWrongPage(onNavigate = { page = it }, onDone = onDone, onBack = { page = Page.Menu })
            Page.VisibleApps -> VisibleAppsPage(onBack = { page = Page.Change })
            Page.ChangePin -> ChangePinPage(onBack = { page = Page.Change })
            Page.Wifi -> WifiPage(onBack = { page = Page.Change })
            Page.InstallUrl -> InstallUrlPage(onBack = { page = Page.Wrong })
        }
    }
}

/**
 * Three destinations named after why a trainer came, rather than eight peers.
 * It costs one extra tap on every task; the point is that recovery is not one
 * of the peers a thumb can land on by accident.
 */
@Composable
private fun AdminMenu(onNavigate: (Page) -> Unit, onDone: () -> Unit) {
    AdminPage(title = stringResource(R.string.admin_title), onBack = onDone, testTag = TAG_ADMIN_MENU) {
        item {
            Door(
                icon = R.drawable.ic_door_about,
                title = R.string.admin_about,
                body = R.string.admin_about_body,
                tag = TAG_ROW_ABOUT,
            ) { onNavigate(Page.About) }
        }
        item {
            Door(
                icon = R.drawable.ic_door_change,
                title = R.string.admin_change,
                body = R.string.admin_change_body,
                tag = TAG_ROW_CHANGE,
            ) { onNavigate(Page.Change) }
        }
        item {
            Door(
                icon = R.drawable.ic_door_update,
                title = R.string.admin_update,
                body = R.string.admin_update_body,
                tag = TAG_ROW_UPDATE,
            ) { onNavigate(Page.Update) }
        }
        item {
            Door(
                icon = R.drawable.ic_door_wrong,
                title = R.string.admin_wrong,
                body = R.string.admin_wrong_body,
                tag = TAG_ROW_WRONG,
                destructive = true,
            ) { onNavigate(Page.Wrong) }
        }
        item {
            Text(
                text = stringResource(R.string.admin_doors_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Door(
    icon: Int,
    title: Int,
    body: Int,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    OutlinedCard(
        onClick = onClick,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (destructive) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (destructive) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .testTag(tag),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = accent)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChangePage(onNavigate: (Page) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current

    AdminPage(stringResource(R.string.admin_change), onBack, TAG_ADMIN_CHANGE) {
        item {
            AdminDoor(R.string.admin_visible_apps, R.string.admin_visible_apps_body, TAG_ROW_VISIBLE_APPS) {
                onNavigate(Page.VisibleApps)
            }
        }
        item {
            AdminDoor(R.string.admin_wifi, R.string.admin_wifi_body, TAG_ROW_WIFI) {
                onNavigate(Page.Wifi)
            }
        }
        item {
            AdminDoor(R.string.admin_change_pin, R.string.admin_change_pin_body, TAG_ROW_CHANGE_PIN) {
                onNavigate(Page.ChangePin)
            }
        }
        // Everything else on this page is one curated setting. This is the
        // escape hatch from curating them one feature request at a time — the
        // phone's own settings, behind the same PIN that can remove the lock
        // altogether, and put back by `LockTaskBreakService` when the break ends.
        item {
            AdminDoor(R.string.admin_settings, R.string.admin_settings_body, TAG_ROW_SETTINGS) {
                openPhoneSettings(context)
            }
        }
    }
}

/**
 * Settings is on the lock task allowlist, so it opens *inside* the lock: the
 * phone stays confined, and there is no window to close afterwards. Nothing
 * offers it to the user — only this door does.
 */
private fun openPhoneSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { Log.w("AdminScreen", "This phone has no settings activity", it) }
}

/**
 * The three ordinary fixes, a labelled break, then the one that cannot be
 * undone — so a trainer who came for the first never lands beside the last.
 */
@Composable
private fun SomethingWrongPage(onNavigate: (Page) -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmUnlock by remember { mutableStateOf(false) }
    var confirmUnprovision by remember { mutableStateOf(false) }
    var unprovisionProblems by remember { mutableStateOf<String?>(null) }

    AdminPage(stringResource(R.string.admin_wrong), onBack, TAG_ADMIN_WRONG) {
        item {
            AdminDoor(R.string.admin_reapply_policy, R.string.admin_reapply_policy_body, TAG_ROW_REAPPLY) {
                scope.launch {
                    busy = context.getString(R.string.admin_reapply_policy)
                    result = reapplyPolicy(context)
                    busy = null
                }
            }
        }
        item {
            AdminDoor(R.string.admin_install_url, R.string.admin_install_url_body, TAG_ROW_INSTALL_URL) {
                onNavigate(Page.InstallUrl)
            }
        }
        item {
            AdminDoor(R.string.admin_unlock_temporarily, R.string.admin_unlock_temporarily_body, TAG_ROW_UNLOCK) {
                confirmUnlock = true
            }
        }
        item {
            Text(
                text = stringResource(R.string.admin_last_resort),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
            )
        }
        item {
            AdminDoor(
                title = R.string.admin_unprovision,
                body = R.string.admin_unprovision_body,
                tag = TAG_ROW_UNPROVISION,
                destructive = true,
            ) { confirmUnprovision = true }
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
    unprovisionProblems?.let { Info(text = it, onDismiss = { unprovisionProblems = null; onDone() }) }

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
                scope.launch {
                    busy = context.getString(R.string.admin_unprovision)
                    val problems = unprovision(context)
                    busy = null
                    if (problems.isEmpty()) onDone() else unprovisionProblems = problems.joinToString("\n\n")
                }
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
    val result = policy.applyAll(config)
    val problems = result.failures + result.permissionFailures
    if (problems.isEmpty()) {
        context.getString(R.string.admin_reapply_ok, result.applied.size)
    } else {
        context.getString(R.string.admin_reapply_partial, problems.joinToString("\n\n"))
    }
}

// --- Pages ---------------------------------------------------------------

/**
 * A verdict, then the facts.
 *
 * A trainer arrives at this screen with one question — is this phone alright —
 * so it is answered in plain words before anything is listed. "Device owner:
 * true" told them nothing; its meaning is folded into the verdict.
 */
@Composable
private fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val config = remember { ConfigStore(context).load() }
    val policy = remember { DevicePolicy(context) }
    val report = remember { Provisioner(context).lastReport() }

    val healthy = policy.isDeviceOwner &&
        config != null &&
        report?.failures.isNullOrEmpty() &&
        report?.permissionFailures.isNullOrEmpty()

    // Queried once: this is a PackageManager call per app, and rows recompose.
    val installed = remember(config) {
        config?.packages.orEmpty().associate {
            it.packageName to DeviceFacts.installedPackage(context, it.packageName)?.versionName
        }
    }

    AdminPage(stringResource(R.string.admin_about), onBack, TAG_ADMIN_ABOUT) {
        item {
            Verdict(
                healthy = healthy,
                headline = when {
                    healthy -> stringResource(R.string.about_healthy)
                    config == null -> stringResource(R.string.about_not_set_up)
                    // The old screen said "device owner: true", which meant
                    // nothing to a trainer. This is the same fact, in words.
                    !policy.isDeviceOwner -> stringResource(R.string.about_not_locked)
                    else -> report?.failures?.firstOrNull()
                        ?: stringResource(R.string.about_not_healthy)
                },
                body = when {
                    healthy -> stringResource(R.string.about_healthy_body)
                    config == null -> stringResource(R.string.about_not_set_up_body)
                    !policy.isDeviceOwner -> stringResource(R.string.admin_not_device_owner)
                    else -> report?.failures.orEmpty().drop(1)
                        .plus(report?.permissionFailures.orEmpty())
                        .joinToString("\n\n")
                        .ifBlank { stringResource(R.string.about_not_healthy_body) }
                },
            )
        }

        item { Section(R.string.about_deployment) }
        item { Fact(R.string.info_deployment, config?.deploymentName ?: "—") }
        item { Fact(R.string.info_deployment_id, config?.deploymentId ?: "—") }
        item { Fact(R.string.deployment_locale, config?.locale ?: "—") }

        item { Section(R.string.about_apps) }
        items(config?.packages.orEmpty()) { spec ->
            val version = installed[spec.packageName]
            ListItem(
                headlineContent = {
                    Text(
                        text = appLabel(context, spec.packageName),
                        color = if (version == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                trailingContent = {
                    Text(
                        text = version ?: stringResource(R.string.about_not_installed),
                        color = if (version == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }

        item { Section(R.string.about_this_phone) }
        item { Fact(R.string.info_device_label, DeviceLabel.of(DeviceFacts.deviceId(context))) }
        item {
            Fact(
                R.string.about_make_and_model,
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            )
        }
        item { Fact(R.string.info_android, android.os.Build.VERSION.RELEASE) }
        item { Fact(R.string.info_kiosk_version, kioskVersion(context)) }
        item {
            // A phone provisioned with a debug-signed kiosk can never receive
            // production updates, so which key signed it has to be visible.
            ListItem(
                headlineContent = { Text(stringResource(R.string.info_build_variant)) },
                supportingContent = { Text(stringResource(R.string.about_signing_key_body)) },
                trailingContent = { Text(report?.buildVariant ?: "—") },
            )
        }
    }
}

@Composable
internal fun Verdict(healthy: Boolean, headline: String, body: String) {
    Surface(
        color = if (healthy) {
            MaterialTheme.okColors.container
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (healthy) {
            MaterialTheme.okColors.onContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag(if (healthy) TAG_VERDICT_OK else TAG_VERDICT_PROBLEM),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            if (body.isNotBlank()) Text(body)
        }
    }
}

@Composable
private fun Section(label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Fact(label: Int, value: String) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Text(value, style = MaterialTheme.typography.bodyMedium) },
    )
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
            val role = config?.launcher?.firstOrNull { it.packageName == spec.packageName }?.role
            val visible = role != null && role != LauncherRole.HIDDEN
            ListItem(
                headlineContent = { Text(appLabel(context, spec.packageName)) },
                supportingContent = { Text(spec.packageName) },
                trailingContent = {
                    Switch(
                        checked = visible,
                        onCheckedChange = { wanted ->
                            config = store.update { current ->
                                val existing = current.launcher
                                    .firstOrNull { it.packageName == spec.packageName }
                                    ?: LauncherEntry(spec.packageName)
                                val next = existing.copy(
                                    role = if (wanted) LauncherRole.SMALL else LauncherRole.HIDDEN,
                                )
                                current.copy(launcher = current.launcher.withEntry(next))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminPage(
    title: String,
    onBack: () -> Unit,
    testTag: String,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_ADMIN_BACK)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        modifier = Modifier.fillMaxSize().testTag(testTag),
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            content = content,
        )
    }
}

@Composable
private fun AdminDoor(
    title: Int,
    body: Int,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        headlineContent = { Text(text = stringResource(title), color = color) },
        supportingContent = { Text(stringResource(body)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick),
    )
    HorizontalDivider()
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

private fun appLabel(context: Context, packageName: String): String = runCatching {
    val info = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(info).toString()
}.getOrDefault(packageName)

const val TAG_ADMIN_MENU = "admin-menu"
const val TAG_ADMIN_ABOUT = "admin-about"
const val TAG_ADMIN_CHANGE = "admin-change"
const val TAG_ADMIN_WRONG = "admin-wrong"
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
const val TAG_VERDICT_OK = "admin-verdict-ok"
const val TAG_VERDICT_PROBLEM = "admin-verdict-problem"
const val TAG_ROW_ABOUT = "admin-row-about"
const val TAG_ROW_CHANGE = "admin-row-change"
const val TAG_ROW_UPDATE = "admin-row-update"
const val TAG_ROW_WRONG = "admin-row-wrong"
const val TAG_ROW_VISIBLE_APPS = "admin-row-visible-apps"
const val TAG_ROW_SETTINGS = "admin-row-settings"
const val TAG_ROW_CHANGE_PIN = "admin-row-change-pin"
const val TAG_ROW_WIFI = "admin-row-wifi"
const val TAG_ROW_REAPPLY = "admin-row-reapply"
const val TAG_ROW_INSTALL_URL = "admin-row-install-url"
const val TAG_ROW_UNLOCK = "admin-row-unlock"
const val TAG_ROW_UNPROVISION = "admin-row-unprovision"
