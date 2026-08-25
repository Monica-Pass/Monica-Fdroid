package takagi.ru.monica.ui.scanner

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

internal class ZxingBarcodeDecoder(formats: Collection<BarcodeFormat>) : AutoCloseable {
    private val reader = MultiFormatReader()

    init {
        val hintFormats = formats.toList().ifEmpty { DEFAULT_FORMATS }
        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to hintFormats,
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    fun decodeFrame(imageProxy: ImageProxy): List<String> {
        val data = readLuminancePlane(imageProxy) ?: return emptyList()
        return decodeWithFallbackChain(
            data,
            imageProxy.width,
            imageProxy.height,
            imageProxy.imageInfo.rotationDegrees
        )
    }

    fun decodeUri(context: Context, uri: Uri): List<String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Cannot open input stream for $uri")
        boundsStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw java.io.IOException(
                "Image decode failed (format unsupported?): ${bounds.outMimeType} size=${bounds.outWidth}x${bounds.outHeight}"
            )
        }

        // 第一遍：常规降采样解码
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2
        }
        val firstResult = decodeUriAt(context, uri, sampleSize)
        if (firstResult.isNotEmpty()) return firstResult

        // 第二遍：截图/密集二维码场景下更高分辨率重试（JPEG 伪影、摩尔纹对小图伤害更大）
        if (sampleSize > 1) {
            val retryResult = decodeUriAt(context, uri, sampleSize / 2)
            if (retryResult.isNotEmpty()) return retryResult
        }
        return emptyList()
    }

    private fun decodeUriAt(context: Context, uri: Uri, sampleSize: Int): List<String> {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Cannot reopen input stream for $uri")
        val bitmap = stream.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw java.io.IOException(
            "BitmapFactory returned null at sampleSize=$sampleSize for $uri"
        )

        val width = bitmap.width
        val height = bitmap.height
        android.util.Log.d("QrGallery", "decoded bitmap ${width}x${height} sampleSize=$sampleSize")
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val luma = ByteArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            luma[i] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
        }
        return decodeWithFallbackChain(luma, width, height, 0)
    }

    /**
     * 统一解码链：4 向旋转 × 2 尺度 × 2 二值化器（HybridBinarizer → GlobalHistogramBinarizer）。
     *
     * 关键点：
     * 1. 不能用 BinaryBitmap.rotateCounterClockwise()——PlanarYUVLuminanceSource 不支持旋转
     *    （isRotateSupported=false），会抛 UnsupportedOperationException 导致相机竖屏每帧静默失败。
     *    改为手动旋转亮度矩阵后重建 source。
     * 2. GlobalHistogramBinarizer 兜底：对截图 JPEG 伪影、屏幕翻拍摩尔纹、渐变光照等
     *    HybridBinarizer 的已知弱项场景经常能成功（两者阈值策略互补）。
     * 3. 2x 降尺度兜底：密集二维码在低分辨率下 Hybrid 的分块网格会与模块对齐失配。
     */
    private fun decodeWithFallbackChain(
        data0: ByteArray,
        width0: Int,
        height0: Int,
        rotationDegrees: Int
    ): List<String> {
        var data = data0
        var width = width0
        var height = height0
        val initialRotations = ((360 - (rotationDegrees % 360)) % 360) / 90
        repeat(initialRotations) {
            data = rotateLumaCounterClockwise(data, width, height)
            val swap = width
            width = height
            height = swap
        }
        repeat(ROTATION_ATTEMPTS) {
            var currentData = data
            var currentWidth = width
            var currentHeight = height
            repeat(SCALE_ATTEMPTS) { scale ->
                if (scale > 0) {
                    if (currentWidth < MIN_SCALED_DIMENSION || currentHeight < MIN_SCALED_DIMENSION) {
                        return@repeat
                    }
                    currentData = downscaleLumaBy2(currentData, currentWidth, currentHeight)
                    currentWidth /= 2
                    currentHeight /= 2
                }
                // 同一亮度源可安全共享给两个二值化器（只读），二者阈值策略互补
                val source = PlanarYUVLuminanceSource(
                    currentData, currentWidth, currentHeight,
                    0, 0, currentWidth, currentHeight, false
                )
                val binarizers = arrayOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))
                for (binarizer in binarizers) {
                    val result = runCatching { reader.decode(BinaryBitmap(binarizer)) }.getOrNull()
                    if (result != null) {
                        val text = result.text?.trim()?.takeIf(String::isNotBlank)
                        reader.reset()
                        if (text != null) return listOf(text)
                    }
                    runCatching { reader.reset() }
                }
            }
            data = rotateLumaCounterClockwise(data, width, height)
            val swap = width
            width = height
            height = swap
        }
        runCatching { reader.reset() }
        return emptyList()
    }

    /** 逆时针旋转 90°：new[x, h-1-y] = old[y, x]，返回新数据（调用方需交换宽高）。 */
    private fun rotateLumaCounterClockwise(src: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                out[x * height + (height - 1 - y)] = src[rowOffset + x]
            }
        }
        return out
    }

    /** 2x 最近邻降采样（取每 2x2 块左上像素），返回新数据（调用方需宽高减半）。 */
    private fun downscaleLumaBy2(src: ByteArray, width: Int, height: Int): ByteArray {
        val outWidth = width / 2
        val outHeight = height / 2
        val out = ByteArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val srcRow = y * 2 * width
            val outRow = y * outWidth
            for (x in 0 until outWidth) {
                out[outRow + x] = src[srcRow + x * 2]
            }
        }
        return out
    }

    private fun readLuminancePlane(imageProxy: ImageProxy): ByteArray? {
        val width = imageProxy.width
        val height = imageProxy.height
        // 优先用 mediaImage 的 Y 平面，失败则回退到 ImageProxy 自身的 plane（兼容部分设备 image 为空的情况）
        val yPlaneBuffer: java.nio.ByteBuffer
        val yPixelStride: Int
        val yRowStride: Int
        val mediaImage = imageProxy.image
        if (mediaImage != null && mediaImage.format == android.graphics.ImageFormat.YUV_420_888) {
            val plane = mediaImage.planes.firstOrNull() ?: return null
            yPlaneBuffer = plane.buffer
            yPixelStride = plane.pixelStride
            yRowStride = plane.rowStride
        } else {
            // 回退：ImageProxy 暴露的 plane（YUV_420_888 时 planes[0] 为 Y）
            if (imageProxy.planes.isEmpty()) return null
            val plane = imageProxy.planes[0]
            yPlaneBuffer = plane.buffer
            yPixelStride = plane.pixelStride
            yRowStride = plane.rowStride
        }
        // 确保从头读取
        yPlaneBuffer.rewind()
        return if (yPixelStride == 1 && yRowStride == width) {
            val data = ByteArray(width * height)
            // 此时 buffer.remaining() 可能 >= width*height（含末尾 padding），只取有效像素
            val toRead = minOf(data.size, yPlaneBuffer.remaining())
            yPlaneBuffer.get(data, 0, toRead)
            // 若 toRead < data.size（极少数设备），剩余保持 0
            data
        } else {
            val data = ByteArray(width * height)
            var offset = 0
            for (row in 0 until height) {
                yPlaneBuffer.position(row * yRowStride)
                val copyLength = minOf(width, yPlaneBuffer.remaining())
                if (copyLength <= 0) break
                yPlaneBuffer.get(data, offset, copyLength)
                offset += width
            }
            data
        }
    }

    override fun close() {
        runCatching { reader.reset() }
    }

    private companion object {
        val DEFAULT_FORMATS = listOf(BarcodeFormat.QR_CODE)
        private const val MAX_IMAGE_DIMENSION = 1536
        private const val ROTATION_ATTEMPTS = 4
        private const val SCALE_ATTEMPTS = 2
        private const val MIN_SCALED_DIMENSION = 4
    }
}
