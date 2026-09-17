package org.awana.kiosk.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.WifiNetwork
import org.awana.kiosk.shared.withEntry

/**
 * Making and editing a deployment.
 *
 * One row per app with a role badge, rather than the two checkboxes this used
 * to have: they asked one question twice, left no room for a name, subtitle or
 * icon, and had nowhere to say which app is the hero. The apps section leads
 * with a preview, so the settings have something to be true about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploymentEditor(
    profile: DeploymentProfile,
    onSave: (DeploymentProfile) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val library = remember { ApkLibrary(context) }
    val available = remember { library.entries() }
    val apps = remember(available) { available.associateBy { it.packageName } }

    var name by remember { mutableStateOf(profile.name) }
    var pin by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf(profile.packages) }
    var launcher by remember { mutableStateOf(profile.launcher) }
    var locale by remember { mutableStateOf(profile.locale) }
    var timeZone by remember { mutableStateOf(profile.timeZone) }
    var shade by remember { mutableStateOf(profile.showNotificationShade) }
    var screenLock by remember { mutableStateOf(profile.screenLock) }
    var screenOff by remember { mutableStateOf(profile.screenOffTimeoutMs) }
    var pickingTimeout by remember { mutableStateOf(false) }
    var networks by remember { mutableStateOf(profile.wifiNetworks) }

    var editingEntry by remember { mutableStateOf<String?>(null) }
    var shadeScreen by remember { mutableStateOf(false) }
    var addingApp by remember { mutableStateOf(false) }

    val hasPin = profile.adminPinHash.isNotEmpty() || pin.length >= AdminPin.MIN_LENGTH
    val valid = name.isNotBlank() && hasPin && packages.isNotEmpty()

    editingEntry?.let { packageName ->
        BackHandler { editingEntry = null }
        EntryEditor(
            entry = launcher.firstOrNull { it.packageName == packageName }
                ?: LauncherEntry(packageName, LauncherRole.HIDDEN),
            app = apps[packageName],
            onChange = { launcher = launcher.withEntry(it) },
            onRemove = {
                packages = packages - packageName
                launcher = launcher.filterNot { it.packageName == packageName }
                editingEntry = null
            },
            onDone = { editingEntry = null },
            modifier = modifier,
        )
        return
    }

    if (shadeScreen) {
        BackHandler { shadeScreen = false }
        NotificationShadeScreen(
            allowed = shade,
            onChange = { shade = it },
            onBack = { shadeScreen = false },
            modifier = modifier,
        )
        return
    }

    if (addingApp) {
        AddAppDialog(
            choices = available.filterNot { it.packageName in packages },
            onPick = { app ->
                packages = packages + app.packageName
                // Shown by default; the entry editor is where "installed only"
                // is chosen, and that is the rarer intent.
                launcher = launcher.withEntry(
                    LauncherEntry(app.packageName, LauncherRole.SMALL),
                )
                addingApp = false
                editingEntry = app.packageName
            },
            onDismiss = { addingApp = false },
        )
    }

    if (pickingTimeout) {
        ChoiceDialog(
            title = stringResource(R.string.deployment_screen_timeout),
            options = SCREEN_OFF_CHOICES,
            selected = screenOff,
            label = { minutesLabel(it) },
            onPick = { screenOff = it; pickingTimeout = false },
            onDismiss = { pickingTimeout = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(name.ifBlank { stringResource(R.string.deployments_new) })
                },
                navigationIcon = { BackButton(onCancel) },
                actions = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            onSave(
                                profile.copy(
                                    name = name.trim(),
                                    adminPinHash = if (pin.length >= AdminPin.MIN_LENGTH) {
                                        AdminPin.hash(pin)
                                    } else {
                                        profile.adminPinHash
                                    },
                                    packages = packages,
                                    launcher = launcher,
                                    locale = locale.trim(),
                                    timeZone = timeZone.trim(),
                                    showNotificationShade = shade,
                                    screenLock = screenLock,
                                    screenOffTimeoutMs = screenOff,
                                    wifiNetworks = networks
                                        .map { it.copy(ssid = it.ssid.trim()) }
                                        .filter { it.ssid.isNotEmpty() },
                                ),
                            )
                        },
                        modifier = Modifier.testTag(TAG_EDITOR_SAVE),
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
        modifier = modifier.testTag(TAG_EDITOR),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.deployment_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_EDITOR_NAME),
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.deployment_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    supportingText = {
                        Text(
                            stringResource(
                                if (profile.adminPinHash.isEmpty()) R.string.deployment_pin_help
                                else R.string.deployment_pin_unchanged,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag(TAG_EDITOR_PIN),
                )
            }

            SectionHeader(stringResource(R.string.deployment_home_screen))
            HomePreview(
                entries = launcher,
                apps = apps,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // Only the apps this deployment installs. Everything else in the
            // library is one tap away, below.
            packages.forEach { packageName ->
                AppRow(
                    app = apps[packageName],
                    packageName = packageName,
                    entry = launcher.firstOrNull { it.packageName == packageName },
                    onOpen = { editingEntry = packageName },
                )
            }
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.deployment_add_app),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .clickableRow { addingApp = true }
                    .testTag(TAG_EDITOR_ADD_APP),
            )
            HorizontalDivider()

            SectionHeader(stringResource(R.string.deployment_language_and_time))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                OutlinedTextField(
                    value = locale,
                    onValueChange = { locale = it },
                    label = { Text(stringResource(R.string.deployment_locale)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeZone,
                    onValueChange = { timeZone = it },
                    label = { Text(stringResource(R.string.deployment_timezone)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.deployment_screen_timeout)) },
                supportingContent = { Text(minutesLabel(screenOff)) },
                trailingContent = {
                    Glyph(R.drawable.ic_chevron_right, MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier
                    .clickableRow { pickingTimeout = true }
                    .testTag(TAG_EDITOR_TIMEOUT),
            )

            // A navigation row, not a switch: the three costs need more room
            // than any editor row can give them.
            ListItem(
                headlineContent = { Text(stringResource(R.string.shade_title)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (shade) R.string.shade_on_body else R.string.shade_off_body,
                        ),
                    )
                },
                trailingContent = {
                    Glyph(R.drawable.ic_chevron_right, MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier
                    .clickableRow { shadeScreen = true }
                    .testTag(TAG_EDITOR_SHADE),
            )

            // A switch, not a screen: unlike the shade there is one consequence
            // and it fits on the row.
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_lock_title)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (screenLock) R.string.screen_lock_on_body
                            else R.string.screen_lock_off_body,
                        ),
                    )
                },
                trailingContent = {
                    Switch(checked = screenLock, onCheckedChange = { screenLock = it })
                },
                modifier = Modifier
                    .clickableRow { screenLock = !screenLock }
                    .testTag(TAG_EDITOR_SCREEN_LOCK),
            )

            SectionHeader(stringResource(R.string.deployment_wifi))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.deployment_wifi_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                networks.forEachIndexed { index, network ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = network.ssid,
                            onValueChange = {
                                networks = networks.replacing(index, network.copy(ssid = it))
                            },
                            label = { Text(stringResource(R.string.deployment_wifi_ssid)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("wifi-ssid-$index"),
                        )
                        OutlinedTextField(
                            value = network.passphrase.orEmpty(),
                            onValueChange = {
                                networks = networks.replacing(index, network.copy(passphrase = it))
                            },
                            label = { Text(stringResource(R.string.deployment_wifi_passphrase)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("wifi-passphrase-$index"),
                        )
                        TextButton(
                            onClick = { networks = networks.filterIndexed { i, _ -> i != index } },
                            modifier = Modifier.testTag("wifi-remove-$index"),
                        ) { Text(stringResource(R.string.action_delete)) }
                    }
                }
                OutlinedButton(
                    onClick = { networks = networks + WifiNetwork(ssid = "") },
                    modifier = Modifier.testTag(TAG_EDITOR_WIFI_ADD),
                ) { Text(stringResource(R.string.deployment_wifi_add)) }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: ApkEntry?,
    packageName: String,
    entry: LauncherEntry?,
    onOpen: () -> Unit,
) {
    ListItem(
        leadingContent = { app?.let { EntryTile(it, entry) } },
        headlineContent = { Text(entry?.label?.takeIf { it.isNotBlank() } ?: app?.label ?: packageName) },
        supportingContent = {
            Text(
                entry?.subtitle?.takeIf { it.isNotBlank() }
                    // A file that is not in the library cannot be served, so a
                    // deployment naming one cannot start until it is added.
                    ?: stringResource(
                        when {
                            app == null -> R.string.entry_file_missing
                            entry?.role == LauncherRole.HIDDEN -> R.string.entry_not_on_home
                            else -> R.string.entry_no_description
                        },
                    ),
                color = if (app == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = { RoleBadge(entry?.role ?: LauncherRole.HIDDEN) },
        modifier = Modifier
            .clickableRow(onOpen)
            .testTag("app-$packageName"),
    )
    HorizontalDivider()
}

/** The library, minus what this deployment already installs. */
@Composable
private fun AddAppDialog(
    choices: List<ApkEntry>,
    onPick: (ApkEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deployment_add_app)) },
        text = {
            if (choices.isEmpty()) {
                Text(stringResource(R.string.deployment_no_apps_left))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    choices.forEach { app ->
                        ListItem(
                            leadingContent = { EntryTile(app, null) },
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.packageName) },
                            modifier = Modifier
                                .clickableRow { onPick(app) }
                                .testTag("add-${app.packageName}"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RoleBadge(role: LauncherRole) {
    SuggestionChip(
        onClick = {},
        enabled = false,
        label = {
            Text(
                stringResource(
                    when (role) {
                        LauncherRole.HERO -> R.string.entry_role_hero
                        LauncherRole.SMALL -> R.string.entry_role_small
                        LauncherRole.HIDDEN -> R.string.entry_role_hidden
                    },
                ),
            )
        },
    )
}

/** The deployment's own icon if it set one, otherwise the app's. */
@Composable
private fun EntryTile(app: ApkEntry, entry: LauncherEntry?) {
    val icon = rememberAppIcon(app, entry?.iconPng)
    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp))
        }
    }
}

