package org.awana.kiosk

import fi.iki.elonen.NanoHTTPD
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.KioskConfig
import java.io.File
import java.io.FileInputStream

/**
 * Stands in for the trainer's app: serves the deployment config and the payload
 * APKs, and keeps the enrolment reports that come back.
 *
 * [config] is served as the exact bytes it is given, never re-encoded, because
 * the hash in the QR is over the bytes as served and re-encoding on either side
 * would make every device reject a genuine deployment.
 */
class DeploymentServer(private val apk: File) : NanoHTTPD(0) {

    val reports = mutableListOf<EnrolmentReport>()

    var config: String? = null

    val url: String get() = "http://127.0.0.1:$listeningPort"

    /** Starts listening and returns the base URL to put in a config or bootstrap. */
    fun begin(): String {
        start(START_TIMEOUT_MS, false)
        return url
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.POST && session.uri == "/report" -> {
            val body = HashMap<String, String>()
            session.parseBody(body)
            reports += EnrolmentReport.parse(body["postData"].orEmpty())
            newFixedLengthResponse("ok")
        }

        session.uri == KioskConfig.CONFIG_PATH && config != null ->
            newFixedLengthResponse(Response.Status.OK, "application/json", config)

        session.uri.startsWith("/apks/") -> newFixedLengthResponse(
            Response.Status.OK,
            "application/vnd.android.package-archive",
            FileInputStream(apk),
            apk.length(),
        )

        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no")
    }

    private companion object {
        const val START_TIMEOUT_MS = 5_000
    }
}
