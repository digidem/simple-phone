package org.awana.provision

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * A deployment, opened.
 *
 * Tapping a row in the list lands here rather than starting a hotspot on the
 * spot: it gives edit, duplicate, export and delete a home, and puts a preview
 * of the home screen the deployment produces in front of the trainer before
 * they commit six phones to it. Starting is still one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploymentDetail(
    profile: DeploymentProfile,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val apps = remember(profile.id) {
        ApkLibrary(context).entries().associateBy { it.packageName }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile.name) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag(TAG_DEPLOYMENT_MENU),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more),
                            contentDescription = stringResource(R.string.action_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            onClick = { menuOpen = false; onEdit() },
                            modifier = Modifier.testTag(TAG_DEPLOYMENT_EDIT),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_duplicate)) },
                            onClick = { menuOpen = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_export)) },
                            onClick = { menuOpen = false; onExport() },
                        )
                        // Separated and error-coloured: nothing destructive
                        // sits where a thumb lands.
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { menuOpen = false; confirmDelete = true },
                            modifier = Modifier.testTag(TAG_DEPLOYMENT_DELETE),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag(TAG_DEPLOYMENT_START),
                ) { Text(stringResource(R.string.deployment_start)) }
            }
        },
        modifier = modifier.testTag(TAG_DEPLOYMENT_DETAIL),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.deployment_home_screen))
            HomePreview(
                entries = profile.launcher,
                apps = apps,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            SummaryRow(R.string.deployment_locale, languageName(profile.locale))
            SummaryRow(
                R.string.shade_title,
                stringResource(
                    if (profile.showNotificationShade) R.string.shade_on_body else R.string.shade_off_body,
                ),
            )
            SummaryRow(R.string.deployment_apps_installed, "${profile.packages.size}")
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.deployment_delete_title, profile.name)) },
            text = {
                Column {
                    // The question a trainer actually has is whether this
                    // breaks phones already in the field, so that goes first.
                    Text(stringResource(R.string.deployment_delete_unaffected))
                    Text(
                        text = stringResource(R.string.deployment_delete_body),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDelete() },
                    modifier = Modifier.testTag(TAG_DELETE_CONFIRM),
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** "pt_BR" means nothing to a trainer; "português (Brasil)" does. */
private fun languageName(locale: String): String {
    val tag = java.util.Locale.forLanguageTag(locale.replace('_', '-'))
    return tag.getDisplayName(tag).ifBlank { locale }
}

@Composable
private fun SummaryRow(label: Int, value: String) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        },
    )
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

const val TAG_DEPLOYMENT_DETAIL = "deployment-detail"
const val TAG_DEPLOYMENT_MENU = "deployment-menu"
const val TAG_DEPLOYMENT_EDIT = "deployment-edit"
const val TAG_DEPLOYMENT_DELETE = "deployment-delete"
const val TAG_DEPLOYMENT_START = "deployment-start"
const val TAG_DELETE_CONFIRM = "deployment-delete-confirm"
