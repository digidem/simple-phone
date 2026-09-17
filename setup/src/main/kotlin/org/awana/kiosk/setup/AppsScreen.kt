package org.awana.kiosk.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The trainer-populated APK library.
 *
 * The fingerprint is shown, and shown readably, because it is the only thing
 * standing between a substituted APK and a whole team's phones. Two moments
 * need more than that: a replacement signed with a different key, and taking a
 * file away that deployments still install.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { ApkLibrary(context) }
    val profiles = remember { ProfileStore(context) }

    var entries by remember { mutableStateOf(library.entries()) }
    // Read once rather than per row: this is a file, and rows recompose.
    val deployments = remember { profiles.all() }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<ApkEntry?>(null) }
    var opened by remember { mutableStateOf<String?>(null) }
    var confirmKeyChange by remember { mutableStateOf<StagedApk?>(null) }

    fun added(entry: ApkEntry) {
        entries = library.entries()
        ApkIcons.forget(entry.packageName)
        message = context.getString(R.string.apps_added, entry.label)
    }

    // Any MIME type: file managers disagree about what an APK is, and a
    // trainer should not have to know which one their phone picked.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            library.stage(uri)
                .onSuccess { staged ->
                    if (staged.keyChanged) confirmKeyChange = staged
                    else added(library.commit(staged))
                }
                .onFailure { message = it.message }
        }
    }

    val pick = { picker.launch(arrayOf("*/*")) }
    val open = opened?.let { name -> entries.firstOrNull { it.packageName == name } }

    if (open != null) {
        BackHandler { opened = null }
        AppFileScreen(
            entry = open,
            usedIn = deployments.filter { open.packageName in it.packages },
            // Replacing is how updating works: the same importer, and the
            // library replaces in place.
            onReplace = { pick() },
            onRemove = { confirmRemove = open },
            onBack = { opened = null },
            modifier = modifier,
        )
    } else {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.apps_title)) }) },
        contentWindowInsets = WindowInsets(0),
        modifier = modifier.fillMaxSize(),
    ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.apps_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .testTag(TAG_APPS_EMPTY),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.packageName }) { entry ->
                val usedIn = deployments.count { entry.packageName in it.packages }
                ListItem(
                    leadingContent = { AppTile(entry) },
                    headlineContent = { Text(entry.label) },
                    supportingContent = { Text(apkSummary(entry, usedIn)) },
                    trailingContent = {
                        Glyph(R.drawable.ic_chevron_right, MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier
                        .clickableRow { opened = entry.packageName }
                        .testTag("apk-${entry.packageName}"),
                )
                HorizontalDivider()
            }
        }

        if (entries.isEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { pick() },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.apps_add)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .testTag(TAG_ADD_APK),
            )
        } else {
            FloatingActionButton(
                onClick = { pick() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .testTag(TAG_ADD_APK),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.apps_add),
                )
            }
        }
    }
    }
    }

    confirmKeyChange?.let { staged ->
        KeyChangedDialog(
            staged = staged,
            onReplace = {
                confirmKeyChange = null
                added(library.commit(staged))
            },
            onCancel = {
                confirmKeyChange = null
                library.discard(staged)
            },
        )
    }

    confirmRemove?.let { entry ->
        RemoveDialog(
            entry = entry,
            usedIn = deployments.filter { entry.packageName in it.packages },
            onRemove = {
                library.remove(entry.packageName)
                ApkIcons.forget(entry.packageName)
                entries = library.entries()
                confirmRemove = null
                opened = null
            },
            onCancel = { confirmRemove = null },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_ok)) }
            },
            modifier = Modifier.testTag(TAG_APPS_DIALOG),
        )
    }
}

@Composable
private fun KeyChangedDialog(staged: StagedApk, onReplace: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.apk_key_changed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.apk_key_changed_body))
                Text(stringResource(R.string.apk_key_changed_consequence))
                Fingerprint(R.string.apk_key_changed_you_have, staged.replaces?.readableFingerprint.orEmpty())
                Fingerprint(R.string.apk_key_changed_adding, staged.entry.readableFingerprint)
            }
        },
        confirmButton = {
            TextButton(onClick = onReplace, modifier = Modifier.testTag(TAG_REPLACE_ANYWAY)) {
                Text(
                    text = stringResource(R.string.apk_replace_anyway),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag(TAG_KEY_CHANGED),
    )
}

@Composable
private fun Fingerprint(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RemoveDialog(
    entry: ApkEntry,
    usedIn: List<DeploymentProfile>,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.apk_remove_title, entry.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (usedIn.isEmpty()) {
                    Text(stringResource(R.string.apk_remove_unused))
                } else {
                    Text(pluralStringResource(R.plurals.apk_remove_in_use, usedIn.size, usedIn.size))
                    usedIn.forEach { Text("• ${it.name}") }
                    Text(stringResource(R.string.apk_remove_consequence))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRemove, modifier = Modifier.testTag(TAG_REMOVE_CONFIRM)) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag(TAG_REMOVE_APK),
    )
}

/** A 56dp button, 16dp clear of the bar below it and 16dp clear of the last row. */
private val FAB_CLEARANCE = 88.dp

const val TAG_ADD_APK = "apps-add"
const val TAG_APPS_EMPTY = "apps-empty"
const val TAG_APPS_DIALOG = "apps-dialog"
const val TAG_KEY_CHANGED = "apk-key-changed"
const val TAG_REPLACE_ANYWAY = "apk-replace-anyway"
const val TAG_REMOVE_APK = "apk-remove"
const val TAG_REMOVE_CONFIRM = "apk-remove-confirm"
