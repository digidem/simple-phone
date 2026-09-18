package org.awana.kiosk.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The one action on the phone that cannot be taken back, so it asks for more
 * than a tap: the consequence in words, and a box to tick before the button
 * works. [suggestUnlock] points at the reversible alternative where there is
 * one — a phone that was never set up has nothing to unlock.
 */
@Composable
fun RemoveLockDialog(suggestUnlock: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_lock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.remove_lock_body))
                if (suggestUnlock) {
                    Text(
                        text = stringResource(R.string.remove_lock_unlock_instead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = understood, role = Role.Checkbox) { understood = it }
                        .testTag(TAG_REMOVE_LOCK_UNDERSTOOD),
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Text(stringResource(R.string.remove_lock_understand))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = understood,
                modifier = Modifier.testTag(TAG_REMOVE_LOCK_CONFIRM),
            ) {
                Text(
                    text = stringResource(R.string.remove_lock_confirm),
                    color = if (understood) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag(TAG_REMOVE_LOCK_DIALOG),
    )
}

const val TAG_REMOVE_LOCK_DIALOG = "remove-lock-dialog"
const val TAG_REMOVE_LOCK_UNDERSTOOD = "remove-lock-understood"
const val TAG_REMOVE_LOCK_CONFIRM = "remove-lock-confirm"
