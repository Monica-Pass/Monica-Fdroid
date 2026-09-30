package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    private fun show(rtl: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedbackEnabled provides feedback,
                LocalReduceAnimations provides reduced,
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MonicaTheme(darkTheme = dark) {
                    foreground = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                    Box(Modifier.width(320.dp).testTag("tiga_frame")) {
                        MdbxTigaModeSelector(mode, { mode = it }, { pulses++ })
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
    }

    @Test fun externallyLoadedModeKeepsItsMeaningAndCorrectPosition() {
        reduced = true
        show()
        for ((index, value) in listOf(MdbxTigaMode.SKY, MdbxTigaMode.MULTI, MdbxTigaMode.POWER).withIndex()) {
            compose.runOnIdle { mode = value }
            compose.onNodeWithTag("tiga_selected_mode").assertTextEquals(value.label)
            val info = compose.onNodeWithTag("tiga_slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(index.toFloat(), info.current)
        }
        assertEquals(0, pulses)
    }

    @Test fun physicalDragSelectsBothEndsAndMiddle() {
        show()
        compose.onNodeWithTag("tiga_slider").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(MdbxTigaMode.SKY, mode)
        compose.onNodeWithTag("tiga_slider").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(MdbxTigaMode.POWER, mode)
        compose.onNodeWithTag("tiga_slider").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(MdbxTigaMode.MULTI, mode)
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

    @Test fun threeModeColorsAndLargeTextRenderInBothThemes() {
        show()
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; scale = if (night) 2f else 1f }
            for (value in MdbxTigaMode.entries) {
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
