package org.awana.kiosk.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.Provisioning
import org.awana.kiosk.policy.UpdateProgress
import org.awana.kiosk.policy.UpdateState
import org.awana.kiosk.policy.Updates
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.InstallResult
import org.awana.kiosk.shared.KioskConfig

/**
 * Updating a phone that is already in service, from the trainer's code.
 *
 * The order is deliberate: scan, join, fetch, *then* ask. The deployment name
 * in the "move this phone" question comes out of hash-verified bytes, so a
 * printed code cannot claim to be a deployment it is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by UpdateProgress.state.collectAsState()

    var step by remember { mutableStateOf<Step>(Step.Scanning) }

    LaunchedEffect(Unit) { UpdateProgress.clear() }

    // Back is blocked from the moment the phone leaves its own Wi-Fi until the
    // update has finished: leaving early would strand the staged config and the
    // disabled networks with nothing left holding the thread that cleans them up.
    // The move question has its own Cancel, which does clean up.
    BackHandler(
        enabled = when (step) {
            Step.Preparing, is Step.Moving -> true
            Step.Applying -> !progress.finished
            else -> false
        },
    ) {}

    fun apply() {
        step = Step.Applying
        if (!Provisioning.applyStagedUpdate(context)) {
            step = Step.Refused(context.getString(R.string.update_could_not_start))
        }
    }

    when (val at = step) {
        Step.Scanning -> ScanScreen(
            onBack = onBack,
            onCode = { code ->
                step = Step.Preparing
                scope.launch {
                    Updates.prepare(context, code)
                        .onSuccess { incoming ->
                            val current = ConfigStore(context).load()
                            if (Updates.movesDeployment(current, incoming)) {
                                step = Step.Moving(incoming, current?.deploymentName.orEmpty())
                            } else {
                                apply()
                            }
                        }
                        .onFailure { step = Step.Refused(it.message.orEmpty()) }
                }
            },
        )

        Step.Preparing -> UpdateWorking(progress.describe(), onBack = null)

        is Step.Moving -> {
            UpdateWorking(progress.describe(), onBack = null)
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_move_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.update_move_body,
                            at.incoming.deploymentName,
                            at.was,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { apply() },
                        modifier = Modifier.testTag(TAG_UPDATE_MOVE_CONFIRM),
                    ) { Text(stringResource(R.string.update_move_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        Updates.finish(context)
                        onBack()
                    }) { Text(stringResource(R.string.action_cancel)) }
                },
                modifier = Modifier.testTag(TAG_UPDATE_MOVE),
            )
        }

        Step.Applying -> when (val now = progress) {
            is UpdateState.Done -> UpdateFinished(now.report, onBack)
            is UpdateState.Failed -> UpdateRefused(now.reason, onBack)
            else -> UpdateWorking(now.describe(), onBack = null)
        }

        is Step.Refused -> UpdateRefused(at.message, onBack)
    }
}

private sealed interface Step {
    data object Scanning : Step
    data object Preparing : Step
    data class Moving(val incoming: KioskConfig, val was: String) : Step
    data object Applying : Step
    data class Refused(val message: String) : Step
}

@Composable
private fun UpdateState.describe(): String = when (this) {
    UpdateState.JoiningWifi -> stringResource(R.string.update_joining)
    UpdateState.FetchingSettings -> stringResource(R.string.update_fetching)
    is UpdateState.Installing -> stringResource(R.string.update_installing, packageName, at, of)
    UpdateState.ApplyingSettings, UpdateState.Idle -> stringResource(R.string.update_applying)
    is UpdateState.Done, is UpdateState.Failed -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_UPDATE_BACK)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.fillMaxSize().testTag(TAG_UPDATE),
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) { content() }
    }
}

/**
 * The three states below are public, not private helpers: the launcher's tests
 * and screenshots live in `:kiosk:app`, so `internal` would not reach them, and
 * driving them directly is the only way to see a finished update without a
 * camera and a trainer.
 */
@Composable
fun UpdateWorking(what: String, onBack: (() -> Unit)?) {
    Shell(stringResource(R.string.admin_update), onBack) {
        CircularProgressIndicator(modifier = Modifier.padding(top = 48.dp))
        Text(
            text = what,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(TAG_UPDATE_STEP),
        )
        Text(
            text = stringResource(R.string.update_hold_on),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun UpdateFinished(report: EnrolmentReport, onBack: () -> Unit) {
    val changed = report.packageOutcomes.count {
        it.result == InstallResult.Installed || it.result == InstallResult.Updated
    }
    val current = report.packageOutcomes.count { it.result == InstallResult.AlreadyCurrent }

    Shell(stringResource(R.string.admin_update), onBack) {
        Verdict(
            healthy = report.failures.isEmpty(),
            headline = if (report.failures.isEmpty()) {
                stringResource(R.string.update_done)
            } else {
                stringResource(R.string.update_problem)
            },
            body = when {
                report.failures.isNotEmpty() -> report.failures.joinToString("\n\n")
                changed == 0 -> stringResource(R.string.update_done_nothing)
                else -> listOfNotNull(
                    pluralStringResource(R.plurals.update_result_updated, changed, changed),
                    current.takeIf { it > 0 }?.let {
                        pluralStringResource(R.plurals.update_result_current, it, it)
                    },
                ).joinToString("\n")
            },
        )
        Button(onClick = onBack, modifier = Modifier.testTag(TAG_UPDATE_CLOSE)) {
            Text(stringResource(R.string.action_close))
        }
    }
}

@Composable
fun UpdateRefused(message: String, onBack: () -> Unit) {
    Shell(stringResource(R.string.admin_update), onBack) {
        Verdict(
            healthy = false,
            headline = stringResource(R.string.update_problem),
            body = message,
        )
        Button(onClick = onBack, modifier = Modifier.testTag(TAG_UPDATE_CLOSE)) {
            Text(stringResource(R.string.action_close))
        }
    }
}

const val TAG_UPDATE = "update"
const val TAG_UPDATE_BACK = "update-back"
const val TAG_UPDATE_STEP = "update-step"
const val TAG_UPDATE_CLOSE = "update-close"
const val TAG_UPDATE_MOVE = "update-move"
const val TAG_UPDATE_MOVE_CONFIRM = "update-move-confirm"
