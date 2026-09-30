package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.GeneratorPreferences
import takagi.ru.monica.ui.components.CredentialGeneratorSheet
import takagi.ru.monica.ui.components.GeneratorSuggestion
import java.io.File

class CredentialGeneratorSheetTest {
    @get:Rule val compose = createComposeRule()
    private var applied: String? = null
    private fun show(username: Boolean = false, prefs: GeneratorPreferences = GeneratorPreferences(), large: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                MaterialTheme(colorScheme = if (large) darkColorScheme() else lightColorScheme()) {
                    CredentialGeneratorSheet(username, listOf(GeneratorSuggestion("Fixture", if (username) "sample@example.com" else "test-template-secret")),
                        prefs, {}, { applied = it })
                }
            }
        }
    }
    private fun result(): String = compose.onNodeWithTag("generator_result").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
    private fun awaitResult() { compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("generator_result").fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotEmpty() } == true && compose.onAllNodesWithTag("generator_apply").fetchSemanticsNodes().firstOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
    } }
    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
    private fun mode(name: String) {
        node("generator_kind").performClick(); compose.onNodeWithTag("generator_kind_$name").performClick(); awaitResult()
    }
    private fun capture(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "generator-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onNodeWithTag("credential_generator_scroll").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun longPasswordAndLargeFontRemainUsableAndTemplatesShowPlaintext() {
        show(prefs = GeneratorPreferences(symbolLength = 128), large = true); awaitResult()
        assertEquals(128, result().length)
        node("generator_suggestions"); compose.onNodeWithText("test-template-secret").assertExists()
        capture("password-dark-large")
        val expected = result(); node("generator_apply").performClick(); assertEquals(expected, applied)
        node("generator_suggestions").assertIsDisplayed()
        compose.onNodeWithTag("generator_suggestion_0").performClick()
        compose.waitUntil(5_000) { applied == "test-template-secret" }
        node("generator_symbols").assertIsDisplayed()
        capture("password-options-dark-large")
    }
    @Test fun invalidCharacterSelectionCannotApplyStaleResult() {
        show(); awaitResult(); capture("password-light")
        listOf("upper", "lower", "numbers", "symbols").forEach { node("generator_$it").performClick() }
        node("generator_apply").assertIsNotEnabled(); compose.onNodeWithTag("generator_error").assertExists()
        assertNull(applied)
        node("generator_lower").performClick(); awaitResult(); assertTrue(result().all { it in 'a'..'z' })
        val before = result(); node("generator_refresh").performClick(); awaitResult(); assertNotEquals(before, result())
    }
    @Test fun usernameAndEmailAliasUseExplicitInput() {
        show(username = true); awaitResult(); assertTrue(result().matches(Regex("[a-z0-9]{12}")))
        mode("EMAIL"); assertTrue(result().matches(Regex("sample\\+[a-z0-9]{5}@example\\.com")))
        node("generator_email").performTextReplacement("person@example.org"); awaitResult()
        assertTrue(result().matches(Regex("person\\+[a-z0-9]{5}@example\\.org")))
        node("generator_apply").performClick(); assertEquals(result(), applied)
        capture("username-email")
        node("generator_email").performTextReplacement("invalid")
        node("generator_apply").assertIsNotEnabled()
    }
    @Test fun pinAndPhraseSwitchAndLengthControlsRegenerate() {
        show(); awaitResult(); mode("PIN"); assertTrue(result().matches(Regex("[0-9]{6}")))
        node("generator_length_increase").performClick(); awaitResult(); assertEquals(7, result().length)
        mode("PHRASE"); assertEquals(5, result().split('-').size)
        node("generator_length_decrease").performClick(); awaitResult(); assertEquals(4, result().split('-').size)
    }
    @Test fun draggingRetainsResultAndSliderPositionUntilRelease() {
        show(username = true); awaitResult()
        val before = result()
        val slider = node("generator_length_slider")
        val top = slider.fetchSemanticsNode().boundsInRoot.top
        slider.performTouchInput { down(center); moveTo(centerRight, delayMillis = 500) }
        compose.waitForIdle()
        assertEquals(before, result())
        compose.onNodeWithTag("generator_apply").assertIsNotEnabled()
        assertEquals(top, slider.fetchSemanticsNode().boundsInRoot.top, 1f)
        slider.performTouchInput { up() }
        awaitResult()
        assertEquals(32, result().length)
        assertEquals(top, slider.fetchSemanticsNode().boundsInRoot.top, 1f)
        capture("drag-released")
    }

    @Test fun longPasswordShrinksTextWithinFixedCard() {
        show(prefs = GeneratorPreferences(symbolLength = 12)); awaitResult()
        val slider = node("generator_length_slider")
        val top = slider.fetchSemanticsNode().boundsInRoot.top
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(128f) }
        awaitResult()
        assertEquals(128, result().length)
        assertEquals(top, slider.fetchSemanticsNode().boundsInRoot.top, 1f)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("generator_result").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.style.fontSize.value < 24f)
        val layout = layouts.single()
        if (layout.layoutInput.density.fontScale <= 1f) {
            assertTrue("Normal-size text must fit the capped viewport", layout.size.height <= 96f * compose.density.density + 1f)
        } else if (layout.size.height > 96f * compose.density.density) {
            assertEquals("Accessibility overflow must first reach the readable minimum", 12f, layout.layoutInput.style.fontSize.value, .01f)
        }
        capture("password-auto-fit")
    }

}
