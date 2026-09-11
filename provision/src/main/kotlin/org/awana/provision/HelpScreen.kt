package org.awana.provision

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The walkthrough, written for a partner trainer rather than an engineer. The
 * six-tap step is the one people get wrong, so it gets its own line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(modifier: Modifier = Modifier) {
    val steps = listOf(
        R.string.help_step1,
        R.string.help_step2,
        R.string.help_step3,
        R.string.help_step4,
        R.string.help_step5,
        R.string.help_step6,
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.help_title)) }) },
        contentWindowInsets = WindowInsets(0),
        modifier = modifier.fillMaxSize(),
    ) { padding ->
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(TAG_HELP),
    ) {
        steps.forEach { step ->
            Text(stringResource(step), style = MaterialTheme.typography.bodyLarge)
        }

        Card(modifier = Modifier.padding(top = 16.dp)) {
            Text(
                text = stringResource(R.string.help_wifi),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Card {
            Text(
                text = stringResource(R.string.help_power),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Text(
            text = stringResource(R.string.session_requires_android9),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
}

const val TAG_HELP = "help-screen"
