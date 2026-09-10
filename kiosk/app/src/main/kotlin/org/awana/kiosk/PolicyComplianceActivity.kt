package org.awana.kiosk

import android.app.Activity
import android.os.Bundle

/**
 * Answers the setup wizard's `ADMIN_POLICY_COMPLIANCE` from Android 11 onwards.
 *
 * The wizard will not finish enrolling without an activity for it. The work it
 * would normally do here — installing apps and applying policy — runs from
 * `ProvisioningService` instead, so this only agrees and closes; anything drawn
 * here would be a screen the trainer has to read and dismiss on every phone.
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
