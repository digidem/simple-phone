package app.comapeo.kiosk.launcher

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A canary for the test harness itself, not for any app behaviour.
 *
 * On a device where this app is Device Owner, the HOME activity and holding
 * lock task, Compose's test harness sees no compose hierarchy at all and every
 * UI test fails with "No compose hierarchies found" — which reads like a bug in
 * the screen under test and is not. If that happens, run this first: if it
 * fails too, the problem is the device, not the UI. Use the clean AVD.
 * See tools/test.sh.
 */
@RunWith(AndroidJUnit4::class)
class ComposeSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theHarnessCanSeeAComposeNode() {
        compose.setContent { Text("hello", modifier = Modifier.testTag("smoke")) }
        compose.waitForIdle()
        compose.onNodeWithTag("smoke").assertIsDisplayed()
    }
}
