package org.awana.kiosk.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploymentsScreen(modifier: Modifier = Modifier, onStartSession: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    var profiles by remember { mutableStateOf(store.all()) }
    var route by remember { mutableStateOf<DeploymentsRoute>(DeploymentsRoute.List) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<DeploymentProfile?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            message = context.getString(R.string.deployment_import_unreadable)
            return@rememberLauncherForActivityResult
        }
        store.import(text)
            .onSuccess {
                store.save(it)
                profiles = store.all()
            }
            .onFailure { message = context.getString(R.string.deployment_import_not_a_deployment) }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val profile = exporting
        exporting = null
        if (uri == null || profile == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(store.export(profile).toByteArray())
            }
        }.getOrNull()
        message = context.getString(
            if (written == null) R.string.deployment_export_failed
            else R.string.deployment_export_saved,
        )
    }

    when (val here = route) {
        is DeploymentsRoute.Editor -> {
            BackHandler { route = DeploymentsRoute.List }
            DeploymentEditor(
                profile = here.profile,
                onSave = {
                    store.save(it)
                    profiles = store.all()
                    route = DeploymentsRoute.Detail(it.id)
                },
                onCancel = { route = if (here.isNew) DeploymentsRoute.List else DeploymentsRoute.Detail(here.profile.id) },
                modifier = modifier,
            )
            return
        }

        is DeploymentsRoute.Detail -> {
            val profile = profiles.firstOrNull { it.id == here.id }
            if (profile == null) {
                // Deleted from under us. Falling back is a side effect, so it
                // cannot happen in composition.
                LaunchedEffect(here.id) { route = DeploymentsRoute.List }
            } else {
                BackHandler { route = DeploymentsRoute.List }
                DeploymentDetail(
                    profile = profile,
                    onStart = { onStartSession(profile.id) },
                    onEdit = { route = DeploymentsRoute.Editor(profile, isNew = false) },
                    onDuplicate = {
                        val copy = profile.duplicate()
                        store.save(copy)
                        profiles = store.all()
                        route = DeploymentsRoute.Detail(copy.id)
                    },
                    onExport = {
                        exporting = profile
                        exporter.launch(exportFileName(profile))
                    },
                    onDelete = {
                        store.delete(profile.id)
                        profiles = store.all()
                        route = DeploymentsRoute.List
                    },
                    onBack = { route = DeploymentsRoute.List },
                    modifier = modifier,
                )
                return
            }
        }

        DeploymentsRoute.List -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.deployments_title)) },
                actions = {
                    // Rare enough to be an icon, and it acts on the list as a
                    // whole rather than on any one deployment.
                    IconButton(
                        onClick = { importer.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag(TAG_IMPORT),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_import),
                            contentDescription = stringResource(R.string.action_import),
                        )
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
        modifier = modifier.fillMaxSize(),
    ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.deployments_empty),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .testTag(TAG_DEPLOYMENTS_EMPTY),
            )
        }

        LazyColumn(
            // Clear of the navigation bar and the button that sits over it.
            contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = {
                        Text(
                            text = pluralStringResource(
                                R.plurals.deployment_summary,
                                profile.packages.size,
                                profile.packages.size,
                                profile.locale,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Glyph(R.drawable.ic_chevron_right, MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier
                        .clickableRow { route = DeploymentsRoute.Detail(profile.id) }
                        .testTag("deployment-${profile.id}"),
                )
                HorizontalDivider()
            }

        }

        val newProfile = { route = DeploymentsRoute.Editor(DeploymentProfile(name = "", adminPinHash = ""), isNew = true) }
        // Extended while there is nothing to explain it, collapsing once the
        // list does the explaining.
        if (profiles.isEmpty()) {
            ExtendedFloatingActionButton(
                onClick = newProfile,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.deployments_new)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .testTag(TAG_NEW_DEPLOYMENT),
            )
        } else {
            FloatingActionButton(
                onClick = newProfile,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .testTag(TAG_NEW_DEPLOYMENT),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.deployments_new),
                )
            }
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

private sealed interface DeploymentsRoute {
    data object List : DeploymentsRoute
    data class Detail(val id: String) : DeploymentsRoute
    data class Editor(val profile: DeploymentProfile, val isNew: Boolean) : DeploymentsRoute
}

private fun exportFileName(profile: DeploymentProfile): String {
    val name = profile.name.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
    return "${name.ifBlank { "deployment" }}.json"
}

/** A 56dp button, 16dp clear of the bar below it and 16dp clear of the last row. */
private val FAB_CLEARANCE = 88.dp

const val TAG_NEW_DEPLOYMENT = "deployments-new"
const val TAG_IMPORT = "deployments-import"
const val TAG_DEPLOYMENTS_EMPTY = "deployments-empty"
