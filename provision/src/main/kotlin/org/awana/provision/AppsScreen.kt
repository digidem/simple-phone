package org.awana.provision

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The trainer-populated APK library.
 *
 * The fingerprint is shown, and shown readably, because it is the only thing
 * standing between a substituted APK and a whole team's phones.
 */
@Composable
fun AppsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { ApkLibrary(context) }

    var entries by remember { mutableStateOf(library.entries()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<ApkEntry?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            library.add(uri)
                .onSuccess {
                    entries = library.entries()
                    message = context.getString(R.string.apps_added, it.label)
                }
                .onFailure { message = it.message }
        }
    }

    Column(modifier = modifier) {
        Button(
            // Any MIME type: file managers disagree about what an APK is, and a
            // trainer should not have to know which one their phone picked.
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag(TAG_ADD_APK),
        ) {
            Text(stringResource(R.string.apps_add))
        }
        HorizontalDivider()

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.apps_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp).testTag(TAG_APPS_EMPTY),
            )
        }

        LazyColumn {
            items(entries, key = { it.packageName }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.label) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.packageName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = stringResource(
                                    R.string.apps_version,
                                    entry.versionName ?: "?",
                                    entry.versionCode,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(R.string.apps_size, entry.sizeBytes / 1_000_000),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(R.string.apps_fingerprint),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = entry.readableFingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("fingerprint-${entry.packageName}"),
                            )
                        }
                    },
                    trailingContent = {
                        TextButton(onClick = { confirmRemove = entry }) {
                            Text(stringResource(R.string.action_delete))
                        }
                    },
                    modifier = Modifier.testTag("apk-${entry.packageName}"),
                )
                HorizontalDivider()
            }
            item {
                Text(
                    text = stringResource(R.string.apps_fingerprint_help),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    confirmRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(entry.label) },
            text = { Text(stringResource(R.string.apps_remove)) },
            confirmButton = {
                TextButton(onClick = {
                    library.remove(entry.packageName)
                    entries = library.entries()
                    confirmRemove = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
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

const val TAG_ADD_APK = "apps-add"
const val TAG_APPS_EMPTY = "apps-empty"
const val TAG_APPS_DIALOG = "apps-dialog"
