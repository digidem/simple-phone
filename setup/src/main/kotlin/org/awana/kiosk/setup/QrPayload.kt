package org.awana.kiosk.setup

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.awana.kiosk.shared.SetupCode
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.KioskJson

/**
 * The JSON the Android setup wizard reads from a provisioning QR.
 *
 * Two details matter and are easy to get wrong:
 *
 * `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` rather than
 * `..._PACKAGE_CHECKSUM`. The signature checksum derives from the signing
 * certificate and so is constant across builds; the package checksum is a file
 * hash and changes with every release. From Android 10 only SHA-256 is accepted.
 *
 * `LEAVE_ALL_SYSTEM_APPS_ENABLED` must be true. Without it, provisioning
 * disables non-required system apps, which can take out components the
 * deployment needs.
 *
 * `ALLOW_OFFLINE` must be true. From Android 14 the wizard otherwise insists on
 * internet to update the platform's provisioning role holder, and the hotspot
 * has none: it says "couldn't connect to the internet" and returns to the scanner.
 */
object QrPayload {

    const val DPC_PACKAGE = SetupCode.DPC_PACKAGE
    const val DPC_RECEIVER = "org.awana.kiosk.KioskDeviceAdminReceiver"

    /**
     * Beyond roughly this many bytes a QR needs so many modules that a budget
     * phone camera struggles with it in poor light.
     */
    const val COMFORTABLE_BYTES = 1_800

    fun build(
        serverUrl: String,
        /** Base64url SHA-256 of the kiosk's signing certificate, no padding. */
        signatureChecksum: String,
        wifiSsid: String,
        wifiPassphrase: String,
        wifiSecurityType: String,
        locale: String,
        timeZone: String,
        /** Lowercase hex SHA-256 of the config bytes the server will hand out. */
        configSha256: String,
    ): String {
        val payload = buildJsonObject {
            put(
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME",
                "$DPC_PACKAGE/$DPC_RECEIVER",
            )
            put(
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION",
                "${serverUrl.trimEnd('/')}/dpc.apk",
            )
            put("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", signatureChecksum)
            put("android.app.extra.PROVISIONING_WIFI_SSID", wifiSsid)
            put("android.app.extra.PROVISIONING_WIFI_PASSWORD", wifiPassphrase)
            put("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", wifiSecurityType)
            put("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", JsonPrimitive(true))
            put("android.app.extra.PROVISIONING_SKIP_ENCRYPTION", JsonPrimitive(true))
            put("android.app.extra.PROVISIONING_ALLOW_OFFLINE", JsonPrimitive(true))
            put("android.app.extra.PROVISIONING_LOCALE", locale)
            put("android.app.extra.PROVISIONING_TIME_ZONE", timeZone)
            put(
                "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE",
                buildJsonObject {
                    // Every value here must be a string. Nested or typed values
                    // through the QR path are unreliable.
                    put(KioskConfig.EXTRA_SERVER_URL, serverUrl.trimEnd('/'))
                    put(KioskConfig.EXTRA_CONFIG_SHA256, configSha256)
                },
            )
        }
        return KioskJson.compact.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            payload,
        )
    }

    fun render(payload: String, sizePx: Int = 900): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        val row = IntArray(matrix.width)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                row[x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
            bitmap.setPixels(row, 0, matrix.width, 0, y, matrix.width, 1)
        }
        return bitmap
    }
}
