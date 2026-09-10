package org.awana.kiosk.shared

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Signing certificate fingerprints, as lowercase hex SHA-256 of the DER
 * encoding. This is the same value `apksigner verify --print-certs` reports as
 * "certificate SHA-256 digest".
 */
object Certificates {

    /**
     * Reads the signing certificates from an APK on disk that is not installed.
     *
     * On a first install there is no incumbent package for Android to compare
     * against, so this is the only point at which a substituted APK can be
     * caught.
     */
    fun ofApkFile(context: Context, apk: File): List<String> =
        context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            .fingerprints()

    fun ofInstalledPackage(context: Context, packageName: String): List<String> = try {
        context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .fingerprints()
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

    /**
     * Whether [expected] appears among [actual].
     *
     * Matching anywhere in the certificate history rather than only the current
     * signer is deliberate: it lets a package that has rotated its key under
     * APK Signature Scheme v3 still verify against a fingerprint recorded before
     * the rotation.
     */
    fun matches(actual: List<String>, expected: String): Boolean {
        val wanted = expected.normalise()
        return actual.any { it.normalise() == wanted }
    }

    private fun PackageInfo?.fingerprints(): List<String> {
        val signing = this?.signingInfo ?: return emptyList()
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return signatures.orEmpty().map { Digests.sha256Hex(it.toByteArray()) }
    }

    private fun String.normalise() = replace(":", "").trim().lowercase()
}

object Digests {
    private const val HEX = "0123456789abcdef"

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
