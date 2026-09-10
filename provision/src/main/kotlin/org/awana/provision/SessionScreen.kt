package org.awana.provision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The live session: the QR to scan, and the dashboard of devices that have
 * reported back.
 *
 * The dashboard is a core feature rather than telemetry — it is what tells a
 * non-engineer that four of six phones are done and that it is safe to stop.
 */
@Composable
fun SessionScreen(profileId: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    val state by SessionService.session.state.collectAsState()
    var permissionRefused by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // The notification permission is asked for at the same time but is not
        // required: without it the session simply runs without its notification.
        if (results[hotspotPermission()] == true) SessionService.start(context, profileId)
        else permissionRefused = true
    }

    // Starting the session is a side effect, so it belongs in a LaunchedEffect
    // rather than in composition. A session already running for this profile is
    // left alone: restarting it would tear the hotspot down under the device
    // being enrolled.
    LaunchedEffect(profileId) {
        when {
            SessionService.session.isActiveFor(profileId) -> Unit
            hasPermission(context, hotspotPermission()) -> SessionService.start(context, profileId)
            else -> request.launch(sessionPermissions())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(TAG_SESSION),
    ) {
        val error = state.error
        val payload = state.qrPayload

        when {
            error != null || permissionRefused -> ManualFallback(
                message = error ?: stringResource(R.string.session_permission_refused),
                onStart = { ssid, passphrase ->
                    permissionRefused = false
                    SessionService.startManual(context, profileId, ssid, passphrase)
                },
                onCancel = {
                    SessionService.stop(context)
                    onFinished()
                },
            )

            payload == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(48.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.session_waiting),
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            else -> QrPanel(payload = payload, state = state)
        }

        if (state.running) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Dashboard(state = state)
        }

        OutlinedButton(
            onClick = {
                SessionService.stop(context)
                onFinished()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).testTag(TAG_SESSION_STOP),
        ) { Text(stringResource(R.string.session_stop)) }
    }
}

@Composable
private fun QrPanel(payload: String, state: SessionState) {
    val tooBig = payload.toByteArray().size > QrPayload.COMFORTABLE_BYTES
    val bitmap = remember(payload) { runCatching { QrPayload.render(payload) }.getOrNull() }

    Text(
        text = stringResource(R.string.session_scan),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    if (tooBig || bitmap == null) {
        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(TAG_QR_TOO_BIG)) {
            Text(
                text = stringResource(R.string.session_qr_too_big),
                modifier = Modifier.padding(16.dp),
            )
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // Nearest-neighbour scaling: a smoothed QR scans noticeably worse.
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(top = 16.dp)
                .testTag(TAG_QR),
        )
    }

    Text(
        text = stringResource(R.string.session_requires_android9),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    state.hotspot?.let {
        Text(stringResource(R.string.session_network, it.ssid), style = MaterialTheme.typography.bodySmall)
    }
    state.serverUrl?.let {
        Text(stringResource(R.string.session_server, it), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Dashboard(state: SessionState) {
    Text(
        text = stringResource(R.string.session_progress, state.succeeded, state.enrolled),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(TAG_PROGRESS),
    )

    if (state.enrolled > 0 && state.succeeded == state.enrolled) {
        Text(
            text = stringResource(R.string.session_safe_to_stop),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp).testTag(TAG_SAFE_TO_STOP),
        )
    }

    // A plain Column, not a LazyColumn: this sits inside a verticalScroll, which
    // measures with infinite height and makes any lazy list throw. A session is
    // a handful of phones, so there is nothing to virtualise.
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        state.reports.forEach { report ->
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(
                            R.string.report_device,
                            report.deviceLabel,
                            report.manufacturer,
                            report.model,
                        ),
                    )
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(
                                if (report.succeeded) R.string.report_ok else R.string.report_failed,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        report.installed.forEach {
                            Text(
                                "${it.packageName} ${it.versionName.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        report.failures.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        report.permissionFailures.forEach {
                            Text(
                                text = stringResource(R.string.report_permission_failed, it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (report.hostileOem != null) {
                            Text(
                                text = stringResource(R.string.report_battery_warning),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                modifier = Modifier.testTag("report-${report.deviceId}"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ManualFallback(
    message: String,
    onStart: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var ssid by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().testTag(TAG_MANUAL),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.session_manual_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.session_manual_help),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text(stringResource(R.string.session_manual_ssid)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TAG_MANUAL_SSID),
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text(stringResource(R.string.session_manual_passphrase)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TAG_MANUAL_PASSPHRASE),
        )
        Button(
            onClick = { onStart(ssid, passphrase) },
            enabled = ssid.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag(TAG_MANUAL_START),
        ) { Text(stringResource(R.string.deployment_start)) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/**
 * `startLocalOnlyHotspot` is refused outright without this, which is what made
 * every session fall through to the manual hotspot. The permission it wants
 * changed name at API 33.
 */
private fun hotspotPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

private fun sessionPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(hotspotPermission(), Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(hotspotPermission())
    }

private fun hasPermission(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

const val TAG_SESSION = "session"
const val TAG_QR = "session-qr"
const val TAG_QR_TOO_BIG = "session-qr-too-big"
const val TAG_PROGRESS = "session-progress"
const val TAG_SAFE_TO_STOP = "session-safe-to-stop"
const val TAG_SESSION_STOP = "session-stop"
const val TAG_MANUAL = "session-manual"
const val TAG_MANUAL_SSID = "session-manual-ssid"
const val TAG_MANUAL_PASSPHRASE = "session-manual-passphrase"
const val TAG_MANUAL_START = "session-manual-start"
