package app.comapeo.kiosk.policy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The small amount of HTTP this app does, over `HttpURLConnection` rather than
 * a client library. Provisioning fetches are plain HTTP over the trainer's
 * hotspot; integrity comes from the certificate checks in [ApkInstaller], not
 * from the transport.
 */
object Http {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    suspend fun download(url: String, into: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("HTTP ${connection.responseCode} from $url")
                }
                into.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    into.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            into
        }
    }

    suspend fun getText(url: String, accept: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    accept?.let { setRequestProperty("Accept", it) }
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        error("HTTP ${connection.responseCode} from $url")
                    }
                    connection.inputStream.bufferedReader().readText()
                } finally {
                    connection.disconnect()
                }
            }
        }

    suspend fun postJson(url: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                if (connection.responseCode !in 200..299) {
                    error("HTTP ${connection.responseCode} from $url")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
