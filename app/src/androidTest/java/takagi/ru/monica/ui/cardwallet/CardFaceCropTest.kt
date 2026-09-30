package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardFaceCropTest {
    @Test fun savesSelectedSourceRegionInsteadOfCenterCrop() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.RED)
            drawRect(800f, 0f, 1600f, 1000f, Paint().apply { color = Color.BLUE })
        }
        try {
            val right = CardCropGeometry.centered(1600, 1000).transform(1600, 1000, 4f, -10000f, 0f)
            val prepared = CardFaceImageProcessor.crop(source, right).getOrThrow()
            try {
                assertEquals(Color.BLUE, prepared.preview.getPixel(5, prepared.preview.height / 2))
                val saved = android.graphics.BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
                try { assertTrue(Color.blue(saved.getPixel(5, saved.height / 2)) > 240) }
                finally { saved.recycle() }
            } finally { prepared.preview.recycle(); prepared.bytes.fill(0) }
        } finally { source.recycle() }
    }

    @Test fun savedJpegMatchesEveryRotationAndMirrorWithoutChangingSource() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.RED)
            drawRect(200f, 0f, 400f, 200f, Paint().apply { color = Color.GREEN })
            drawRect(0f, 200f, 200f, 400f, Paint().apply { color = Color.BLUE })
            drawRect(200f, 200f, 400f, 400f, Paint().apply { color = Color.YELLOW })
        }
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE))
        try {
            for (turns in 0..3) for (horizontal in listOf(false, true)) for (vertical in listOf(false, true)) {
                val region = CardCropGeometry.centered(400, 400, turns).copy(flipHorizontal = horizontal, flipVertical = vertical)
                val rowFlipped = expected[turns].let { if (horizontal) listOf(it[1], it[0], it[3], it[2]) else it }
                val colors = rowFlipped.let { if (vertical) listOf(it[2], it[3], it[0], it[1]) else it }
                val prepared = CardFaceImageProcessor.crop(source, region).getOrThrow()
                val jpeg = android.graphics.BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
                try {
                    val points = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3)
                    points.forEachIndexed { i, (x, y) ->
                        val px = jpeg.width * x / 4
                        val py = jpeg.height * y / 4
                        assertEquals(colors[i], prepared.preview.getPixel(px, py))
                        val actual = jpeg.getPixel(px, py)
                        val color = colors[i]
                        assertTrue(kotlin.math.abs(Color.red(actual) - Color.red(color)) < 12)
                        assertTrue(kotlin.math.abs(Color.green(actual) - Color.green(color)) < 12)
                        assertTrue(kotlin.math.abs(Color.blue(actual) - Color.blue(color)) < 12)
                    }
                } finally { jpeg.recycle(); prepared.preview.recycle(); prepared.bytes.fill(0) }
            }
            assertEquals(Color.RED, source.getPixel(20, 20))
            assertEquals(Color.YELLOW, source.getPixel(380, 380))
        } finally { source.recycle() }
    }
}
