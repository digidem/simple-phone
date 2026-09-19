package org.awana.kiosk.shared

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import java.net.Inet4Address

/**
 * Crash and diagnostic reporting for both apps, through Sentry.
 *
 * Field phones are usually offline while it matters, so events are written to
 * disk and sent the next time the app runs with a connection. Every call also
 * goes to Logcat, and every call is a no-op until [init] has run.
 *
 * The DSN comes from the `io.sentry.dsn` manifest entry each app sets from the
 * `sentryDsn` Gradle property; an empty one disables reporting.
 */
object Telemetry {

    private var started = false

    /**
     * Safe to call from every entry point: only the first call does anything.
     * Synchronized so a second caller waits for the SDK to be up rather than
     * reporting into one that is not. Does disk I/O, so the launcher calls it
     * off the main thread.
     */
    @Synchronized
    fun init(context: Context) {
        if (started) return
        started = true
        // Instrumented tests run on emulators and deliberately fail
        // provisioning; none of that belongs in the field reports.
        if (isEmulator()) return
        val app = context.applicationContext
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        runCatching {
            SentryAndroid.init(app) { options ->
                options.environment = if (debuggable) "debug" else "release"
                options.logs.isEnabled = true
                options.isSendDefaultPii = false
                // Every message comes from report(), so an attached stack would
                // group them all into one issue; group by the message instead.
                options.isAttachStacktrace = false
                // A phone can go days between connections.
                options.maxCacheItems = 100
            }
        }.onFailure { Log.e(TAG, "Sentry did not start", it) }
    }

    fun setTag(key: String, value: String) {
        Sentry.setTag(key, value)
    }

    fun info(tag: String, message: String) {
        Log.i(tag, message)
        trail(tag, message, SentryLevel.INFO)
        Sentry.logger().info("[$tag] $message")
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        val full = if (error == null) message else "$message: $error"
        trail(tag, full, SentryLevel.WARNING)
        Sentry.logger().warn("[$tag] $full")
    }

    /**
     * An event of its own rather than a log line, so it shows up as an issue.
     * [extras] must not carry secrets: no passphrases, no PIN hashes.
     */
    fun report(
        tag: String,
        message: String,
        level: SentryLevel = SentryLevel.ERROR,
        error: Throwable? = null,
        extras: Map<String, Any?> = emptyMap(),
        context: Context? = null,
    ) {
        when (level) {
            SentryLevel.ERROR, SentryLevel.FATAL -> Log.e(tag, message, error)
            SentryLevel.WARNING -> Log.w(tag, message, error)
            else -> Log.i(tag, message, error)
        }
        val network = context?.let { runCatching { network(it) }.getOrNull() }
        val callback = io.sentry.ScopeCallback { scope ->
            scope.setTag("component", tag)
            extras.forEach { (key, value) -> scope.setExtra(key, value.toString()) }
            network?.let { scope.setContexts("network", it) }
        }
        if (error != null) {
            Sentry.captureException(error, callback)
        } else {
            Sentry.captureMessage("[$tag] $message", level, callback)
        }
    }

    /** Blocks; call off the main thread before a process is likely to end. */
    fun flush(timeoutMs: Long = 5_000) {
        if (Sentry.isEnabled()) Sentry.flush(timeoutMs)
    }

    private fun trail(tag: String, message: String, level: SentryLevel) {
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                category = tag
                this.message = message
                this.level = level
            },
        )
    }

    /**
     * Whether the phone can reach the trainer is the first question on every
     * failed fetch: which network is the default, and which Wi-Fi it holds.
     */
    private fun network(context: Context): Map<String, Any?> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val default = manager.activeNetwork
        @Suppress("DEPRECATION")
        val all = manager.allNetworks.map { network ->
            val caps = manager.getNetworkCapabilities(network)
            val link: LinkProperties? = manager.getLinkProperties(network)
            mapOf(
                "default" to (network == default),
                "wifi" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true),
                "cellular" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true),
                "internet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true),
                "validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
                "interface" to link?.interfaceName,
                // Private IPv4 only: a mobile network's IPv6 addresses are public.
                "addresses" to link?.linkAddresses
                    ?.map { it.address }
                    ?.filter { it is Inet4Address && it.isSiteLocalAddress }
                    ?.map { it.hostAddress },
            )
        }
        return mapOf("networks" to all, "hasDefault" to (default != null))
    }

    /** By hardware alone: some white-label phone ROMs also have "generic" fingerprints. */
    private fun isEmulator(): Boolean = Build.HARDWARE in setOf("ranchu", "goldfish")

    private const val TAG = "Telemetry"
}
