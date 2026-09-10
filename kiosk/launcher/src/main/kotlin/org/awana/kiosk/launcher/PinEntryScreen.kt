package org.awana.kiosk.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.PinGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Numeric PIN entry, on the app's own keypad rather than the system keyboard —
 * the IME is another surface a locked-down device does not need.
 */
@Composable
fun PinEntryScreen(onUnlocked: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val gate = remember { PinGate(context) }
    val hash = remember { ConfigStore(context).load()?.adminPinHash }

    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockoutMs by remember { mutableStateOf(gate.lockoutRemainingMs()) }

    LaunchedEffect(lockoutMs) {
        while (lockoutMs > 0) {
            delay(500)
            lockoutMs = gate.lockoutRemainingMs()
        }
    }

    fun submit() {
        val encoded = hash
        if (encoded == null) {
            error = context.getString(R.string.pin_no_config)
            return
        }
        if (checking) return
        scope.launch {
            checking = true
            // 120k PBKDF2 iterations: on the main thread this freezes the
            // keypad for long enough to look broken on a budget phone.
            val ok = withContext(Dispatchers.Default) { gate.check(entered, encoded) }
            checking = false
            if (ok) {
                onUnlocked()
            } else {
                entered = ""
                lockoutMs = gate.lockoutRemainingMs()
                error = context.getString(R.string.pin_wrong)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .testTag(TAG_PIN_SCREEN),
        ) {
            Text(
                text = stringResource(R.string.pin_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "•".repeat(entered.length),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag(TAG_PIN_DOTS),
            )
            Spacer(Modifier.height(16.dp))

            val message = when {
                lockoutMs > 0 -> stringResource(R.string.pin_locked_out, (lockoutMs / 1000) + 1)
                error != null -> error!!
                else -> ""
            }
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(TAG_PIN_MESSAGE),
            )

            Keypad(
                enabled = lockoutMs == 0L && !checking,
                onDigit = { if (entered.length < MAX_PIN) entered += it },
                onDelete = { entered = entered.dropLast(1) },
                onSubmit = ::submit,
            )

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel, modifier = Modifier.testTag(TAG_PIN_CANCEL)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row {
                row.forEach { digit -> KeyButton(digit.toString(), enabled) { onDigit(digit) } }
            }
        }
        Row {
            KeyButton("⌫", enabled, onClick = onDelete)
            KeyButton("0", enabled) { onDigit('0') }
            KeyButton("✓", enabled, onClick = onSubmit)
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.padding(6.dp)) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(76.dp)
                .testTag("pin-key-$label"),
        ) {
            Text(label, fontSize = 24.sp)
        }
    }
}

private const val MAX_PIN = 12

const val TAG_PIN_SCREEN = "pin-screen"
const val TAG_PIN_DOTS = "pin-dots"
const val TAG_PIN_MESSAGE = "pin-message"
const val TAG_PIN_CANCEL = "pin-cancel"
