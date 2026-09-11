package org.awana.provision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.awana.kiosk.design.okColors

/**
 * The live session: the code to scan, the three steps written out, and one line
 * about how it is going.
 *
 * There is deliberately no total and no completion state. The app never learns
 * how many phones a trainer means to set up, so it must not invent a
 * denominator or take the code away. "Safe to stop" means nothing is in flight,
 * which is derivable; "you are finished" is not.
 */
@Composable
fun SessionScreen(profileId: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    val state by SessionService.session.state.collectAsState()
    var permissionRefused by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<SessionRoute>(SessionRoute.Session) }
    var confirmStop by remember { mutableStateOf(false) }

    // Silence is what makes a phone "stopped responding", so the screen has to
    // re-read the clock even when nothing arrives.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }

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

    val stop = {
        SessionService.stop(context)
        onFinished()
    }
    val attemptStop = {
        if (state.inFlight(now).isEmpty()) stop() else confirmStop = true
    }

    when (val here = route) {
        SessionRoute.Session -> SessionBody(
            state = state,
            now = now,
            permissionRefused = permissionRefused,
            onManualStart = { ssid, passphrase ->
                permissionRefused = false
                SessionService.startManual(context, profileId, ssid, passphrase)
            },
            onOpenPhones = { route = SessionRoute.Phones },
            onStop = attemptStop,
            onCancel = stop,
        )

        SessionRoute.Phones -> {
            BackHandler { route = SessionRoute.Session }
            PhonesScreen(
                phones = state.phones(now),
                onOpen = { route = SessionRoute.Detail(it.deviceId) },
                onBack = { route = SessionRoute.Session },
            )
        }

        is SessionRoute.Detail -> {
            BackHandler { route = SessionRoute.Phones }
            val report = state.reports.firstOrNull { it.deviceId == here.deviceId }
            if (report == null) {
                LaunchedEffect(here.deviceId) { route = SessionRoute.Phones }
            } else {
                PhoneDetailScreen(
                    report = report,
                    expected = rememberExpectedApps(profileId),
                    onBack = { route = SessionRoute.Phones },
                )
            }
        }
    }

    if (confirmStop) {
        val busy = state.inFlight(now).size
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(pluralStringResource(R.plurals.session_stop_confirm_title, busy, busy))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.session_stop_confirm_body))
                    Text(stringResource(R.string.session_stop_confirm_wait))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmStop = false; stop() },
                    modifier = Modifier.testTag(TAG_STOP_ANYWAY),
                ) {
                    Text(
                        text = stringResource(R.string.session_stop_anyway),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.session_stop_keep_going))
                }
            },
            modifier = Modifier.testTag(TAG_STOP_CONFIRM),
        )
    }
}

/** Internal rather than private so its four states can be driven directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionBody(
    state: SessionState,
    now: Long,
    permissionRefused: Boolean,
    onManualStart: (String, String) -> Unit,
    onOpenPhones: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = state.inFlight(now).isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.profileName.ifBlank { stringResource(R.string.session_title) }) },
                navigationIcon = {
                    IconButton(onClick = onStop, modifier = Modifier.testTag(TAG_SESSION_CLOSE)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.session_stop),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                OutlinedButton(
                    onClick = onStop,
                    colors = if (busy) {
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag(TAG_SESSION_STOP),
                ) {
                    Text(
                        stringResource(
                            if (busy) R.string.session_stop_busy else R.string.session_stop,
                        ),
                    )
                }
            }
        },
        modifier = Modifier.testTag(TAG_SESSION),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val error = state.error
            val payload = state.qrPayload

            when {
                error != null || permissionRefused -> ManualFallback(
                    message = error ?: stringResource(R.string.session_permission_refused),
                    onStart = onManualStart,
                    onCancel = onCancel,
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

                else -> {
                    Qr(payload)
                    Steps()
                    Status(
                        state = state,
                        now = now,
                        onOpen = onOpenPhones,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Qr(payload: String) {
    val tooBig = payload.toByteArray().size > QrPayload.COMFORTABLE_BYTES
    val bitmap = remember(payload) { runCatching { QrPayload.render(payload) }.getOrNull() }

    if (tooBig || bitmap == null) {
        Card(modifier = Modifier.fillMaxWidth().testTag(TAG_QR_TOO_BIG)) {
            Text(
                text = stringResource(R.string.session_qr_too_big),
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // The one colour here that is not a theme role: a camera needs a light
        // quiet zone around the code, in dark mode as much as light.
        Surface(color = Color.White, modifier = Modifier.size(QR_SIZE + 20.dp)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // Nearest-neighbour scaling: a smoothed QR scans noticeably worse.
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                modifier = Modifier.size(QR_SIZE).padding(10.dp).testTag(TAG_QR),
            )
        }
    }
}

@Composable
private fun Steps() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Step(1, R.string.session_step_1)
            Step(2, R.string.session_step_2, figure = true)
            Step(3, R.string.session_step_3)
        }
    }
}

@Composable
private fun Step(number: Int, text: Int, figure: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            // The copy bolds the words a trainer must not skim past, so the
            // markup has to be rendered rather than shown.
            text = AnnotatedString.fromHtml(stringResource(text)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (figure) {
            Icon(
                painter = painterResource(R.drawable.ic_tap_six_times),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(width = 42.dp, height = 52.dp),
            )
        }
    }
}

/**
 * One component, four states, chosen by what is most worth knowing: a problem
 * beats anything in flight beats everything finished beats nothing yet. The
 * second line carries whatever the headline displaced, so no count is lost.
 */
