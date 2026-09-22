package org.awana.kiosk.handshake

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.awana.kiosk.shared.KioskConfig

/**
 * The setup app's five endpoints, plus `/state` for the host to read what
 * happened. `setup/…/ProvisioningServer.kt` is the real one; this one serves a
 * single deployment and keeps a record instead of driving a UI.
 */
class DeploymentServer(
    port: Int,
    private val deployment: Deployment,
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        // The host polls /state; recording it would bury what the device asked for.
        if (uri != "/state") State.requested(uri)
        Log.i(DeploymentService.TAG, "${session.method} $uri from ${session.remoteIpAddress}")
        return try {
            when {
                session.method == Method.POST && uri == "/report" -> report(session)
                session.method != Method.GET ->
                    text(Response.Status.METHOD_NOT_ALLOWED, "Only GET and POST /report")
                uri == KioskConfig.DPC_PATH -> Assets.apk(deployment.kioskApk)
                uri == KioskConfig.CONFIG_PATH ->
                    newFixedLengthResponse(Response.Status.OK, "application/json", deployment.configJson)
                uri == "/manifest.json" ->
                    newFixedLengthResponse(Response.Status.OK, "application/json", deployment.manifest.encode())
                uri == Deployment.SAMPLE_PATH -> Assets.apk(deployment.sampleApk)
                uri == "/state" ->
                    newFixedLengthResponse(Response.Status.OK, "application/json", State.encode())
                else -> text(Response.Status.NOT_FOUND, "No such path")
            }
        } catch (e: Exception) {
            Log.e(DeploymentService.TAG, "Request for $uri failed", e)
            text(Response.Status.INTERNAL_ERROR, "Server error: ${e.message}")
        }
    }

    private fun report(session: IHTTPSession): Response {
        // NanoHTTPD only reads the body once parseBody has been called, and
        // only exposes it through this map.
        val body = HashMap<String, String>()
        session.parseBody(body)
        State.report(body["postData"].orEmpty())
        return text(Response.Status.OK, "ok")
    }

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message)
}
