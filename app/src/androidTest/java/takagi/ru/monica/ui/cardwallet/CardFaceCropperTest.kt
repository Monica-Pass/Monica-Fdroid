package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.theme.MonicaTheme

class CardFaceCropperTest {
    @get:Rule val compose = createComposeRule()
    @Test fun cancelDoesNotApplyAndConfirmReturnsDisplayedRegion() {
        val source = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.rgb(15, 63, 111))
            drawRect(800f, 0f, 1600f, 1000f, Paint().apply { color = Color.rgb(30, 122, 147) })
            drawText("MONICA", 160f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 95f })
        }
        var cancelled = false
        var result: CardCropGeometry? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MonicaTheme {
            CardFaceCropper(source, false, null, { cancelled = true }, { result = it })
        } }
        compose.onRoot().captureToImage().asAndroidBitmap().let { screenshot ->
            File(context.getExternalFilesDir(null), "card-crop-preview.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(cancelled); assertNull(result) }
        compose.onNodeWithTag("card_face_crop_canvas").performTouchInput {
            pinch(center - Offset(40f, 0f), center + Offset(40f, 0f),
                center - Offset(100f, 0f), center + Offset(100f, 0f))
        }
        compose.onNodeWithTag("card_face_crop_canvas").performTouchInput {
            swipe(center, center + Offset(35f, 0f))
        }
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.runOnIdle { assertTrue(result!!.width < CardCropGeometry.centered(1600, 1000).width) }
        compose.onNodeWithText(context.getString(R.string.card_face_crop_reset)).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.runOnIdle { assertEquals(CardCropGeometry.centered(1600, 1000), result) }
    }
}
