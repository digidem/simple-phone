package org.awana.kiosk.launcher

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.shared.LauncherRole
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

    private fun app(
        packageName: String,
        label: String,
        role: LauncherRole = LauncherRole.SMALL,
        subtitle: String? = null,
    ) = LaunchableApp(packageName, role, label, subtitle, ImageBitmap(1, 1))

    private fun apps(vararg app: LaunchableApp) = HomeState.Apps(
        hero = app.firstOrNull { it.role == LauncherRole.HERO },
        small = app.filter { it.role == LauncherRole.SMALL },
    )

    @Composable
    private fun Home(
        state: HomeState,
        onLaunch: (String) -> Unit = {},
        onSetUpAgain: () -> Unit = {},
        onRemoveLock: () -> Unit = {},
        onAdminGesture: () -> Unit = {},
        onScanCode: () -> Unit = {},
        onRelock: () -> Unit = {},
    ) = HomeScreen(
        state,
        onLaunch,
        onSetUpAgain,
        onRemoveLock,
        onAdminGesture,
        onScanCode = onScanCode,
        onRelock = onRelock,
    )

    @Test
    fun showsTheHeroAndEverySmallApp() {
        compose.setContent {
            KioskTheme {
                Home(
                    apps(
                        app("com.comapeo", "CoMapeo", LauncherRole.HERO, "Maps and recordings"),
                        app("org.telegram.messenger", "Telegram"),
                        app("org.osmand", "Offline maps"),
                    ),
                )
            }
        }

        compose.onNodeWithTag(tagFor("com.comapeo")).assertIsDisplayed()
        compose.onNodeWithTag(tagFor("org.telegram.messenger")).assertIsDisplayed()
        compose.onNodeWithTag(tagFor("org.osmand")).assertIsDisplayed()
        compose.onNodeWithText("Maps and recordings").assertIsDisplayed()
    }

    @Test
    fun theSubtitleIsTheHerosAlone() {
        compose.setContent {
            KioskTheme {
                Home(apps(app("org.telegram.messenger", "Telegram", subtitle = "Messages")))
            }
        }

        compose.onNodeWithText("Messages").assertDoesNotExist()
    }

    @Test
    fun aDeploymentWithNoHeroShowsJustTheGrid() {
        compose.setContent {
            KioskTheme { Home(apps(app("org.telegram.messenger", "Telegram"))) }
        }

        compose.onNodeWithTag(tagFor("org.telegram.messenger")).assertIsDisplayed()
    }

    @Test
    fun tappingAnAppLaunchesIt() {
        var launched: String? = null
        compose.setContent {
            KioskTheme {
                Home(
                    apps(app("com.comapeo", "CoMapeo", LauncherRole.HERO)),
                    onLaunch = { launched = it },
                )
            }
        }

        compose.onNodeWithTag(tagFor("com.comapeo")).performClick()

        assertEquals("com.comapeo", launched)
    }

    @Test
    fun aPhoneThatWasNeverSetUpSaysSoAndOffersNoRetry() {
        compose.setContent {
            KioskTheme { Home(HomeState.NotSetUp(canSetUpAgain = false)) }
        }

        compose.onNodeWithTag(TAG_NOT_SET_UP).assertIsDisplayed()
        // Nothing was ever scanned, so there is no bootstrap to re-run.
        compose.onNodeWithTag(TAG_SET_UP_AGAIN).assertDoesNotExist()
        // The screen tells them to scan, so it has to offer the scanner.
        compose.onNodeWithTag(TAG_SCAN_CODE).assertIsDisplayed()
        compose.onNodeWithTag(TAG_REMOVE_LOCK).assertIsDisplayed()
    }

    @Test
    fun aSetupThatBrokeIsADifferentScreenFromOneThatNeverStarted() {
        compose.setContent {
            KioskTheme { Home(HomeState.SetupUnfinished(canSetUpAgain = true)) }
        }

        compose.onNodeWithTag(TAG_SETUP_UNFINISHED).assertIsDisplayed()
        compose.onNodeWithTag(TAG_NOT_SET_UP).assertDoesNotExist()
    }

    @Test
    fun everyRecoveryActionRunsWithoutAPin() {
        var setUpAgain = false
        var scan = false
        var removeLock = false
        compose.setContent {
            KioskTheme {
                Home(
                    HomeState.SetupUnfinished(canSetUpAgain = true),
                    onSetUpAgain = { setUpAgain = true },
                    onScanCode = { scan = true },
                    onRemoveLock = { removeLock = true },
                )
            }
        }

        compose.onNodeWithTag(TAG_SET_UP_AGAIN).performClick()
        compose.onNodeWithTag(TAG_SCAN_CODE).performClick()
        compose.onNodeWithTag(TAG_REMOVE_LOCK).performClick()
        compose.onNodeWithTag(TAG_REMOVE_LOCK_UNDERSTOOD).performClick()
        compose.onNodeWithTag(TAG_REMOVE_LOCK_CONFIRM).performClick()

        assertEquals(true, setUpAgain)
        assertEquals(true, scan)
        assertEquals(true, removeLock)
    }

    @Test
    fun removingTheLockCannotBeConfirmedWithoutSayingSoIsUnderstood() {
        var removeLock = false
        compose.setContent {
            KioskTheme {
                Home(HomeState.NotSetUp(canSetUpAgain = false), onRemoveLock = { removeLock = true })
            }
        }

        compose.onNodeWithTag(TAG_REMOVE_LOCK).performClick()
        compose.onNodeWithTag(TAG_REMOVE_LOCK_DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TAG_REMOVE_LOCK_CONFIRM).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_REMOVE_LOCK_CONFIRM).performClick()

        assertEquals("one tap must not remove the lock", false, removeLock)
    }

    @Test
    fun anUnlockedPhoneSaysSoAndLocksAgainFromTheHomeScreen() {
        var relocked = false
        compose.setContent {
            KioskTheme {
                Home(HomeState.Unlocked(canOpenOtherApps = true), onRelock = { relocked = true })
            }
        }

        compose.onNodeWithTag(TAG_UNLOCKED).assertIsDisplayed()
        compose.onNodeWithTag(TAG_OPEN_OTHER_APPS).assertIsDisplayed()
        compose.onNodeWithTag(TAG_LOCK_AGAIN).performClick()

        assertEquals(true, relocked)
    }

    @Test
    fun aPhoneWhoseLockWasRemovedOffersNothingThatNeedsTheLock() {
        compose.setContent { KioskTheme { Home(HomeState.LockRemoved) } }

        compose.onNodeWithTag(TAG_LOCK_REMOVED).assertIsDisplayed()
        compose.onNodeWithTag(TAG_CHOOSE_HOME).assertIsDisplayed()
        compose.onNodeWithTag(TAG_SCAN_CODE).assertDoesNotExist()
        compose.onNodeWithTag(TAG_REMOVE_LOCK).assertDoesNotExist()
    }

    @Test
    fun aProvisionedPhoneWithNothingVisibleGetsNoRecoveryButtons() {
        compose.setContent {
            KioskTheme { Home(HomeState.NoApps) }
        }

        compose.onNodeWithTag(TAG_NO_APPS).assertIsDisplayed()
        // The PIN works on this phone, so admin is the way in — not a way out.
        compose.onNodeWithTag(TAG_SET_UP_AGAIN).assertDoesNotExist()
        compose.onNodeWithTag(TAG_REMOVE_LOCK).assertDoesNotExist()
    }

    @Test
    fun aBriefPressOnTheAdminCornerDoesNothing() {
        var triggered = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KioskTheme { Home(HomeState.NoApps, onAdminGesture = { triggered = true }) }
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
            KioskTheme { Home(HomeState.NoApps, onAdminGesture = { triggered = true }) }
        }

        compose.onNodeWithTag(TAG_ADMIN_CORNER).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(6_000)

        assertEquals(true, triggered)
    }

    @Test
    fun theAdminCornerHasNoVisibleAffordance() {
        compose.setContent {
            KioskTheme { Home(HomeState.NoApps) }
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
