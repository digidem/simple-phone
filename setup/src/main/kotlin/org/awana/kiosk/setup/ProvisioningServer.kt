package org.awana.kiosk.setup

import fi.iki.elonen.NanoHTTPD
import org.awana.kiosk.shared.SetupReport
import org.awana.kiosk.shared.ServerManifest
import org.awana.kiosk.shared.Telemetry
import java.io.File
import java.io.FileInputStream

/**
 * The four endpoints a device needs while being set up.
 *
 * ```
 * GET  /dpc.apk            the kiosk APK, fetched by the setup wizard
 * GET  /config.json        the deployment config, fetched by the kiosk
 * GET  /apks/{package}.apk the payload APKs, fetched by the kiosk
 * GET  /manifest.json      what this deployment consists of, for diagnosis
 * POST /report             completion reports from phones
 * ```
 *
 * Plain HTTP is correct: integrity comes from the signature checksum in the QR
 * and from the certificate checks the kiosk performs itself, not from the
 * transport. The hotspot has no certificate to present.
 */
class ProvisioningServer(
    port: Int,
    private val kioskApk: File,
    private val payload: List<ServedApk>,
    private val manifest: ServerManifest,
    /**
     * Served verbatim. The QR carries a SHA-256 of exactly these bytes, so
     * re-encoding the config here would break the check on every device.
     */
    private val configJson: String,
    private val onReport: (address: String, report: SetupReport) -> Unit,
    /**
     * Every request, with the address it came from. Until a device reports it
     * has no identity at all, so its requests are the only sign it exists.
     */
    private val onRequest: (address: String, uri: String) -> Unit = { _, _ -> },
) : NanoHTTPD(port) {

    data class ServedApk(val packageName: String, val file: File)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        onRequest(session.remoteIpAddress.orEmpty(), uri)
        return try {
            when {
                session.method == Method.POST && uri == "/report" -> handleReport(session)
                session.method != Method.GET ->
                    text(Response.Status.METHOD_NOT_ALLOWED, "Only GET and POST /report")
                uri == "/config.json" ->
                    newFixedLengthResponse(Response.Status.OK, "application/json", configJson)
                uri == "/manifest.json" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        manifest.encode(),
                    )
                uri == "/dpc.apk" -> apk(kioskApk)
                uri.startsWith("/apks/") && uri.endsWith(".apk") -> {
                    val packageName = uri.removePrefix("/apks/").removeSuffix(".apk")
                    val served = payload.firstOrNull { it.packageName == packageName }
                    if (served == null) text(Response.Status.NOT_FOUND, "No APK for $packageName")
                    else apk(served.file)
                }
                else -> text(Response.Status.NOT_FOUND, "No such path")
            }
        } catch (e: Exception) {
            Telemetry.report(TAG, "Request for $uri failed", error = e)
            text(Response.Status.INTERNAL_ERROR, "Server error: ${e.message}")
        }
    }

    private fun handleReport(session: IHTTPSession): Response {
        // NanoHTTPD only reads the body once parseBody has been called, and
        // only exposes it through this map.
        val body = HashMap<String, String>()
        session.parseBody(body)
        val text = body["postData"].orEmpty()
        return try {
            onReport(session.remoteIpAddress.orEmpty(), SetupReport.parse(text))
            text(Response.Status.OK, "ok")
        } catch (e: Exception) {
            Telemetry.report(TAG, "Unreadable report: ${text.take(500)}", error = e)
            text(Response.Status.BAD_REQUEST, "Could not read that report")
        }
    }

    private fun apk(file: File): Response {
        if (!file.isFile) return text(Response.Status.NOT_FOUND, "Missing file")
        // Streamed with an explicit length rather than read into memory: the
        // a field app can be well over 100 MB.
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/vnd.android.package-archive",
            FileInputStream(file),
            file.length(),
        )
    }

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message)

    private companion object {
        const val TAG = "ProvisioningServer"
    }
}
