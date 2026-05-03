package hr.fer.studentzkp.holder.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

object QrCodeUtils {

    /**
     * Generate a QR code Bitmap for the given content.
     * Compresses the payload with DEFLATE + base64url to reduce QR density,
     * prefixed with "Z:" so the scanner knows to decompress.
     */
    fun generate(content: String, sizePx: Int = 1024): Bitmap {
        val payload = compress(content)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 3,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        )
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /**
     * Decompress a QR payload back to the original string.
     * If prefixed with "Z:", deflate-decompress. Otherwise return as-is.
     */
    fun decompress(payload: String): String {
        if (!payload.startsWith("Z:")) return payload
        val compressed = Base64.decode(
            payload.substring(2),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val inflater = java.util.zip.Inflater()
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream(compressed.size * 3)
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val len = inflater.inflate(buf)
            out.write(buf, 0, len)
        }
        inflater.end()
        return out.toString(Charsets.UTF_8.name())
    }

    private fun compress(content: String): String {
        val raw = content.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream(raw.size)
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val len = deflater.deflate(buf)
            out.write(buf, 0, len)
        }
        deflater.end()
        val compressed = out.toByteArray()
        // Only use compression if it actually saves space
        if (compressed.size >= raw.size) return content
        return "Z:" + Base64.encodeToString(
            compressed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}
