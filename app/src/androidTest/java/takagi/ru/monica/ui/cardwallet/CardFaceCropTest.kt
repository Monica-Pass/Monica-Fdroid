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
}
