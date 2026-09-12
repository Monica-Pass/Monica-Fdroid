package takagi.ru.monica.ui.common.pull

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R

@RunWith(AndroidJUnit4::class)
class PullSearchHintTest {
    @get:Rule val compose = createComposeRule()
    private var offset by mutableFloatStateOf(36f)
    private var localeTag by mutableStateOf("en")
    private var dark by mutableStateOf(false)
    private val palette get() = if (dark) darkColorScheme() else lightColorScheme()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show() {
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(localeTag))
                fontScale = 1.5f
            }
            val localizedContext = context.createConfigurationContext(configuration)
            val density = Density(LocalDensity.current.density, configuration.fontScale)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides density,
            ) {
                MaterialTheme(colorScheme = palette) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxHeight().wrapContentWidth().width(280.dp)) {
                            Text("Toolbar", Modifier.height(56.dp))
                            Box(Modifier.fillMaxSize()) {
                                val pixels = with(density) { offset.dp.toPx() }
                                PullSearchHint(currentOffset = pixels)
                                Text(
                                    "First item",
                                    Modifier.fillMaxWidth().offset { IntOffset(0, pixels.toInt()) }
                                        .height(72.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                                        .testTag("first_row"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hintLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("pull_search_hint").onChild()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertInsideGap() {
        val hint = compose.onNodeWithTag("pull_search_hint").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("pull_search_hint").onChild().fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("first_row").fetchSemanticsNode().boundsInRoot
        assertTrue("Hint overlaps the first row", hint.bottom <= row.top + 1f)
        assertTrue("Hint text leaves the exposed gap", text.top >= hint.top && text.bottom <= hint.bottom)
    }

    @Test fun textHighlightsAtThresholdAndRetreatsWithoutCoveringTheList() {
        show()
        assertEquals(palette.onSurfaceVariant.copy(alpha = 0.7f), hintLayout().layoutInput.style.color)
        assertInsideGap()
        compose.runOnIdle { offset = 80f }
        assertEquals(palette.primary, hintLayout().layoutInput.style.color)
        assertInsideGap()
        compose.runOnIdle { offset = 36f }
        assertEquals(palette.onSurfaceVariant.copy(alpha = 0.7f), hintLayout().layoutInput.style.color)
        compose.runOnIdle { offset = 0f }
        compose.onNodeWithTag("pull_search_hint").assertDoesNotExist()
    }

    @Test fun allAppLanguagesSwitchInPlaceAndFitANarrowScreenWithLargeText() {
        offset = 88f
        show()
        compose.onNodeWithTag("pull_search_hint").assertWidthIsEqualTo(280.dp)
        val localizedStrings = mutableSetOf<String>()
        for (night in listOf(false, true)) {
            for (tag in listOf("en", "zh", "vi", "ja", "ru", "ko", "de", "es")) {
                compose.runOnIdle { dark = night; localeTag = tag }
                val configuration = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                val expected = context.createConfigurationContext(configuration).getString(R.string.pull_release_to_search)
                localizedStrings.add(expected)
                compose.onNodeWithText(expected).assertIsDisplayed()
                val layout = hintLayout()
                assertFalse(
                    "Hint is clipped for $tag, dark=$night: size=${layout.size}, " +
                        "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
                        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                        "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}",
                    layout.hasVisualOverflow,
                )
                assertInsideGap()
            }
        }
        assertEquals("A supported language fell back to another language", 8, localizedStrings.size)
    }
}
