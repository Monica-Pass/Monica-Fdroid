package takagi.ru.monica.ui.images

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.ui.theme.MonicaTheme

class ImageSubscriptionUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ImageSourceFixture
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() { fixture = ImageSourceFixture() }
    @After fun cleanup() { fixture.close() }
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("image_sources_browser").captureToImage().asAndroidBitmap()
        File(context.filesDir, "image-sources-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun categorizedCatalogSearchAndSelectionWorkInBothThemes() {
        runBlocking {
            fixture.store.save(fixture.client.load("https://example.test/pack.json", "Icon pack", ImageSourceKind.ICON))
            fixture.store.save(fixture.client.load("https://example.test/pack.json", "Card pack", ImageSourceKind.CARD))
        }
        var dark by mutableStateOf(false)
        var selected = 0
        compose.setContent { MonicaTheme(darkTheme = dark) {
            ImageSubscriptionBrowser(initialKind = ImageSourceKind.CARD, onDismiss = {}, onSelect = { selected++; it.recycle() }, sourceStore = fixture.store, sourceClient = fixture.client)
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Card pack").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Icon pack").assertDoesNotExist()
        capture("catalog-light")
        compose.onNodeWithText("Card pack").performClick()
        compose.onNodeWithText(context.getString(R.string.image_sources_search)).performTextInput("Nothing")
        compose.onNodeWithText(context.getString(R.string.image_sources_no_results)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Purple card").assertIsDisplayed()
        capture("gallery-light")
        compose.runOnIdle { dark = true }
        capture("gallery-dark")
        compose.onNodeWithText("Purple card").performClick()
        compose.waitUntil(15_000) { selected == 1 }
        compose.runOnIdle { assertEquals(1, selected) }
    }
    @Test fun addSubscriptionFetchesAndPersistsBeforeClosing() {
        compose.setContent { MonicaTheme {
            ImageSubscriptionBrowser(onDismiss = {}, sourceStore = fixture.store, sourceClient = fixture.client)
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.image_sources_empty)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.image_sources_add)).performClick()
        compose.onNodeWithText(context.getString(R.string.image_sources_url)).performTextInput("https://example.test/pack.json")
        compose.onAllNodesWithText(context.getString(R.string.image_sources_add)).filter(hasClickAction()).onLast().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Sample pack").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, runBlocking { fixture.store.read() }.size)
    }
}
