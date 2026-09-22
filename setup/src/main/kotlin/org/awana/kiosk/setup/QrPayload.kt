package org.awana.kiosk.setup

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The provisioning QR as pixels. The JSON it carries is built by
 * `SetupCode.build`, next to the parser that reads it back.
 */
object QrPayload {

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
