package takagi.ru.monica.ui.scanner

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZxingBarcodeDecoderTest {
    @Test fun unreadableFramesDoNotPoisonLaterSteamOrAuthenticatorRecognition() {
        val decoder = ZxingBarcodeDecoder(listOf(BarcodeFormat.QR_CODE))
        val blank = QrScannerFixtures.Frame(640, 480)
        repeat(300) { assertTrue(decoder.decodeFrame(blank.proxy()).isNullOrEmpty()) }
        for (payload in listOf(QrScannerFixtures.STEAM, QrScannerFixtures.TOTP)) {
            val frame = QrScannerFixtures.Frame().code(payload)
            for (rotation in listOf(0, 90, 180, 270)) {
                val observed = (0 until 6).flatMap { decoder.decodeFrame(frame.proxy(rotation)) }
                assertTrue("Payload must remain readable at $rotation degrees", observed.contains(payload))
            }
        }
    }

    @Test fun mixedNormalAndInvertedQrCodesRemainAvailableToTheCallerValidator() {
        val decoder = ZxingBarcodeDecoder(listOf(BarcodeFormat.QR_CODE))
        for (inverted in listOf(false, true)) {
            val frame = QrScannerFixtures.Frame()
                .code(QrScannerFixtures.UNRELATED, left = 70, top = 290, size = 380)
                .code(QrScannerFixtures.STEAM, left = 830, top = 290, size = 380, inverted = inverted)
            val decoded = decoder.decodeFrame(frame.proxy()).orEmpty()
            assertTrue("Ordinary QR must be returned", decoded.contains(QrScannerFixtures.UNRELATED))
            assertTrue("Valid Steam QR must not be hidden; inverted=$inverted", decoded.contains(QrScannerFixtures.STEAM))
            val bitmap = frame.bitmap()
            try {
                val gallery = decoder.decodeBitmap(bitmap)
                assertTrue(gallery.contains(QrScannerFixtures.UNRELATED))
                assertTrue(gallery.contains(QrScannerFixtures.STEAM))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun galleryAndCameraBothRecognizeInvertedAuthenticatorCodes() {
        val decoder = ZxingBarcodeDecoder(listOf(BarcodeFormat.QR_CODE))
        val frame = QrScannerFixtures.Frame().code(QrScannerFixtures.TOTP, inverted = true)
        assertTrue(decoder.decodeFrame(frame.proxy()).orEmpty().contains(QrScannerFixtures.TOTP))
        val bitmap = frame.bitmap()
        try { assertTrue(decoder.decodeBitmap(bitmap).orEmpty().contains(QrScannerFixtures.TOTP)) }
        finally { bitmap.recycle() }
    }

    @Test fun yPlanePositionPaddingPixelStrideAndCropAreRespected() {
        val frame = QrScannerFixtures.Frame()
            .code(QrScannerFixtures.UNRELATED, left = 50, top = 200, size = 400)
            .code(QrScannerFixtures.STEAM, left = 790, top = 200, size = 400)
        for (testPixelStride in listOf(1, 2)) {
            val prefix = 17
            val testRowStride = frame.width * testPixelStride + 37
            val bytes = ByteArray(prefix + testRowStride * (frame.height - 1) + (frame.width - 1) * testPixelStride + 1)
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                bytes[prefix + y * testRowStride + x * testPixelStride] = frame.pixels[y * frame.width + x]
            }
            val testBuffer = ByteBuffer.wrap(bytes).apply { position(prefix) }
            val plane = object : ImageProxy.PlaneProxy {
                override fun getBuffer() = testBuffer
                override fun getPixelStride() = testPixelStride
                override fun getRowStride() = testRowStride
            }
            val proxy = object : ImageProxy by frame.proxy() {
                override fun getPlanes() = arrayOf(plane)
                override fun getCropRect() = Rect(700, 100, 1280, 800)
            }
            val decoder = ZxingBarcodeDecoder(listOf(BarcodeFormat.QR_CODE))
            val observed = linkedSetOf<String>()
            repeat(6) {
                observed += decoder.decodeFrame(proxy)
                assertEquals("Decoding must not consume the shared plane buffer", prefix, testBuffer.position())
            }
            assertEquals(setOf(QrScannerFixtures.STEAM), observed)
        }
    }

    @Test fun smallQrStillDecodesAtFullResolutionBesideAnUnrelatedLargeCode() {
        for (inverted in listOf(false, true)) {
            val decoder = ZxingBarcodeDecoder(listOf(BarcodeFormat.QR_CODE))
            val frame = QrScannerFixtures.Frame()
                .code(QrScannerFixtures.UNRELATED, left = 70, top = 290, size = 380)
                // Two pixels per module at full resolution; decimation loses the
                // repeated finder-pattern observations required by ZXing's multi-reader.
                .code(QrScannerFixtures.STEAM, left = 1030, top = 380, size = 90, inverted = inverted)
            val observed = (0 until 6).flatMap { decoder.decodeFrame(frame.proxy()) }.toSet()
            assertTrue("Small QR must be found during a sweep; inverted=$inverted", observed.contains(QrScannerFixtures.STEAM))
            assertTrue(observed.contains(QrScannerFixtures.UNRELATED))
        }
    }

    @Test fun cameraAndGalleryRetainAllSupportedBarcodeFormats() {
        val samples = listOf(
            BarcodeFormat.QR_CODE to QrScannerFixtures.TOTP,
            BarcodeFormat.CODE_128 to "MONICA123",
            BarcodeFormat.CODE_39 to "MONICA123",
            BarcodeFormat.CODE_93 to "MONICA123",
            BarcodeFormat.EAN_13 to "5901234123457",
            BarcodeFormat.EAN_8 to "96385074",
            BarcodeFormat.UPC_A to "042100005264",
            BarcodeFormat.UPC_E to "04252614",
            BarcodeFormat.ITF to "12345670",
            BarcodeFormat.CODABAR to "A123456A",
            BarcodeFormat.DATA_MATRIX to "Monica matrix 123",
            BarcodeFormat.AZTEC to "Monica aztec 123",
            BarcodeFormat.PDF_417 to "Monica PDF 123"
        )
        for ((format, payload) in samples) {
            val decoder = ZxingBarcodeDecoder(listOf(format))
            val frame = QrScannerFixtures.Frame().code(payload, format = format)
            val expected = if (format == BarcodeFormat.CODABAR) "123456" else payload
            assertTrue("Camera format $format", decoder.decodeFrame(frame.proxy()).contains(expected))
            val bitmap = frame.bitmap()
            try {
                assertTrue("Gallery format $format", decoder.decodeBitmap(bitmap).contains(expected))
            } finally {
                bitmap.recycle()
            }
        }
    }
}
