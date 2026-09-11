package org.awana.kiosk.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.PinGate

/**
 * Numeric PIN entry.
 *
 * A stock field with a number-password keyboard rather than a keypad of this
 * app's own: a bespoke twelve-key pad is a custom component with its own
 * layout, state and disabled handling, and the system keyboard is available in
 * lock task anyway. [PinGate] and its backoff are untouched.
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

    val enabled = lockoutMs == 0L && !checking

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
            // screen for long enough to look broken on a budget phone.
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

            OutlinedTextField(
                value = entered,
                onValueChange = { entered = it.filter(Char::isDigit).take(MAX_PIN) },
                enabled = enabled,
                singleLine = true,
                // Never in clear: a trainer types this in front of the people
                // the lock is meant to keep out.
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                isError = error != null,
                modifier = Modifier.fillMaxWidth().testTag(TAG_PIN_FIELD),
            )

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
                    .padding(top = 8.dp)
                    .testTag(TAG_PIN_MESSAGE),
            )

            Button(
                onClick = ::submit,
                enabled = enabled && entered.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag(TAG_PIN_SUBMIT),
            ) { Text(stringResource(R.string.action_unlock_admin)) }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel, modifier = Modifier.testTag(TAG_PIN_CANCEL)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

private const val MAX_PIN = 12

const val TAG_PIN_SCREEN = "pin-screen"
const val TAG_PIN_FIELD = "pin-field"
const val TAG_PIN_MESSAGE = "pin-message"
const val TAG_PIN_SUBMIT = "pin-submit"
const val TAG_PIN_CANCEL = "pin-cancel"
