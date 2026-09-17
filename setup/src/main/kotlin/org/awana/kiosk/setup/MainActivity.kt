package org.awana.kiosk.setup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.awana.kiosk.design.AwanaTheme
import org.awana.kiosk.shared.Telemetry

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Telemetry.init(this)
        SessionService.ensureSession(this)
        setContent { ProvisionTheme { ProvisionApp() } }
    }
}

private enum class Tab { Deployments, Apps, Help }

/** Internal rather than private so the whole shell, tabs and all, can be driven. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProvisionApp() {
    var tab by remember { mutableStateOf(Tab.Deployments) }
    // The session screen takes over the whole app while it is running: a
    // trainer holding a phone up to another phone should not be able to
    // navigate away from the code by accident.
    var sessionProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val sessionState by SessionService.session.state.collectAsState()

    // A session outlives this activity, so a rotation or a recreation comes
    // back to the session already running rather than starting a second one.
    val current = sessionProfileId ?: sessionState.profileId
    if (current != null) {
        SessionScreen(profileId = current, onFinished = { sessionProfileId = null })
        return
    }

    // No app bar here: each screen draws its own, so a screen opened inside a
    // tab replaces the title rather than stacking a second bar under it.
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                painter = painterResource(iconFor(entry)),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(labelFor(entry))) },
                        modifier = Modifier.testTag("tab-${entry.name.lowercase()}"),
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)
        when (tab) {
            Tab.Deployments -> DeploymentsScreen(
                modifier = content,
                onStartSession = { sessionProfileId = it },
            )

            Tab.Apps -> AppsScreen(modifier = content)
            Tab.Help -> HelpScreen(modifier = content)
        }
    }
}

private fun iconFor(tab: Tab) = when (tab) {
    Tab.Deployments -> R.drawable.ic_tab_deployments
    Tab.Apps -> R.drawable.ic_tab_apps
    Tab.Help -> R.drawable.ic_tab_help
}

private fun labelFor(tab: Tab) = when (tab) {
    Tab.Deployments -> R.string.tab_deployments
    Tab.Apps -> R.string.tab_apps
    Tab.Help -> R.string.tab_help
}

/** The trainer's own phone, so this one follows the system setting. */
@Composable
fun ProvisionTheme(content: @Composable () -> Unit) {
    AwanaTheme(content = content)
}
