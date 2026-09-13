package org.awana.kiosk.launcher

import android.Manifest
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.InstallResult
import org.awana.kiosk.shared.InstalledPackage
import org.awana.kiosk.shared.PackageOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens a trainer sees when updating a phone that is already in service.
 *
 * The scanner itself is not driven here — pointing a camera at a QR is not
 * something a test can do. What it decodes is covered by `:shared`, and what
 * happens next by `UpdatesTest`; between them the only untested part is the
 * lens.
 */
@RunWith(AndroidJUnit4::class)
class UpdateScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun report(vararg outcomes: Pair<String, InstallResult>, failures: List<String> = emptyList()) =
        EnrolmentReport(
            deviceId = "device",
            deviceLabel = "AB23",
            deploymentId = "rio-negro",
            deploymentName = "Rio Negro",
            manufacturer = "Xiaomi",
            model = "Redmi 12C",
            androidVersion = "13",
            apiLevel = 33,
            kioskVersion = "0.1.0 (1)",
            buildVariant = "sig:8766564a",
            isDeviceOwner = true,
            installed = outcomes.map { InstalledPackage(it.first, "1.0") },
            packageOutcomes = outcomes.map { PackageOutcome(it.first, it.second, "1.0") },
            failures = failures,
            reportedAtEpochMs = 0L,
        )

    @Test
    fun updatingIsItsOwnDoorRatherThanBuriedUnderSettings() {
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }

        compose.onNodeWithTag(TAG_ROW_UPDATE).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.admin_update)).assertIsDisplayed()
    }

    /**
     * A phone that will not hand over the camera has to say so. The alternative
     * is a black rectangle a trainer holds up to a code for a minute before
     * giving up.
     */
    @Test
    fun theScannerSaysSoWhenTheCameraIsNotAvailable() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .revokeRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        }

        compose.setContent { KioskTheme { ScanScreen(onCode = {}, onBack = {}) } }

        compose.onNodeWithTag(TAG_SCAN_NO_CAMERA).assertIsDisplayed()
    }

    @Test
    fun aFinishedUpdateSaysWhatChangedAndWhatDidNotNeedTo() {
        compose.setContent {
            KioskTheme {
                UpdateFinished(
                    report = report(
                        "org.example.mapping" to InstallResult.Updated,
                        "org.example.chat" to InstallResult.AlreadyCurrent,
                        "org.example.camera" to InstallResult.AlreadyCurrent,
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.update_done)).assertIsDisplayed()
        compose.onNodeWithText("1 app updated", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2 apps were already up to date", substring = true).assertIsDisplayed()
    }

    /**
     * The common case on a second pass round a room: worth saying plainly, so a
     * trainer does not scan again wondering whether it worked.
     */
    @Test
    fun anUpdateThatChangedNothingSaysSoRatherThanShowingAnEmptyList() {
        compose.setContent {
            KioskTheme {
                UpdateFinished(
                    report = report("org.example.mapping" to InstallResult.AlreadyCurrent),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.update_done_nothing)).assertIsDisplayed()
    }

    @Test
    fun anUpdateThatFailedLeadsWithTheReasonNotTheCount() {
        compose.setContent {
            KioskTheme {
                UpdateFinished(
                    report = report(
                        "org.example.mapping" to InstallResult.Failed,
                        failures = listOf("Could not download org.example.mapping."),
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.update_problem)).assertIsDisplayed()
        compose.onNodeWithText("Could not download", substring = true).assertIsDisplayed()
    }

    @Test
    fun aCodeFromTheOtherFleetExplainsItselfInWords() {
        val refusal = context.getString(R.string.update_other_fleet)
        compose.setContent { KioskTheme { UpdateRefused(refusal, onBack = {}) } }

        compose.onNodeWithText(refusal).assertIsDisplayed()
        compose.onNodeWithTag(TAG_UPDATE_CLOSE).assertIsDisplayed()
    }

    /**
     * The escape hatch from curating settings one feature request at a time.
     * It sits behind the same PIN that can remove the lock altogether, so it
     * widens no blast radius that was not already open.
     */
    @Test
    fun theChangePageOffersThePhonesOwnSettings() {
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }
        compose.onNodeWithTag(TAG_ROW_CHANGE).performClick()

        compose.onNodeWithTag(TAG_ROW_SETTINGS).performScrollTo().assertIsDisplayed()
    }

    /** Opening it unlocks the phone, so it says so before it does. */
    @Test
    fun openingSettingsExplainsTheUnlockFirst() {
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }
        compose.onNodeWithTag(TAG_ROW_CHANGE).performClick()
        compose.onNodeWithTag(TAG_ROW_SETTINGS).performScrollTo().performClick()

        compose.onNodeWithText(context.getString(R.string.admin_settings_explain)).assertIsDisplayed()
    }
}
