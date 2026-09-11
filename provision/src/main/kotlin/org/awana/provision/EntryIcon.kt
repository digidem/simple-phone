package org.awana.provision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Turns a picture a trainer chose into the base64 PNG a `LauncherEntry` carries.
 *
 * The icon rides inside the config document, which is fetched over a hotspot and
 * hashed into the QR, so it has to stay small: this scales down until the
 * encoded PNG fits the budget rather than trusting whatever came out of the
 * gallery.
 */
object EntryIcon {

    private const val TAG = "EntryIcon"

    /** Comfortably under the ~40 KB `LauncherEntry.iconPng` documents. */
    private const val MAX_BYTES = 40_000

    private val SIZES = listOf(192, 144, 96, 72)

    fun encode(context: Context, uri: Uri): Result<String> = runCatching {
        val source = context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("That file is not a picture this phone can read.")

        for (size in SIZES) {
            val encoded = source.squareTo(size).toBase64Png()
            if (encoded.length <= MAX_BYTES) return@runCatching encoded
        }
        error("That picture could not be made small enough. Try a simpler one.")
    }.onFailure { Log.w(TAG, "Could not use $uri as an icon", it) }

    /** Centre-cropped, because a launcher tile is square and a photo is not. */
    private fun Bitmap.squareTo(size: Int): Bitmap {
        val side = minOf(width, height)
        val cropped = Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    private fun Bitmap.toBase64Png(): String {
        val bytes = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }
}
