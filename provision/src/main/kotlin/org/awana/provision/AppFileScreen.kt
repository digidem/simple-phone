package org.awana.provision

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One file in the library.
 *
 * The fingerprint and the deployments that depend on it sit on the same screen
 * as the two actions that can break them, rather than a tap away: replacing
 * with a differently-signed build and removing a file in use are both
 * routine-looking taps with consequences a trainer cannot otherwise see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFileScreen(
    entry: ApkEntry,
    usedIn: List<DeploymentProfile>,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.label) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    OutlinedButton(
                        onClick = onReplace,
                        modifier = Modifier.weight(1f).testTag(TAG_FILE_REPLACE),
                    ) { Text(stringResource(R.string.apk_replace)) }
                    Button(
                        onClick = onRemove,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.testTag(TAG_FILE_REMOVE),
                    ) { Text(stringResource(R.string.apk_remove)) }
                }
            }
        },
        modifier = modifier.testTag(TAG_FILE_DETAIL),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Fact(R.string.apk_package, entry.packageName)
            Fact(
                R.string.apk_version,
                "${entry.versionName ?: "?"} (${entry.versionCode})",
            )
            Fact(R.string.apk_size, stringResource(R.string.apps_size, entry.sizeBytes / 1_000_000))

            SectionHeader(stringResource(R.string.apps_fingerprint))
            Text(
                // Grouped in fours and wrapped, so it can be read aloud and
                // checked against whoever published the build.
                text = entry.readableFingerprint,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("fingerprint-${entry.packageName}"),
            )
            Text(
                text = stringResource(R.string.apk_fingerprint_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )

            SectionHeader(stringResource(R.string.apk_used_in))
            if (usedIn.isEmpty()) {
                Text(
                    text = stringResource(R.string.apk_used_in_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            usedIn.forEach { profile ->
                ListItem(headlineContent = { Text(profile.name) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Fact(label: Int, value: String) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Text(value, style = MaterialTheme.typography.bodyMedium) },
    )
}

/** "In 3 deployments" is the only warning before a trainer opens one and changes it. */
@Composable
fun apkSummary(entry: ApkEntry, usedIn: Int): String {
    val size = stringResource(R.string.apps_size, entry.sizeBytes / 1_000_000)
    val used = if (usedIn == 0) {
        stringResource(R.string.apk_used_in_none_short)
    } else {
        pluralStringResource(R.plurals.apk_in_deployments, usedIn, usedIn)
    }
    return "${entry.versionName ?: "?"} · $size · $used"
}

const val TAG_FILE_DETAIL = "app-file"
const val TAG_FILE_REPLACE = "app-file-replace"
const val TAG_FILE_REMOVE = "app-file-remove"
