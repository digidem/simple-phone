package org.awana.kiosk

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * From Android 11 the setup wizard asks the DPC to handle these two actions and
 * a QR provisioning run can stop without them. Nothing in the instrumented suite
 * exercises the wizard itself, so what is checked here is what the wizard looks
 * for: that this package answers both, and behind the permission that keeps any
 * other app from starting them.
 */
@RunWith(AndroidJUnit4::class)
class ProvisioningActivitiesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun resolve(action: String): ResolveInfo? =
        context.packageManager
            .queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName == context.packageName }

    @Test
    fun thisPackageAnswersGetProvisioningMode() {
        val activity = resolve("android.app.action.GET_PROVISIONING_MODE")?.activityInfo
        assertNotNull("no activity handles GET_PROVISIONING_MODE", activity)
        assertEquals(
            "android.permission.BIND_DEVICE_ADMIN",
            activity!!.permission,
        )
        assertEquals(true, activity.exported)
    }

    @Test
    fun thisPackageAnswersAdminPolicyCompliance() {
        val activity = resolve("android.app.action.ADMIN_POLICY_COMPLIANCE")?.activityInfo
        assertNotNull("no activity handles ADMIN_POLICY_COMPLIANCE", activity)
        assertEquals(
            "android.permission.BIND_DEVICE_ADMIN",
            activity!!.permission,
        )
        assertEquals(true, activity.exported)
    }
}
