package org.awana.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle

/**
 * Answers the setup wizard's `GET_PROVISIONING_MODE` from Android 11 onwards.
 *
 * Without it a QR enrolment can stop before the device is ever handed over.
 * There is nothing to ask the trainer — every device this app is scanned onto is
 * fully managed — so it answers and closes without drawing anything.
 *
 * The admin extras have to be handed back, or the bootstrap the QR carried never
 * reaches `onProfileProvisioningComplete` and the device provisions into an
 * empty launcher.
 */
class ProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )
        val result = Intent()
            .putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            )
            .putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                extras ?: PersistableBundle(),
            )

        setResult(RESULT_OK, result)
        finish()
    }
}