/** One app's entry: role, the words the phone shows, and the icon. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditor(
    entry: LauncherEntry,
    app: ApkEntry?,
    onChange: (LauncherEntry) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = listOf(LauncherRole.HERO, LauncherRole.SMALL, LauncherRole.HIDDEN)
    val context = LocalContext.current
    var iconProblem by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        EntryIcon.encode(context, uri)
            .onSuccess { onChange(entry.copy(iconPng = it)) }
            .onFailure { iconProblem = it.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.label?.takeIf { it.isNotBlank() } ?: app?.label ?: entry.packageName) },
                navigationIcon = { BackButton(onDone) },
                actions = {
                    TextButton(onClick = onDone, modifier = Modifier.testTag(TAG_ENTRY_DONE)) {
                        Text(stringResource(R.string.action_done))
                    }
                },
            )
        },
        modifier = modifier.testTag(TAG_ENTRY_EDITOR),
    ) { padding ->
        iconProblem?.let { problem ->
            AlertDialog(
                onDismissRequest = { iconProblem = null },
                text = { Text(problem) },
                confirmButton = {
                    TextButton(onClick = { iconProblem = null }) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.entry_how_it_appears))
            HomePreview(
                entries = listOf(entry),
                apps = app?.let { mapOf(it.packageName to it) }.orEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader(stringResource(R.string.entry_role))
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                roles.forEachIndexed { index, role ->
                    SegmentedButton(
                        selected = entry.role == role,
                        onClick = { onChange(entry.copy(role = role)) },
                        shape = SegmentedButtonDefaults.itemShape(index, roles.size),
                        label = {
                            Text(
                                stringResource(
                                    when (role) {
                                        LauncherRole.HERO -> R.string.entry_role_hero
                                        LauncherRole.SMALL -> R.string.entry_role_small
                                        LauncherRole.HIDDEN -> R.string.entry_role_hidden
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.testTag("role-${role.name.lowercase()}"),
                    )
                }
            }
            Text(
                text = stringResource(R.string.entry_role_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionHeader(stringResource(R.string.entry_name_and_description))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                OutlinedTextField(
                    value = entry.label.orEmpty(),
                    onValueChange = { onChange(entry.copy(label = it.ifBlank { null })) },
                    label = { Text(stringResource(R.string.entry_name)) },
                    placeholder = { app?.let { Text(it.label) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ENTRY_NAME),
                )
                OutlinedTextField(
                    value = entry.subtitle.orEmpty(),
                    onValueChange = { onChange(entry.copy(subtitle = it.ifBlank { null })) },
                    label = { Text(stringResource(R.string.entry_subtitle)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ENTRY_SUBTITLE),
                )
                Text(
                    text = stringResource(R.string.entry_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(stringResource(R.string.entry_icon))
            ListItem(
                leadingContent = { app?.let { EntryTile(it, entry) } },
                headlineContent = {
                    Text(
                        stringResource(
                            if (entry.iconPng == null) R.string.entry_icon_own
                            else R.string.entry_icon_picture,
                        ),
                    )
                },
                supportingContent = { Text(stringResource(R.string.entry_icon_change)) },
                trailingContent = {
                    Glyph(R.drawable.ic_chevron_right, MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier
                    .clickableRow { picker.launch("image/*") }
                    .testTag(TAG_ENTRY_ICON),
            )
            if (entry.iconPng != null) {
                TextButton(
                    onClick = { onChange(entry.copy(iconPng = null)) },
                    modifier = Modifier.padding(horizontal = 8.dp).testTag(TAG_ENTRY_ICON_CLEAR),
                ) { Text(stringResource(R.string.entry_icon_use_own)) }
            }

            // Hidden takes an app off the home screen; this takes it out of the
            // deployment, so it is not installed at all.
            TextButton(
                onClick = onRemove,
                modifier = Modifier.padding(16.dp).testTag(TAG_ENTRY_REMOVE),
            ) {
                Text(
                    text = stringResource(R.string.deployment_remove_app),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The shade's three costs need a screen; they do not fit in a row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationShadeScreen(
    allowed: Boolean,
    onChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shade_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        modifier = modifier.testTag(TAG_SHADE_SCREEN),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.shade_allow)) },
                supportingContent = {
                    Text(
                        stringResource(if (allowed) R.string.shade_on_body else R.string.shade_off_body),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = allowed,
                        onCheckedChange = onChange,
                        modifier = Modifier.testTag(TAG_SHADE_SWITCH),
                    )
                },
            )
            HorizontalDivider()

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.shade_off_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.shade_costs_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                showNotificationShadeCaveats.forEach {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableRow { onPick(option) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = { onPick(option) })
                        Text(label(option), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun minutesLabel(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return pluralStringResource(R.plurals.deployment_minutes, minutes, minutes)
}

/** Whole minutes only, so the label never has to say "0 minutes". */
private val SCREEN_OFF_CHOICES = listOf(60_000L, 120_000L, 300_000L, 600_000L)

