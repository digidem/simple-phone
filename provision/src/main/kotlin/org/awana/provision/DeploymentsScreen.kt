package org.awana.provision

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.awana.kiosk.shared.AdminPin

@Composable
fun DeploymentsScreen(modifier: Modifier = Modifier, onStartSession: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    var profiles by remember { mutableStateOf(store.all()) }
    var editing by remember { mutableStateOf<DeploymentProfile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            message = "That file could not be read."
            return@rememberLauncherForActivityResult
        }
        store.import(text)
            .onSuccess {
                store.save(it)
                profiles = store.all()
            }
            .onFailure { message = "That file is not a deployment: ${it.message}" }
    }

    val target = editing
    if (target != null) {
        DeploymentEditor(
            profile = target,
            onSave = {
                store.save(it)
                profiles = store.all()
                editing = null
            },
            onCancel = { editing = null },
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Button(
                onClick = {
                    editing = DeploymentProfile(name = "", adminPinHash = "")
                },
                modifier = Modifier.weight(1f).testTag(TAG_NEW_DEPLOYMENT),
            ) { Text(stringResource(R.string.deployments_new)) }

            OutlinedButton(
                onClick = { importer.launch(arrayOf("*/*")) },
                modifier = Modifier.testTag(TAG_IMPORT),
            ) { Text(stringResource(R.string.action_import)) }
        }
        HorizontalDivider()

        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.deployments_empty),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp).testTag(TAG_DEPLOYMENTS_EMPTY),
            )
        }

        LazyColumn {
            items(profiles, key = { it.id }) { profile ->
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = {
                        Text(
                            text = "${profile.packages.size} apps · ${profile.locale}" +
                                if (profile.showNotificationShade) " · shade on" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.testTag("deployment-${profile.id}"),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Button(
                        onClick = { onStartSession(profile.id) },
                        modifier = Modifier.testTag("start-${profile.id}"),
                    ) { Text(stringResource(R.string.deployment_start)) }
                    TextButton(onClick = { editing = profile }) { Text(stringResource(R.string.action_edit)) }
                    TextButton(onClick = {
                        store.save(profile.duplicate())
                        profiles = store.all()
                    }) { Text(stringResource(R.string.action_duplicate)) }
                    TextButton(onClick = { message = store.export(profile) }) {
                        Text(stringResource(R.string.action_export))
                    }
                    TextButton(onClick = {
                        store.delete(profile.id)
                        profiles = store.all()
                    }) { Text(stringResource(R.string.action_delete)) }
                }
                HorizontalDivider()
            }
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(it, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

@Composable
private fun DeploymentEditor(
    profile: DeploymentProfile,
    onSave: (DeploymentProfile) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val library = remember { ApkLibrary(context) }
    val available = remember { library.entries() }

    var name by remember { mutableStateOf(profile.name) }
    var pin by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf(profile.packages) }
    var visible by remember { mutableStateOf(profile.visibleInLauncher) }
    var locale by remember { mutableStateOf(profile.locale) }
    var timeZone by remember { mutableStateOf(profile.timeZone) }
    var shade by remember { mutableStateOf(profile.showNotificationShade) }

    val hasPin = profile.adminPinHash.isNotEmpty() || pin.length >= AdminPin.MIN_LENGTH
    val valid = name.isNotBlank() && hasPin && packages.isNotEmpty()

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag(TAG_EDITOR),
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
            modifier = Modifier.fillMaxWidth().testTag(TAG_EDITOR_PIN),
        )
        Text(
            text = stringResource(
                if (profile.adminPinHash.isEmpty()) R.string.deployment_pin_help
                else R.string.deployment_pin_unchanged,
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        Text(stringResource(R.string.deployment_apps), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.deployment_apps_help), style = MaterialTheme.typography.bodySmall)
        if (available.isEmpty()) {
            Text(stringResource(R.string.deployment_no_apps), style = MaterialTheme.typography.bodyMedium)
        }
        available.forEach { entry ->
            val included = entry.packageName in packages
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = included,
                    onCheckedChange = { wanted ->
                        packages = if (wanted) packages + entry.packageName
                        else packages - entry.packageName
                        // An app cannot be shown on the home screen if it is not
                        // installed at all.
                        if (!wanted) visible = visible - entry.packageName
                    },
                    modifier = Modifier.testTag("include-${entry.packageName}"),
                )
                Text(entry.label, modifier = Modifier.weight(1f))
                Checkbox(
                    checked = entry.packageName in visible,
                    enabled = included,
                    onCheckedChange = { wanted ->
                        visible = if (wanted) visible + entry.packageName
                        else visible - entry.packageName
                    },
                    modifier = Modifier.testTag("visible-${entry.packageName}"),
                )
                Text(
                    text = stringResource(R.string.deployment_show_on_home),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

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

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(
                checked = shade,
                onCheckedChange = { shade = it },
                modifier = Modifier.testTag(TAG_EDITOR_SHADE),
            )
            Text(
                text = stringResource(R.string.deployment_shade),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // The caveats live here, beside the toggle, rather than in separate
        // documentation: a trainer has to understand the cost at the moment
        // they choose to pay it.
        if (shade) {
            Card(modifier = Modifier.testTag(TAG_SHADE_WARNING)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.deployment_shade_on_warning),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    showNotificationShadeCaveats.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.deployment_shade_off_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
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
                            visibleInLauncher = visible,
                            locale = locale.trim(),
                            timeZone = timeZone.trim(),
                            showNotificationShade = shade,
                        ),
                    )
                },
                modifier = Modifier.testTag(TAG_EDITOR_SAVE),
            ) { Text(stringResource(R.string.action_save)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

const val TAG_NEW_DEPLOYMENT = "deployments-new"
const val TAG_IMPORT = "deployments-import"
const val TAG_DEPLOYMENTS_EMPTY = "deployments-empty"
const val TAG_EDITOR = "deployment-editor"
const val TAG_EDITOR_NAME = "deployment-name"
const val TAG_EDITOR_PIN = "deployment-pin"
const val TAG_EDITOR_SHADE = "deployment-shade"
const val TAG_SHADE_WARNING = "deployment-shade-warning"
const val TAG_EDITOR_SAVE = "deployment-save"
