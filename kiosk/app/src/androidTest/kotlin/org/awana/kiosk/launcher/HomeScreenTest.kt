package org.awana.kiosk.launcher

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.awana.kiosk.policy.DevicePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    companion object {
        /**
         * Lock task refuses to start the compose rule's host activity. These
         * tests exercise the composables directly, so the lock is irrelevant to
         * what they assert — but it does stop them running at all.
         *
         * This has to be `@BeforeClass` rather than `@Before`: JUnit rules are
         * applied outside `@Before`, so the compose rule launches its activity
         * first and fails before any per-test setup could unlock the device.
         */
        @JvmStatic
        @BeforeClass
        fun leaveLockTask() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val policy = DevicePolicy(context)
            if (policy.isDeviceOwner) {
                val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                dpm.setLockTaskPackages(policy.admin, emptyArray())
            }
        }
    }

    private fun app(packageName: String, label: String) =
        LaunchableApp(packageName, label, ImageBitmap(1, 1))

    @Test
    fun showsAnIconForEveryVisibleApp() {
        compose.setContent {
            KioskTheme {
                HomeScreen(
                    apps = listOf(app("com.comapeo", "CoMapeo"), app("org.telegram.messenger", "Telegram")),
                    onLaunch = {},
                    onAdminGesture = {},
                )
            }
        }

        compose.onNodeWithTag(tagFor("com.comapeo")).assertIsDisplayed()
        compose.onNodeWithTag(tagFor("org.telegram.messenger")).assertIsDisplayed()
    }

    @Test
    fun tappingAnIconLaunchesThatApp() {
        var launched: String? = null
        compose.setContent {
            KioskTheme {
                HomeScreen(
                    apps = listOf(app("com.comapeo", "CoMapeo")),
                    onLaunch = { launched = it },
                    onAdminGesture = {},
                )
            }
        }

        compose.onNodeWithTag(tagFor("com.comapeo")).performClick()

        assertEquals("com.comapeo", launched)
    }

    @Test
    fun anEmptyDeploymentSaysSoRatherThanShowingABlankScreen() {
        compose.setContent {
            KioskTheme { HomeScreen(apps = emptyList(), onLaunch = {}, onAdminGesture = {}) }
        }

        compose.onNodeWithTag(TAG_EMPTY).assertIsDisplayed()
    }

    @Test
    fun aBriefPressOnTheAdminCornerDoesNothing() {
        var triggered = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KioskTheme { HomeScreen(apps = emptyList(), onLaunch = {}, onAdminGesture = { triggered = true }) }
        }

        compose.onNodeWithTag(TAG_ADMIN_CORNER).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag(TAG_ADMIN_CORNER).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals("a one-second press must not open the admin screen", false, triggered)
    }

    @Test
    fun holdingTheAdminCornerForFiveSecondsOpensAdmin() {
        var triggered = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KioskTheme { HomeScreen(apps = emptyList(), onLaunch = {}, onAdminGesture = { triggered = true }) }
        }

        compose.onNodeWithTag(TAG_ADMIN_CORNER).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(6_000)

        assertEquals(true, triggered)
    }

    @Test
    fun theAdminCornerHasNoVisibleAffordance() {
        compose.setContent {
            KioskTheme { HomeScreen(apps = emptyList(), onLaunch = {}, onAdminGesture = {}) }
        }

        // No label and no content description: a user who cannot read must not
        // be able to stumble into it.
        val node = compose.onNodeWithTag(TAG_ADMIN_CORNER).fetchSemanticsNode()
        assertNull(
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription),
        )
        assertNull(node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text))
    }
}

private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
    key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
): T? = if (contains(key)) this[key] else null
