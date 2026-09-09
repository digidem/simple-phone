package app.comapeo.kiosk.policy

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest

/**
 * Signing certificate fingerprints, as lowercase hex SHA-256 of the DER
 * encoding. This is the same value `apksigner verify --print-certs` reports as
 * "certificate SHA-256 digest".
 */
object Certificates {

    /**
     * Reads the signing certificates from an APK that is staged on disk but not
     * yet installed.
     *
     * On a first install there is no incumbent package for Android to compare
     * against, so this is the only point at which a substituted APK can be
     * caught.
     */
    fun ofApkFile(context: Context, apk: File): List<String> {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return emptyList()
        return info.signingInfo?.let { signing ->
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        }.orEmpty().map { it.sha256() }
    }

    fun ofInstalledPackage(context: Context, packageName: String): List<String> = try {
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        info.signingInfo?.let { signing ->
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        }.orEmpty().map { it.sha256() }
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

    private fun Signature.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

    private fun String.normalise() = replace(":", "").trim().lowercase()

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
