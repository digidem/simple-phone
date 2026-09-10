package org.awana.provision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionService.ensureSession(this)
        setContent { ProvisionTheme { ProvisionApp() } }
    }
}

private enum class Tab { Deployments, Apps, Help }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvisionApp() {
    var tab by remember { mutableStateOf(Tab.Deployments) }
    // The session screen takes over the whole app while it is running: a
    // trainer holding a phone up to another phone should not be able to
    // navigate away from the code by accident.
    var sessionProfileId by remember { mutableStateOf<String?>(null) }

    val current = sessionProfileId
    if (current != null) {
        SessionScreen(profileId = current, onFinished = { sessionProfileId = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(titleFor(tab))) })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
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

private fun titleFor(tab: Tab) = when (tab) {
    Tab.Deployments -> R.string.deployments_title
    Tab.Apps -> R.string.apps_title
    Tab.Help -> R.string.help_title
}

private fun labelFor(tab: Tab) = when (tab) {
    Tab.Deployments -> R.string.tab_deployments
    Tab.Apps -> R.string.tab_apps
    Tab.Help -> R.string.tab_help
}

@Composable
fun ProvisionTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(primary = Color(0xFF7FB2D6), background = Color(0xFF101416))
        } else {
            lightColorScheme(primary = Color(0xFF0B3C5D))
        },
        content = content,
    )
}
