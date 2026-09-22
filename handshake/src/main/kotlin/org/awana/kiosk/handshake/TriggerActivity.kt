package org.awana.kiosk.handshake

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.awana.kiosk.shared.KioskJson
import org.awana.kiosk.shared.SetupCode

/**
 * Does what the setup wizard does around a setup code. Started from the host:
 *
 *   am start -n org.awana.kiosk.handshake/.TriggerActivity                     # scan: start provisioning
 *   am start -n org.awana.kiosk.handshake/.TriggerActivity --es step finalize  # end of wizard
 *   am start … --ez wrongChecksum true      # the wizard must refuse the download
 *   am start … --ez wrongConfigHash true    # the kiosk must refuse the config
 *
 * The wizard hands ManagedProvisioning the code's extras, and at its own end
 * starts PROVISION_FINALIZATION, which is when the DPC's activities and the
 * completion broadcast happen. The AOSP stub wizard on an emulator does
 * neither, so this app does both.
 */
class TriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The wizard applies PROVISIONING_LOCALE mid-run, which recreates this
        // activity; a recreated instance must not start provisioning again.
        if (savedInstanceState != null) return
        DeploymentService.start(this)

        when (val step = intent.getStringExtra("step") ?: "scan") {
            "scan" -> scan()
            "finalize" -> finalizeProvisioning()
            else -> { note("unknown step $step"); finish() }
        }
    }

    private fun scan() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val allowed = dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)
        note("isProvisioningAllowed(PROVISION_MANAGED_DEVICE)=$allowed")

        val deployment = Deployment.of(this)
        val checksum = SetupCode.signatureChecksumOf(this, deployment.kioskApk)
            .let { if (intent.getBooleanExtra("wrongChecksum", false)) corrupt(it) else it }
        val configSha256 = deployment.configSha256
            .let { if (intent.getBooleanExtra("wrongConfigHash", false)) corrupt(it) else it }
        note("kiosk signature checksum $checksum, config $configSha256")

        val code = SetupCode.build(
            serverUrl = Deployment.SERVER_URL,
            signatureChecksum = checksum,
            // The emulator's own open network, which it is already on. Without
            // Wi-Fi extras the wizard shows its Wi-Fi picker, whose NEXT ends
            // provisioning on API 34; with them it compares this against the
            // quoted SSID Android reports, and cannot add a duplicate when
            // they differ, so the quotes are part of the value.
            wifiSsid = "\"AndroidWifi\"",
            wifiPassphrase = "",
            wifiSecurityType = "NONE",
            locale = Deployment.LOCALE,
            timeZone = Deployment.TIME_ZONE,
            configSha256 = configSha256,
        )
        note("setup code ${code.length} bytes")
        start(intentFrom(code))
    }

    /**
     * The setup code's JSON, as the wizard turns it into an intent: strings and
     * booleans as extras of that type, the component as a [ComponentName] (the
     * intent form is a component, the QR form a string), and the admin extras
     * as a [PersistableBundle] of strings.
     */
    private fun intentFrom(code: String): Intent {
        val intent = Intent(ACTION_PROVISION_FROM_TRUSTED_SOURCE)
        for ((key, value) in KioskJson.compact.parseToJsonElement(code).jsonObject) {
            when {
                key == COMPONENT ->
                    intent.putExtra(key, ComponentName.unflattenFromString(value.jsonPrimitive.content))

                key == ADMIN_EXTRAS -> intent.putExtra(key, bundleOf(value.jsonObject))

                else -> {
                    val primitive = value.jsonPrimitive
                    val flag = if (primitive.isString) null else primitive.booleanOrNull
                    if (flag == null) intent.putExtra(key, primitive.content)
                    else intent.putExtra(key, flag)
                }
            }
        }
        return intent
    }

    private fun bundleOf(extras: JsonObject) = PersistableBundle().apply {
        extras.forEach { (key, value) -> putString(key, value.jsonPrimitive.content) }
    }

    private fun finalizeProvisioning() {
        start(Intent(ACTION_PROVISION_FINALIZATION).addCategory(Intent.CATEGORY_DEFAULT))
    }

    /** Keeps the length and the alphabet, so only the value is wrong. */
    private fun corrupt(value: String): String =
        value.map { if (it == 'a' || it == 'A') 'b' else 'a' }.joinToString("")

    private fun start(intent: Intent) {
        try {
            startActivityForResult(intent, 1)
            note("started ${intent.action}")
        } catch (e: Exception) {
            note("could not start ${intent.action}: $e")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        note("returned result $resultCode")
        finish()
    }

    private fun note(text: String) {
        Log.i(TAG, text)
        State.note(text)
    }

    private companion object {
        const val TAG = "HandshakeTrigger"

        // Not in the public SDK; they are what the wizard sends after reading a code.
        const val ACTION_PROVISION_FROM_TRUSTED_SOURCE =
            "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE"
        const val ACTION_PROVISION_FINALIZATION = "android.app.action.PROVISION_FINALIZATION"

        const val COMPONENT = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
        const val ADMIN_EXTRAS = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
    }
}
