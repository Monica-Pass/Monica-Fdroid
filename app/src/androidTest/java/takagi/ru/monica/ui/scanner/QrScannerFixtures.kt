package takagi.ru.monica.ui.scanner

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

internal object QrScannerFixtures {
    const val STEAM = "https://s.team/q/1/123456789"
    const val TOTP = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"
    const val UNRELATED = "https://example.org/help"

    class Frame(val width: Int = 1280, val height: Int = 960) {
        val pixels = ByteArray(width * height) { 255.toByte() }

        fun code(value: String, left: Int = (width - 440) / 2, top: Int = (height - 440) / 2,
            size: Int = 440, inverted: Boolean = false, format: BarcodeFormat = BarcodeFormat.QR_CODE): Frame {
            val matrix = MultiFormatWriter().encode(value, format, size, size,
                mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.QR_VERSION to 5))
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                pixels[(top + y) * width + left + x] = if (matrix[x, y] != inverted) 0 else 255.toByte()
            }
            return this
        }

        private val pixelBuffer = ByteBuffer.wrap(pixels)

        private fun plane(): ImageProxy.PlaneProxy = object : ImageProxy.PlaneProxy {
            override fun getRowStride() = width
            override fun getPixelStride() = 1
            override fun getBuffer(): ByteBuffer = pixelBuffer
        }

        fun proxy(rotation: Int = 0): ImageProxy {
            val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
                when (method.name) { "getRotationDegrees" -> rotation; "getTimestamp" -> 0L; else -> null }
            } as ImageInfo
            return Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
                when (method.name) {
                    "getWidth" -> width
                    "getHeight" -> height
                    "getPlanes" -> arrayOf(plane())
                    "getImageInfo" -> info
                    "getCropRect" -> Rect(0, 0, width, height)
                    "getFormat" -> ImageFormat.YUV_420_888
                    else -> null
                }
            } as ImageProxy
        }

        /** Preserve the real CameraX frame and its close/backpressure contract; replace only test pixels. */
        fun replacePixels(original: ImageProxy): ImageProxy = object : ImageProxy by original {
            override fun getWidth() = this@Frame.width
            override fun getHeight() = this@Frame.height
            override fun getPlanes() = arrayOf(plane())
            override fun getCropRect() = Rect(0, 0, this@Frame.width, this@Frame.height)
            override fun getImageInfo(): ImageInfo = object : ImageInfo by original.imageInfo {
                override fun getRotationDegrees() = 0
            }
        }

        fun bitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.setPixels(IntArray(pixels.size) { index ->
                val value = pixels[index].toInt() and 255
                (255 shl 24) or (value shl 16) or (value shl 8) or value
            }, 0, width, 0, 0, width, height)
        }
    }
}
