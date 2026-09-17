package org.awana.kiosk.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import org.awana.kiosk.shared.InstallResult
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.awana.kiosk.design.okColors
import org.awana.kiosk.shared.SetupReport

/**
 * Every phone the session knows about. Reference rather than something to
 * watch: a trainer opens it when the status line says something they want
 * explained.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonesScreen(
    phones: List<Phone>,
    onOpen: (SetupReport) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.phones_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        modifier = modifier.testTag(TAG_PHONES),
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(phones, key = ::keyOf) { phone ->
                when (phone) {
                    // No name and no chevron: it has not said who it is yet,
                    // and there is nothing to open.
                    is Phone.Copying -> PhoneRow(
                        headline = stringResource(R.string.phone_unnamed),
                        supporting = phone.device.step?.let {
                            stringResource(R.string.phone_copying, it, phone.device.percent)
                        } ?: stringResource(R.string.phone_starting),
                        leading = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) },
                    )

                    is Phone.Silent -> PhoneRow(
                        headline = stringResource(R.string.phone_unnamed),
                        supporting = stringResource(
                            R.string.phone_silent,
                            phone.silentForMs / 60_000,
                            phone.device.percent,
                        ),
                        leading = { Glyph(R.drawable.ic_phone_quiet, MaterialTheme.colorScheme.onSurfaceVariant) },
                    )

                    is Phone.Reported -> {
                        val report = phone.report
                        PhoneRow(
                            headline = "${report.manufacturer} ${report.model}",
                            supporting = if (report.succeeded) {
                                stringResource(R.string.phone_ok)
                            } else {
                                report.failures.firstOrNull()
                                    ?: stringResource(R.string.phone_problem)
                            },
                            leading = {
                                if (report.succeeded) {
                                    Glyph(R.drawable.ic_check, MaterialTheme.colorScheme.primary)
                                } else {
                                    Glyph(R.drawable.ic_warning, MaterialTheme.colorScheme.error)
                                }
                            },
                            onOpen = { onOpen(report) },
                            testTag = "phone-${report.deviceId}",
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PhoneRow(
    headline: String,
    supporting: String,
    leading: @Composable () -> Unit,
    onOpen: (() -> Unit)? = null,
    testTag: String? = null,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = leading,
        trailingContent = onOpen?.let {
            { Glyph(R.drawable.ic_chevron_right, MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        modifier = Modifier
            .then(if (onOpen != null) Modifier.clickableRow(onOpen) else Modifier)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    )
}

/** What one phone did, leading with what happened rather than with the facts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneDetailScreen(
    report: SetupReport,
    /** Every app the deployment asks for, package name to the name a trainer knows it by. */
    expected: Map<String, String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val missing = expected.keys - report.installed.map { it.packageName }.toSet()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${report.manufacturer} ${report.model}") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        modifier = modifier.testTag(TAG_PHONE_DETAIL),
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Verdict(report, modifier = Modifier.padding(16.dp))
            }

            item { SectionHeader(stringResource(R.string.phone_apps_installed)) }
            items(report.installed, key = { it.packageName }) {
                // Empty on reports from before updating existed, which is why
                // the version is still what the row leads with.
                val outcome = report.packageOutcomes.firstOrNull { o -> o.packageName == it.packageName }
                ListItem(
                    headlineContent = { Text(expected[it.packageName] ?: it.packageName) },
                    supportingContent = outcome?.let { o -> { Text(stringResource(o.result.label())) } },
                    trailingContent = { Text(it.versionName.orEmpty()) },
                )
            }
            // Everything that did work is still listed, so the one that did not
            // is read in context rather than on its own.
            items(missing.toList(), key = { it }) { packageName ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = expected[packageName] ?: packageName,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    trailingContent = {
                        Text(
                            text = stringResource(R.string.phone_not_installed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }

            item { SectionHeader(stringResource(R.string.phone_settings_applied)) }
            items(report.policiesApplied, key = { it }) {
                ListItem(headlineContent = { Text(it) })
            }
        }
    }
}

@Composable
private fun Verdict(report: SetupReport, modifier: Modifier = Modifier) {
    val ok = report.succeeded
    Surface(
        color = if (ok) {
            MaterialTheme.okColors.container
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (ok) {
            MaterialTheme.okColors.onContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = if (ok) {
                    stringResource(R.string.phone_ok)
                } else {
                    report.failures.firstOrNull() ?: stringResource(R.string.phone_problem)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (ok) {
                Text(stringResource(R.string.phone_ok_body))
            } else {
                report.failures.drop(1).forEach { Text(it) }
                report.permissionFailures.forEach {
                    Text(stringResource(R.string.report_permission_failed, it))
                }
            }
        }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_BACK)) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.action_back),
        )
    }
}

@Composable
internal fun Glyph(id: Int, tint: Color) {
    Icon(painter = painterResource(id), contentDescription = null, tint = tint)
}

private fun keyOf(phone: Phone): String = when (phone) {
    is Phone.Copying -> "downloading-${phone.device.address}"
    is Phone.Silent -> "downloading-${phone.device.address}"
    is Phone.Reported -> "reported-${phone.report.deviceId}"
}

const val TAG_PHONES = "phones"
const val TAG_PHONE_DETAIL = "phone-detail"
const val TAG_BACK = "back"

/**
 * A phone that took the new build and one that already had it look identical
 * in the installed list, and on an update session that is the difference a
 * trainer is looking for.
 */
private fun InstallResult.label() = when (this) {
    InstallResult.Installed -> R.string.phone_app_installed
    InstallResult.Updated -> R.string.phone_app_updated
    InstallResult.AlreadyCurrent -> R.string.phone_app_already_current
    InstallResult.Failed -> R.string.phone_app_failed
}
