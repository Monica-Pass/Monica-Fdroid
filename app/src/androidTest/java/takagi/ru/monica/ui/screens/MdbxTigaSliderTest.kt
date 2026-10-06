package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled
import takagi.ru.monica.ui.LocalReduceAnimations
import takagi.ru.monica.ui.theme.MonicaTheme

class MdbxTigaSliderTest {
    @get:Rule val compose = createComposeRule()
    private var mode by mutableStateOf(MdbxTigaMode.MULTI)
    private var feedback by mutableStateOf(true)
    private var dark by mutableStateOf(false)
    private var scale by mutableStateOf(1f)
    private var foreground = 0
    private var pulses = 0
    private var reduced by mutableStateOf(false)
    private lateinit var scrollState: ScrollState
    private fun show(rtl: Boolean = false, scrollable: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedbackEnabled provides feedback,
                LocalReduceAnimations provides reduced,
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MonicaTheme(darkTheme = dark) {
                    foreground = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                    val scroll = rememberScrollState()
                    SideEffect { scrollState = scroll }
                    Box(Modifier.width(320.dp).testTag("tiga_frame")) {
                        Column(if (scrollable) Modifier.height(300.dp).verticalScroll(scroll) else Modifier) {
                            MdbxTigaModeSelector(mode, { mode = it }, { pulses++ })
                            if (scrollable) Spacer(Modifier.height(1200.dp))
                        }
                    }
                }
            }
        }
    }
    private fun progress(value: Float) = compose.onNodeWithTag("tiga_slider")
        .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }

    @Test fun discreteSelectionDoesNotRepeatHapticsAndHonorsTheGlobalSwitch() {
        show()
        progress(0f)
        assertEquals(MdbxTigaMode.SKY, mode)
        progress(0f)
        assertEquals(1, pulses)
        progress(2f)
        assertEquals(MdbxTigaMode.POWER, mode)
        assertEquals(2, pulses)
        compose.runOnIdle { feedback = false }
        progress(1f)
        assertEquals(MdbxTigaMode.MULTI, mode)
        assertEquals(2, pulses)
        compose.runOnIdle { mode = MdbxTigaMode.POWER }
        compose.onNodeWithTag("tiga_selected_mode").assertTextEquals("Power")
        assertEquals(2, pulses)
        compose.runOnIdle { feedback = true }
        progress(2f)
        assertEquals("Selecting an externally restored current mode is silent", 2, pulses)
        progress(0f)
        assertEquals("Re-enabling feedback must take effect immediately", 3, pulses)
    }

    @Test fun externallyLoadedModeKeepsItsMeaningAndCorrectPosition() {
        reduced = true
        show()
        for ((index, value) in listOf(MdbxTigaMode.SKY, MdbxTigaMode.MULTI, MdbxTigaMode.POWER).withIndex()) {
            compose.runOnIdle { mode = value }
            compose.onNodeWithTag("tiga_selected_mode").assertTextEquals(value.label)
            val info = compose.onNodeWithTag("tiga_slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(index.toFloat(), info.current)
            assertEquals(0f..2f, info.range)
            assertEquals(1, info.steps)
        }
        assertEquals(0, pulses)
    }

    @Test fun physicalDragSelectsAllThreeDetents() {
        show()
        compose.onNodeWithTag("tiga_slider").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(MdbxTigaMode.SKY, mode)
        compose.onNodeWithTag("tiga_slider").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(MdbxTigaMode.POWER, mode)
        compose.onNodeWithTag("tiga_slider").performTouchInput { click(Offset(width / 3f, centerY)) }
        compose.waitForIdle()
        assertEquals(MdbxTigaMode.MULTI, mode)
        compose.onNodeWithTag("tiga_mode_POWER").performClick()
        assertEquals(MdbxTigaMode.POWER, mode)
    }

    @Test fun rtlMirrorsTheSliderWithoutChangingModeMeaning() {
        show(rtl = true)
        compose.onNodeWithTag("tiga_slider").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(MdbxTigaMode.POWER, mode)
        compose.onNodeWithTag("tiga_slider").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(MdbxTigaMode.SKY, mode)
    }

    @Test fun keyboardMovesBetweenDetents() {
        show()
        val slider = compose.onNodeWithTag("tiga_slider")
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        slider.performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(MdbxTigaMode.SKY, mode) }
        slider.performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(MdbxTigaMode.MULTI, mode) }
    }

    @Test fun reducedMotionKeepsSparklesStatic() {
        reduced = true
        show()
        val frame = compose.onNodeWithTag("tiga_frame")
        val before = frame.captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(6400)
        val after = frame.captureToImage().asAndroidBitmap()
        assertTrue("Reduced-motion rendering must remain static", before.sameAs(after))
        before.recycle()
        after.recycle()
    }

    private fun track() = compose.onNodeWithTag("tiga_track").captureToImage().asAndroidBitmap()

    private fun thumbFraction(bitmap: Bitmap): Float {
        val y = bitmap.height / 2
        var longestStart = 0
        var longestSize = 0
        var start = -1
        for (x in 0..bitmap.width) {
            val white = x < bitmap.width && bitmap.getPixel(x, y).let {
                android.graphics.Color.red(it) > 250 && android.graphics.Color.green(it) > 250 && android.graphics.Color.blue(it) > 250
            }
            if (white && start == -1) start = x
            if (!white && start != -1) {
                if (x - start > longestSize) { longestStart = start; longestSize = x - start }
                start = -1
            }
        }
        assertTrue("The rendered white thumb must be present", longestSize > bitmap.height / 3)
        val radius = bitmap.height * 20f / 56f
        return ((longestStart + (longestSize - 1) / 2f - radius) / (bitmap.width - radius * 2)).coerceIn(0f, 1f)
    }

    private fun pointerAt(fraction: Float, scope: TouchInjectionScope): Offset = with(scope) {
        val radius = height * 20f / 56f
        Offset(radius + (width - radius * 2) * fraction, centerY)
    }

    private fun saveFrame(name: String) {
        val bitmap = compose.onNodeWithTag("tiga_frame").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "tiga-motion-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun clickRendersIntermediateThumbPositionsBeforeSettling() {
        dark = true
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(600)
        progress(2f)
        compose.mainClock.advanceTimeByFrame()
        val frames = mutableListOf<Float>()
        repeat(22) { index ->
            compose.mainClock.advanceTimeBy(16)
            val image = track()
            frames += thumbFraction(image)
            image.recycle()
            saveFrame("click-${index.toString().padStart(2, '0')}")
        }
        assertTrue("Click must produce visible intermediate frames", frames.count { it > 0.55f && it < 0.94f } >= 3)
        assertEquals(1f, frames.last(), 0.01f)
        assertEquals(1, pulses)
    }

    @Test fun heldDragMovesWithinADetentReversesAndAnimatesRelease() {
        dark = true
        mode = MdbxTigaMode.SKY
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(600)
        val slider = compose.onNodeWithTag("tiga_slider")
        slider.performTouchInput { down(pointerAt(0f, this)); moveTo(pointerAt(0.30f, this), 200) }
        compose.mainClock.advanceTimeBy(32)
        var image = track()
        assertEquals(0.30f, thumbFraction(image), 0.015f)
        image.recycle()
        assertEquals(MdbxTigaMode.MULTI, mode)
        assertEquals(1, pulses)
        slider.performTouchInput { moveTo(pointerAt(0.43f, this), 120) }
        compose.mainClock.advanceTimeBy(32)
        image = track()
        assertEquals("Thumb must follow the finger even within the same detent", 0.43f, thumbFraction(image), 0.015f)
        image.recycle()
        assertEquals(1, pulses)
        saveFrame("held-between-detents")
        slider.performTouchInput { moveTo(pointerAt(0.9f, this), 120) }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(MdbxTigaMode.POWER, mode)
        slider.performTouchInput { moveTo(pointerAt(0.43f, this), 120) }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(MdbxTigaMode.MULTI, mode)
        assertEquals(3, pulses)
        slider.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(64)
        image = track()
        val releasing = thumbFraction(image)
        image.recycle()
        assertTrue("Release must start from the last finger position", releasing > 0.43f && releasing < 0.5f)
        compose.mainClock.advanceTimeBy(400)
        image = track()
        assertEquals(0.5f, thumbFraction(image), 0.01f)
        image.recycle()
        assertEquals("Settling must not duplicate detent feedback", 3, pulses)
    }

    @Test fun sparkleFramesChangeOnlyOnTheFilledSide() {
        dark = true
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(600)
        val before = track()
        compose.mainClock.advanceTimeBy(750)
        val after = track()
        val radius = before.height * 20f / 56f
        val thumb = radius + (before.width - radius * 2) / 2f
        var activeChanges = 0
        var inactiveChanges = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before.getPixel(x, y) != after.getPixel(x, y)) {
                if (x < thumb - radius - 2) activeChanges++
                if (x > thumb + radius + 6) inactiveChanges++
            }
        }
        assertTrue("Visible stars must twinkle between frames", activeChanges > 10)
        assertEquals("Unfilled track must stay completely still", 0, inactiveChanges)
        // Static dim stars would also be wrong: the unused upper track is uniformly empty.
        val sampleX = (thumb + radius + 12).toInt()
        val sampleY = (before.height / 2 - radius / 2).toInt()
        val background = after.getPixel(sampleX, sampleY)
        for (x in sampleX until (before.width - radius).toInt()) {
            for (y in (before.height / 2 - radius + 4).toInt() until (before.height / 2 - radius * 0.3f).toInt()) {
                assertEquals("No star may remain beyond the thumb", background, after.getPixel(x, y))
            }
        }
        before.recycle(); after.recycle()
    }

    @Test fun reducedMotionStillFollowsFingerAndCancelSnapsWithoutExtraFeedback() {
        dark = true
        reduced = true
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(32)
        progress(2f)
        compose.mainClock.advanceTimeBy(32)
        var image = track()
        assertEquals(1f, thumbFraction(image), 0.01f)
        image.recycle()
        val slider = compose.onNodeWithTag("tiga_slider")
        slider.performTouchInput { down(pointerAt(1f, this)); moveTo(pointerAt(0.57f, this), 200) }
        compose.mainClock.advanceTimeBy(32)
        image = track()
        assertEquals(0.57f, thumbFraction(image), 0.015f)
        image.recycle()
        val beforeCancel = pulses
        slider.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(32)
        image = track()
        assertEquals(0.5f, thumbFraction(image), 0.01f)
        image.recycle()
        assertEquals(beforeCancel, pulses)
    }

    @Test fun verticalSwipeScrollsTheFormWithoutChangingSecurityMode() {
        show(scrollable = true)
        compose.onNodeWithTag("tiga_slider").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("Horizontal slider must not consume vertical form scrolling", scrollState.value > 0)
        assertEquals(MdbxTigaMode.MULTI, mode)
        assertEquals(0, pulses)
    }

    @Test fun remoteCreationKeepsOnlyThreeModesDuringDragAndAccessibilitySelection() {
        show()
        compose.onNodeWithTag("tiga_mode_GLITTER").assertDoesNotExist()
        val slider = compose.onNodeWithTag("tiga_slider")
        slider.performTouchInput { swipeRight() }
        assertEquals(MdbxTigaMode.POWER, mode)
        progress(100f)
        assertEquals(MdbxTigaMode.POWER, mode)
        val info = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f..2f, info.range)
        assertEquals(1, info.steps)
        slider.performTouchInput { swipeLeft() }
        assertEquals(MdbxTigaMode.SKY, mode)
    }

    @Test fun threeModeColorsAndLargeTextRenderInBothThemes() {
        show()
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; scale = if (night) 2f else 1f }
            for (value in mdbxTigaModesForCreation()) {
                compose.onNodeWithTag("tiga_mode_${value.name}").performClick()
                compose.onNodeWithTag("tiga_selected_mode").assertTextEquals(value.label)
                compose.waitForIdle()
                val bitmap = compose.onNodeWithTag("tiga_frame").captureToImage().asAndroidBitmap()
                for (x in listOf(bitmap.width / 4, bitmap.width * 3 / 4)) {
                    val background = bitmap.getPixel(x, 5)
                    assertTrue("Description must retain AA contrast", ColorUtils.calculateContrast(foreground, background) >= 4.5)
                    val luminance = ColorUtils.calculateLuminance(background)
                    assertTrue("Tint must stay very light or very dark", if (night) luminance < 0.04 else luminance > 0.85)
                }
                val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                    "tiga-${if (night) "dark-large" else "light"}-${value.name}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
