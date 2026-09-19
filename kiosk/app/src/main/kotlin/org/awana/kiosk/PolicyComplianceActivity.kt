package org.awana.kiosk

import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.awana.kiosk.launcher.KioskTheme
import org.awana.kiosk.launcher.SetupWorking
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.policy.UpdateProgress
import org.awana.kiosk.policy.UpdateState
import org.awana.kiosk.shared.Telemetry

/**
 * Answers the setup wizard's `ADMIN_POLICY_COMPLIANCE`, and is where the phone
 * actually sets itself up.
 *
 * From Android 12 this is the only point a device owner is sure to get:
 * "DPC setup can't be started after the end of the setup wizard", and the
 * completion broadcast never arrived on a Galaxy A17 on Android 16. So the
 * wizard is held here, behind a progress screen, until the service is done.
 * The result is always OK: a failed setup is recorded for the launcher to
 * offer again, which beats a wizard that stops with the phone half managed.
 */
class PolicyComplianceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )
        val bootstrap = Handshake.note(this, "Policy compliance", extras)
            ?: Provisioner(this).pendingBootstrap()
        if (!DevicePolicy(this).isDeviceOwner || bootstrap == null) {
            Telemetry.warn(TAG, "Nothing to set up here; returning to the wizard")
            finishWithOk()
            return
        }

        // Leaving now would leave the wizard to finish with setup half done.
        onBackPressedDispatcher.addCallback(this) {}

        if (savedInstanceState == null) {
            UpdateProgress.clear()
            ProvisioningService.startInSetupWizard(this, bootstrap)
        } else if (!ProvisioningService.isRunning && !UpdateProgress.state.value.finished) {
            // Recreated after the process died: the run died with it, and
            // waiting on it would hold the wizard for the whole timeout.
            Telemetry.warn(TAG, "Recreated with no setup running; starting it again")
            ProvisioningService.startInSetupWizard(this, bootstrap)
        }

        setContent {
            KioskTheme {
                val state by UpdateProgress.state.collectAsState()
                SetupWorking(state)
            }
        }

        lifecycleScope.launch {
            val outcome = withTimeoutOrNull(LIMIT_MS) { UpdateProgress.state.first { it.finished } }
            if (outcome == null) Telemetry.report(TAG, "Setup did not finish in time; releasing the wizard")
            if (outcome !is UpdateState.Done) {
                // A successful run made this app HOME; a failed one never got
                // that far. Without it the wizard ends on the stock launcher
                // rather than on the screen that offers to set up again.
                runCatching { DevicePolicy(this@PolicyComplianceActivity).applyHome() }
                    .onFailure { Telemetry.warn(TAG, "Could not make the launcher HOME after a failed setup", it) }
            }
            UpdateProgress.clear()
            finishWithOk()
        }
    }

    private fun finishWithOk() {
        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val TAG = "PolicyCompliance"

        /** A generous ceiling: a slow hotspot and a large app can take many minutes. */
        const val LIMIT_MS = 30 * 60_000L
    }
}