@Composable
private fun Status(
    state: SessionState,
    now: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.status(now)
    val busy = state.inFlight(now).size
    val colors = MaterialTheme.colorScheme

    val container = when (status) {
        SessionStatus.Idle -> colors.surfaceContainerHigh
        SessionStatus.InProgress -> colors.secondaryContainer
        SessionStatus.Complete -> MaterialTheme.okColors.container
        SessionStatus.Problem -> colors.errorContainer
    }
    val onContainer = when (status) {
        SessionStatus.Idle -> colors.onSurface
        SessionStatus.InProgress -> colors.onSecondaryContainer
        SessionStatus.Complete -> MaterialTheme.okColors.onContainer
        SessionStatus.Problem -> colors.onErrorContainer
    }
    val headline = when (status) {
        SessionStatus.Idle -> stringResource(R.string.session_idle)
        SessionStatus.InProgress -> pluralStringResource(R.plurals.session_busy, busy, busy)
        SessionStatus.Complete ->
            pluralStringResource(R.plurals.session_done, state.succeeded, state.succeeded)
        SessionStatus.Problem ->
            pluralStringResource(R.plurals.session_problem, state.failed, state.failed)
    }
    val body = when (status) {
        SessionStatus.Idle -> stringResource(R.string.session_idle_body)
        SessionStatus.InProgress -> stringResource(R.string.session_busy_body, state.enrolled)
        SessionStatus.Complete -> stringResource(R.string.session_done_body)
        SessionStatus.Problem -> stringResource(R.string.session_problem_body, state.succeeded)
    }

    Surface(
        color = container,
        contentColor = onContainer,
        shape = MaterialTheme.shapes.medium,
        // Idle alone has nothing to open, so idle alone is not a target.
        onClick = onOpen,
        enabled = status != SessionStatus.Idle,
        modifier = modifier.fillMaxWidth().testTag(TAG_STATUS),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                when (status) {
                    SessionStatus.InProgress -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    SessionStatus.Problem -> Glyph(R.drawable.ic_warning, onContainer)
                    SessionStatus.Complete -> Glyph(R.drawable.ic_check, onContainer)
                    SessionStatus.Idle -> Glyph(R.drawable.ic_phone_quiet, onContainer)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (status != SessionStatus.Idle) {
                Glyph(R.drawable.ic_chevron_right, onContainer)
            }
        }
    }
}

@Composable
private fun rememberExpectedApps(profileId: String): Map<String, String> {
    val context = LocalContext.current
    return remember(profileId) {
        val profile = ProfileStore(context).get(profileId)
        val library = ApkLibrary(context)
        profile?.packages.orEmpty().associateWith { packageName ->
            library.find(packageName)?.label ?: packageName
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

private sealed interface SessionRoute {
    data object Session : SessionRoute
    data object Phones : SessionRoute
    data class Detail(val deviceId: String) : SessionRoute
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

private val QR_SIZE = 240.dp
private const val TICK_MS = 15_000L

const val TAG_SESSION = "session"
const val TAG_QR = "session-qr"
const val TAG_QR_TOO_BIG = "session-qr-too-big"
const val TAG_STATUS = "session-status"
const val TAG_SESSION_STOP = "session-stop"
const val TAG_SESSION_CLOSE = "session-close"
const val TAG_STOP_CONFIRM = "session-stop-confirm"
const val TAG_STOP_ANYWAY = "session-stop-anyway"
const val TAG_MANUAL = "session-manual"
const val TAG_MANUAL_SSID = "session-manual-ssid"
const val TAG_MANUAL_PASSPHRASE = "session-manual-passphrase"
const val TAG_MANUAL_START = "session-manual-start"
