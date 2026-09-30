package takagi.ru.monica.ui.cardwallet

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** Reads a document once, then normalizes it before encrypted attachment persistence. */
object CardFaceImageProcessor {
    const val CARD_ASPECT_RATIO = 85.60f / 53.98f
    private const val MAX_SOURCE_BYTES = 25L * 1024L * 1024L
    private const val MAX_SOURCE_DIMENSION = 32_768
    private const val OUTPUT_WIDTH = 1280
    private const val JPEG_QUALITY = 88

    enum class Failure { UNREADABLE, TOO_LARGE, UNSUPPORTED, DECODE_FAILED }
    class ImportException(val reason: Failure) : Exception(reason.name)

    data class Prepared(val bytes: ByteArray, val preview: Bitmap)

    suspend fun prepare(context: Context, uri: Uri): Result<Prepared> {
        val decoded = decode(context, uri).getOrElse { return Result.failure(it) }
        return try { crop(decoded, CardCropGeometry.centered(decoded.width, decoded.height)) }
        finally { decoded.recycle() }
    }

    suspend fun decode(context: Context, uri: Uri): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.applicationContext.contentResolver
            val sourceBytes = openSourceStream(resolver, uri)?.use {
                readBoundedBytes(it, MAX_SOURCE_BYTES) ?: throw ImportException(Failure.TOO_LARGE)
            } ?: throw ImportException(Failure.UNREADABLE)
            try {
                Result.success(decodeSource(sourceBytes))
            } finally {
                sourceBytes.fill(0)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ImportException) {
            Result.failure(error)
        } catch (_: SecurityException) {
            Result.failure(ImportException(Failure.UNREADABLE))
        } catch (_: OutOfMemoryError) {
            Result.failure(ImportException(Failure.DECODE_FAILED))
        } catch (_: Exception) {
            Result.failure(ImportException(Failure.DECODE_FAILED))
        }
    }

    private fun decodeSource(sourceBytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_SOURCE_DIMENSION || bounds.outHeight !in 1..MAX_SOURCE_DIMENSION) {
            throw ImportException(Failure.DECODE_FAILED)
        }
        if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp")) {
            throw ImportException(Failure.UNSUPPORTED)
        }
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies EXIF orientation, including mirrored camera images.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(sourceBytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(calculateSampleSize(info.size.width, info.size.height, OUTPUT_WIDTH * 2))
            }
        } else {
            decodeLegacy(sourceBytes, bounds)
        }
        return decoded
    }

    suspend fun crop(source: Bitmap, region: CardCropGeometry): Result<Prepared> = withContext(Dispatchers.Default) {
        try {
            val width = minOf(OUTPUT_WIDTH, region.width.toInt()).coerceAtLeast(1)
            val height = (width / CARD_ASPECT_RATIO).toInt().coerceAtLeast(1)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(output).apply {
                    drawColor(Color.WHITE)
                    drawCropSource(this, source, region, 0f, 0f, width.toFloat(),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                }
                val bytes = ByteArrayOutputStream().use { buffer ->
                    if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) {
                        throw ImportException(Failure.DECODE_FAILED)
                    }
                    buffer.toByteArray()
                }
                Result.success(Prepared(bytes, output.copy(Bitmap.Config.ARGB_8888, false)))
            } finally { output.recycle() }
        } catch (error: CancellationException) { throw error }
        catch (_: OutOfMemoryError) { Result.failure(ImportException(Failure.DECODE_FAILED)) }
        catch (_: Exception) { Result.failure(ImportException(Failure.DECODE_FAILED)) }
    }

    /** Draw the original bitmap through one transform; rotating never reallocates or recompresses it. */
    internal fun drawCropSource(canvas: Canvas, source: Bitmap, region: CardCropGeometry,
        left: Float, top: Float, frameWidth: Float, paint: android.graphics.Paint) {
        val checkpoint = canvas.save()
        try {
            val (width, height) = region.sourceSize(source.width, source.height)
            canvas.translate(left, top)
            val scale = frameWidth / region.width
            canvas.scale(scale, scale)
            canvas.translate(-region.left, -region.top)
            // Transparent PNG/WebP pixels have the same white backing in preview and export.
            val color = paint.color
            paint.color = Color.WHITE
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = color
            if (region.flipHorizontal) { canvas.translate(width.toFloat(), 0f); canvas.scale(-1f, 1f) }
            if (region.flipVertical) { canvas.translate(0f, height.toFloat()); canvas.scale(1f, -1f) }
            when (region.quarterTurns.mod(4)) {
                1 -> { canvas.translate(source.height.toFloat(), 0f); canvas.rotate(90f) }
                2 -> { canvas.translate(source.width.toFloat(), source.height.toFloat()); canvas.rotate(180f) }
                3 -> { canvas.translate(0f, source.width.toFloat()); canvas.rotate(270f) }
            }
            canvas.drawBitmap(source, 0f, 0f, paint)
        } finally { canvas.restoreToCount(checkpoint) }
    }

    private fun decodeLegacy(sourceBytes: ByteArray, bounds: BitmapFactory.Options): Bitmap {
        val decoded = BitmapFactory.decodeByteArray(
            sourceBytes, 0, sourceBytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, OUTPUT_WIDTH * 2)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw ImportException(Failure.DECODE_FAILED)
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(sourceBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return decoded
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } finally {
            decoded.recycle()
        }
    }

    /** Provider sizes can be unknown; enforce the limit using the bytes actually read. */
    internal fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray? {
        if (maxBytes <= 0L) return null
        val output = ByteArrayOutputStream(minOf(maxBytes, 64L * 1024L).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun openSourceStream(resolver: ContentResolver, uri: Uri): InputStream? {
        // Some document providers only expose typed assets, or cannot expose a seekable descriptor.
        return runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: runCatching { resolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.createInputStream() }.getOrNull()
            ?: runCatching {
                resolver.openFileDescriptor(uri, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            }.getOrNull()
    }

    internal fun centerCropSize(width: Int, height: Int, aspectRatio: Float): Pair<Int, Int> {
        require(width > 0 && height > 0 && aspectRatio > 0f)
        return if (width.toFloat() / height > aspectRatio) {
            (height * aspectRatio).toInt().coerceIn(1, width) to height
        } else {
            width to (width / aspectRatio).toInt().coerceIn(1, height)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) sample *= 2
        return sample
    }
}
