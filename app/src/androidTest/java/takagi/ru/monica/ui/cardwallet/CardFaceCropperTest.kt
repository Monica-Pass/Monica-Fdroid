package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.junit.Before
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun focus() {
        compose.runOnUiThread {
            compose.activity.setShowWhenLocked(true)
            compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun photoControlsStayReachableWithLargeDarkText() {
        val source = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(22, 72, 65)) }
        var busy by mutableStateOf(false)
        var skipped = 0
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.6f)) {
                androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    CardFaceCropper(source, busy, null, {}, {}, onSkipCrop = { skipped++ })
                }
            }
        }
        compose.onNodeWithTag("photo_crop_skip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("card_face_crop_confirm").assertIsDisplayed()
        assertTrue("Large text actions must stack instead of squeezing words into narrow columns",
            compose.onNodeWithTag("photo_crop_skip").fetchSemanticsNode().boundsInRoot.bottom <=
                compose.onNodeWithTag("card_face_crop_confirm").fetchSemanticsNode().boundsInRoot.top)
        assertEquals(1, skipped)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            File(context.filesDir, "photo-crop-large-dark.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("photo_crop_skip").assertIsNotEnabled()
        compose.onNodeWithTag("card_face_crop_confirm").assertIsNotEnabled()
    }

    @Test fun portraitImageStaysInsideViewportDuringZoomAndPan() {
        val source = Bitmap.createBitmap(400, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MonicaTheme {
            CardFaceCropper(source, false, null, {}, {})
        } }

        compose.onNodeWithTag("photo_crop_skip").assertDoesNotExist()

        fun assertPreviewIsClipped(stage: String) {
            val root = compose.onRoot()
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("card_face_crop_canvas")
                .fetchSemanticsNode().boundsInRoot
            val top = viewport.top - rootBounds.top
            val bottom = viewport.bottom - rootBounds.top
            val screenshot = root.captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), "card-crop-portrait-$stage.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("The screenshot must include both bars", top > 0f && bottom < screenshot.height)
            assertEquals("The selected image must remain visible", Color.MAGENTA,
                screenshot.getPixel(screenshot.width / 2, ((top + bottom) / 2).toInt()))

            val pixels = IntArray(screenshot.width * screenshot.height)
            screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
            var leakedAbove = 0
            var leakedBelow = 0
            for (y in 0 until screenshot.height) {
                if (y + .5f >= top && y + .5f < bottom) continue
                for (x in 0 until screenshot.width) {
                    if (pixels[y * screenshot.width + x] == Color.MAGENTA) {
                        if (y + .5f < top) leakedAbove++ else leakedBelow++
                    }
                }
            }
            assertEquals("$stage: image must not leak into the footer", 0, leakedBelow)
            assertEquals("$stage: image must not leak into the toolbar", 0, leakedAbove)
        }

        assertPreviewIsClipped("initial")
        compose.onNodeWithTag("card_face_crop_canvas").performTouchInput {
            pinch(center - Offset(40f, 0f), center + Offset(40f, 0f),
                center - Offset(100f, 0f), center + Offset(100f, 0f))
        }
        compose.onNodeWithTag("card_face_crop_canvas").performTouchInput {
            swipe(center, center + Offset(35f, 120f))
        }
        assertPreviewIsClipped("zoom-pan-down")
        compose.onNodeWithTag("card_face_crop_canvas").performTouchInput {
            swipe(center, center - Offset(35f, 180f))
        }
        assertPreviewIsClipped("pan-up")
        compose.onNodeWithTag("card_face_rotate_left").performClick()
        assertPreviewIsClipped("rotated")
    }

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
        compose.onNodeWithTag("card_face_crop_cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertNull(result) }
        compose.onNodeWithTag("card_face_crop_canvas").performTouchInput {
            pinch(center - Offset(40f, 0f), center + Offset(40f, 0f),
                center - Offset(100f, 0f), center + Offset(100f, 0f))
        }
        compose.onNodeWithTag("card_face_crop_canvas").performTouchInput {
            swipe(center, center + Offset(35f, 0f))
        }
        compose.onNodeWithTag("card_face_crop_confirm").performClick()
        compose.runOnIdle { assertTrue(result!!.width < CardCropGeometry.centered(1600, 1000).width) }
        compose.onNodeWithTag("card_face_crop_reset").performClick()
        compose.onNodeWithTag("card_face_crop_confirm").performClick()
        compose.runOnIdle { assertEquals(CardCropGeometry.centered(1600, 1000), result) }
    }

    @Test fun rotateControlsMatchSavedPixelsAndResetAndBusyStates() {
        val source = Bitmap.createBitmap(400, 700, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.RED)
            drawRect(0f, 350f, 400f, 700f, Paint().apply { color = Color.BLUE })
        }
        var result: CardCropGeometry? = null
        var busy by mutableStateOf(false)
        compose.setContent { MonicaTheme { CardFaceCropper(source, busy, null, {}, { result = it }) } }
        compose.onNodeWithTag("card_face_rotate_right").performClick()
        compose.onNodeWithTag("card_face_crop_confirm").performClick()
        compose.runOnIdle { assertEquals(1, result!!.quarterTurns) }
        val screen = compose.onRoot().captureToImage().asAndroidBitmap()
        val bounds = compose.onNodeWithTag("card_face_crop_canvas").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val middleX = bounds.center.x - root.left
        val middleY = bounds.center.y - root.top
        val offset = minOf(bounds.width, bounds.height) / 8f
        assertEquals(Color.BLUE, screen.getPixel((middleX - offset).toInt(), middleY.toInt()))
        assertEquals(Color.RED, screen.getPixel((middleX + offset).toInt(), middleY.toInt()))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "card-crop-rotated.png").outputStream().use {
            screen.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        kotlinx.coroutines.runBlocking {
            val output = CardFaceImageProcessor.crop(source, result!!).getOrThrow()
            try {
                assertEquals(Color.BLUE, output.preview.getPixel(output.preview.width / 4, output.preview.height / 2))
                assertEquals(Color.RED, output.preview.getPixel(output.preview.width * 3 / 4, output.preview.height / 2))
            } finally { output.preview.recycle(); output.bytes.fill(0) }
        }
        compose.onNodeWithTag("card_face_flip_horizontal").performClick()
        compose.onNodeWithTag("card_face_flip_vertical").performClick()
        compose.onNodeWithTag("card_face_crop_confirm").performClick()
        compose.runOnIdle { assertTrue(result!!.flipHorizontal && result!!.flipVertical); assertEquals(1, result!!.quarterTurns) }
        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            File(context.getExternalFilesDir(null), "card-crop-mirrored.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("card_face_crop_reset").performClick()
        compose.onNodeWithTag("card_face_crop_confirm").performClick()
        compose.runOnIdle { assertEquals(CardCropGeometry.centered(400, 700), result) }
        compose.onNodeWithTag("card_face_rotate_right").performClick()
        compose.onNodeWithTag("card_face_rotate_left").performClick()
        compose.onNodeWithTag("card_face_crop_confirm").performClick()
        compose.runOnIdle { assertEquals(CardCropGeometry.centered(400, 700), result) }
        compose.onNodeWithTag("card_face_rotate_left").performClick()
        compose.onNodeWithTag("card_face_crop_reset").performClick()
        compose.onNodeWithTag("card_face_crop_confirm").performClick()
        compose.runOnIdle { assertEquals(CardCropGeometry.centered(400, 700), result); busy = true }
        listOf("card_face_rotate_left", "card_face_rotate_right", "card_face_crop_reset", "card_face_crop_cancel", "card_face_crop_confirm", "card_face_flip_horizontal", "card_face_flip_vertical").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }
}
