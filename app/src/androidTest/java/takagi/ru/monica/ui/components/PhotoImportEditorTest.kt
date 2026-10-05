package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.cardwallet.CardFaceImageProcessor
import takagi.ru.monica.ui.cardwallet.CardCropGeometry
import takagi.ru.monica.util.ImageManager

class PhotoImportEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun focus() {
        compose.runOnUiThread {
            compose.activity.setShowWhenLocked(true)
            compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }
    private fun source() = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.RED)
            drawRect(400f, 0f, 800f, 1000f, Paint().apply { color = Color.BLUE })
        }
    }
    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun cameraSkipAndGalleryCropSaveCorrectSidesWithoutChangingOriginal() {
        val source = source()
        val original = File(context.cacheDir, "photo-import-fixture-${UUID.randomUUID()}.png")
        original.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val originalBytes = original.readBytes()
        val saved = mutableListOf<String>()
        var front by mutableStateOf<String?>(null)
        var back by mutableStateOf<String?>(null)
        val manager = ImageManager(context)
        var cameraLaunches = 0
        var galleryLaunches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                when (contract) {
                    is ActivityResultContracts.RequestPermission -> dispatchResult(code, true)
                    is ActivityResultContracts.TakePicture -> {
                        cameraLaunches++
                        context.contentResolver.openOutputStream(input as Uri)!!.use {
                            source.compress(Bitmap.CompressFormat.JPEG, 100, it)
                        }
                        dispatchResult(code, true)
                    }
                    else -> { galleryLaunches++; dispatchResult(code, Uri.fromFile(original)) }
                }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            DualPhotoPicker(front, back,
                                { front = it; saved.add(it) }, {}, { back = it; saved.add(it) }, {})
                        }
                    }
                }
            }
            fun action(slot: String, label: Int) = compose.onNode(
                hasText(context.getString(label)) and hasAnyAncestor(hasTestTag(slot)))
            action("dual_photo_front", R.string.camera).performScrollTo().performClick()
            waitFor("photo_crop_skip")
            compose.onNodeWithTag("card_face_rotate_right").performClick()
            compose.onNodeWithTag("photo_crop_skip").performClick()
            compose.onNodeWithText(context.getString(R.string.photo_import_dialog_confirm)).performClick()
            compose.waitUntil(15_000) { front != null }
            val full = runBlocking { manager.loadImage(front!!) }!!
            assertEquals(800, full.width); assertEquals(1000, full.height)
            assertTrue(Color.red(full.getPixel(100, 100)) > 200)
            full.recycle()
            action("dual_photo_back", R.string.gallery).performScrollTo().performClick()
            waitFor("card_face_crop_confirm")
            compose.onNodeWithTag("card_face_flip_horizontal").performClick()
            compose.onNodeWithTag("card_face_crop_confirm").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.photo_import_dialog_confirm)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.photo_import_dialog_confirm)).performClick()
            compose.waitUntil(15_000) { back != null }
            val cropped = runBlocking { manager.loadImage(back!!) }!!
            assertEquals(CardFaceImageProcessor.CARD_ASPECT_RATIO, cropped.width.toFloat() / cropped.height, .01f)
            assertTrue(Color.blue(cropped.getPixel(100, cropped.height / 2)) > 200)
            cropped.recycle()
            assertEquals(2, saved.size); assertNotEquals(front, back)
            assertEquals(1, cameraLaunches); assertEquals(1, galleryLaunches)
            assertArrayEquals(originalBytes, original.readBytes())
            val before = File(context.filesDir, "secure_images").list()!!.toSet()
            action("dual_photo_front", R.string.gallery).performScrollTo().performClick()
            waitFor("photo_crop_skip")
            compose.onNodeWithTag("card_face_crop_cancel").performClick()
            compose.waitForIdle()
            assertEquals(2, saved.size)
            assertEquals(before, File(context.filesDir, "secure_images").list()!!.toSet())
        } finally {
            runBlocking { saved.forEach { manager.deleteImage(it) } }
            original.delete()
        }
    }

    @Test fun photoCropMatchesArtworkTransformWithoutMutatingSource() = runBlocking {
        val source = source()
        try {
            for (turn in 0..3) {
                val region = CardCropGeometry.centered(source.width, source.height, turn)
                    .flipped(source.width, source.height, true)
                val photo = CardFaceImageProcessor.cropPhoto(source, region).getOrThrow()
                val artwork = CardFaceImageProcessor.crop(source, region).getOrThrow()
                assertTrue(photo.sameAs(artwork.preview))
                photo.recycle(); artwork.preview.recycle(); artwork.bytes.fill(0)
            }
            assertEquals(Color.RED, source.getPixel(100, 100))
            assertEquals(Color.BLUE, source.getPixel(600, 100))
        } finally { source.recycle() }
    }
}