internal fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable { onClick() }

private fun List<WifiNetwork>.replacing(index: Int, network: WifiNetwork): List<WifiNetwork> =
    mapIndexed { i, existing -> if (i == index) network else existing }

const val TAG_EDITOR = "deployment-editor"
const val TAG_EDITOR_NAME = "deployment-name"
const val TAG_EDITOR_PIN = "deployment-pin"
const val TAG_EDITOR_SCREEN_LOCK = "editor-screen-lock"
const val TAG_EDITOR_SHADE = "deployment-shade"
const val TAG_EDITOR_TIMEOUT = "deployment-timeout"
const val TAG_EDITOR_ADD_APP = "deployment-add-app"
const val TAG_EDITOR_WIFI_ADD = "deployment-wifi-add"
const val TAG_EDITOR_SAVE = "deployment-save"
const val TAG_ENTRY_EDITOR = "entry-editor"
const val TAG_ENTRY_NAME = "entry-name"
const val TAG_ENTRY_SUBTITLE = "entry-subtitle"
const val TAG_ENTRY_DONE = "entry-done"
const val TAG_ENTRY_REMOVE = "entry-remove"
const val TAG_ENTRY_ICON = "entry-icon"
const val TAG_ENTRY_ICON_CLEAR = "entry-icon-clear"
const val TAG_SHADE_SCREEN = "shade-screen"
const val TAG_SHADE_SWITCH = "shade-switch"
