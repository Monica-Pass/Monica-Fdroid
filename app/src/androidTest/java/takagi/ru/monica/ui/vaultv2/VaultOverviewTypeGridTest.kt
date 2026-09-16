package takagi.ru.monica.ui.vaultv2

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ColorScheme
import takagi.ru.monica.data.VaultOverviewConfig
import takagi.ru.monica.data.VaultOverviewModule
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.cardwallet.WalletStackOverlayHost
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.util.Locale

/** Real overview rendering with synthetic counts; no user's vault or cloud connection is changed. */
@RunWith(AndroidJUnit4::class)
class VaultOverviewTypeGridTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var localeTag by mutableStateOf("ru")
    private var frameWidth by mutableStateOf(390.dp)
    private var textScale by mutableFloatStateOf(1f)
    private var dark by mutableStateOf(true)
    private var counts by mutableStateOf(mapOf(VaultV2ItemType.PASSWORD to 12,
        VaultV2ItemType.AUTHENTICATOR to 2, VaultV2ItemType.PASSKEY to 1))
    private var config by mutableStateOf(VaultOverviewConfig(
        hidden = VaultOverviewModule.entries.filter { it != VaultOverviewModule.TYPES }.map { it.name }.toSet(),
    ))
    private val opened = mutableListOf<VaultV2ItemType>()

    private fun localizedConfiguration() = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(localeTag))
        fontScale = textScale
        screenWidthDp = frameWidth.value.toInt()
    }

    private fun label(type: VaultV2ItemType) = context.createConfigurationContext(localizedConfiguration()).getString(type.titleRes())

    private fun show() {
        compose.setContent {
            val configuration = localizedConfiguration()
            val localized = remember(localeTag, textScale, frameWidth) { context.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, textScale)) {
                MonicaTheme(darkTheme = dark, colorScheme = ColorScheme.OCEAN_BLUE) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding()) {
                        Box(Modifier.width(frameWidth).fillMaxHeight().testTag("type_grid_test_frame")) {
                            WalletStackOverlayHost {
                                VaultOverviewScreen(
                                    snapshot = VaultOverviewSnapshot(scope = "local", accessibleSources = setOf("local"), typeCounts = counts),
                                    sources = listOf(VaultOverviewSource("local", "Monica", "Monica")),
                                    keepassDatabases = emptyList(), mdbxDatabases = emptyList(), bitwardenVaults = emptyList(),
                                    currentScope = "local", config = config, listState = rememberLazyListState(),
                                    securityManager = remember { SecurityManager(context) }, reduceAnimations = false, trashCount = 0,
                                    onConfigChange = { config = it(config) }, onSelectScope = {}, onOpenSource = {}, onOpenItem = {},
                                    onOpenType = { opened += it }, onOpenFolder = {}, onFavorites = {}, onArchive = {}, onTrash = {},
                                    onAllItems = {}, onSearch = {}, onUnlock = {},
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
        compose.onNodeWithTag("overview_type_grid").assertExists()
    }

    private fun tile(type: VaultV2ItemType) = compose.onNodeWithTag("overview_type_${type.name}")
    private fun textIn(type: VaultV2ItemType, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag("overview_type_${type.name}")), useUnmergedTree = true,
    )

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    private fun SemanticsNodeInteraction.assertReadable(): TextLayoutResult {
        val layout = textLayout()
        val info = fetchSemanticsNode().layoutInfo
        assertFalse("Text is truncated: ${layout.layoutInput.text}", layout.multiParagraph.didExceedMaxLines)
        (0 until layout.lineCount).forEach { line ->
            assertFalse(layout.isLineEllipsized(line))
            // String semantics can rebuild a paragraph at the parent's maximum width.
            // Check the actual glyph extents against the visible Text node instead.
            assertTrue("Line is clipped: ${layout.layoutInput.text}",
                layout.getLineRight(line) - layout.getLineLeft(line) <= info.width + 1f)
        }
        assertTrue("Text height is clipped: ${layout.layoutInput.text}", layout.multiParagraph.height <= info.height + 1f)
        return layout
    }

    private fun verifyGrid(columns: Int, name: String): Float {
        compose.waitForIdle()
        val first = tile(VaultV2ItemType.PASSWORD).getUnclippedBoundsInRoot()
        val width = (first.right - first.left).value
        val height = (first.bottom - first.top).value
        val metrics = JSONArray()
        VaultV2ItemType.entries.forEachIndexed { index, type ->
            val node = tile(type).assertHasClickAction()
            val bounds = node.getUnclippedBoundsInRoot()
            val tileWidth = (bounds.right - bounds.left).value
            val tileHeight = (bounds.bottom - bounds.top).value
            assertEquals("$type width", width, tileWidth, 0.6f)
            assertEquals("$type height", height, tileHeight, 0.6f)
            assertEquals("$type column", first.left.value + (index % columns) * (width + 8), bounds.left.value, 0.6f)
            assertEquals("$type row", first.top.value + (index / columns) * (height + 8), bounds.top.value, 1.2f)
            val text = textIn(type, label(type))
            val layout = text.assertReadable()
            assertEquals("$type must show the complete label", label(type).length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            val labelBounds = text.getUnclippedBoundsInRoot()
            assertTrue("$type label stays inside the tile", labelBounds.left.value >= bounds.left.value + 13 &&
                labelBounds.right.value <= bounds.right.value - 13 && labelBounds.bottom.value <= bounds.bottom.value - 13)
            val count = textIn(type, (counts[type] ?: 0).toString())
            count.assertReadable()
            val countBounds = count.getUnclippedBoundsInRoot()
            assertTrue("$type name has its own line", labelBounds.top.value >= countBounds.bottom.value + 8)
            assertTrue("$type count stays inside the tile", countBounds.right.value <= bounds.right.value - 13)
            assertEquals(textScale, text.fetchSemanticsNode().layoutInfo.density.fontScale, 0.001f)
            metrics.put(JSONObject().put("type", type.name).put("label", label(type)).put("lines", layout.lineCount)
                .put("widthDp", tileWidth).put("heightDp", tileHeight)
                .put("widthPx", node.fetchSemanticsNode().layoutInfo.width).put("heightPx", node.fetchSemanticsNode().layoutInfo.height))
        }
        val output = File(context.getExternalFilesDir("overview-type-grid"), "$name-metrics.json")
        output.writeText(JSONObject().put("locale", localeTag).put("fontScale", textScale).put("columns", columns).put("tiles", metrics).toString(2))
        return height
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        File(context.getExternalFilesDir("overview-type-grid"), "$name.png").outputStream().use {
            compose.onNodeWithTag("type_grid_test_frame").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun russianWrappedNamesUseTheTallestTileAcrossEveryRow() {
        show()
        textIn(VaultV2ItemType.AUTHENTICATOR, "Аутентификатор").assertExists()
        textIn(VaultV2ItemType.BILLING_ADDRESS, "Адрес для выставления счёта").assertExists()
        verifyGrid(2, "russian")
        assertTrue(textIn(VaultV2ItemType.BILLING_ADDRESS, label(VaultV2ItemType.BILLING_ADDRESS)).textLayout().lineCount > 1)
        capture("russian-dark")
    }

    @Test fun germanLongWordsRemainCompleteOnASmallerPhone() {
        localeTag = "de"; frameWidth = 360.dp; dark = false
        show()
        textIn(VaultV2ItemType.AUTHENTICATOR, "Authentifikator").assertExists()
        verifyGrid(2, "german")
        capture("german-light")
    }

    @Test fun polishNamesAndCountsStayAligned() {
        localeTag = "pl"; dark = false
        show()
        textIn(VaultV2ItemType.PASSWORD, "Hasło").assertExists()
        textIn(VaultV2ItemType.BILLING_ADDRESS, "Adres rozliczeniowy").assertExists()
        verifyGrid(2, "polish")
        capture("polish-light")
    }

    @Test fun chineseUsesCompactEqualTilesIncludingTheUnpairedLastItem() {
        localeTag = "zh-CN"; dark = false
        show()
        textIn(VaultV2ItemType.PASSWORD, "密码").assertExists()
        verifyGrid(2, "chinese")
        capture("chinese-light")
    }

    @Test fun otherSelectableLanguagesAlsoFitWithoutTruncation() {
        localeTag = "en"
        show()
        textIn(VaultV2ItemType.PASSWORD, "Password").assertExists()
        mapOf("en" to "Password", "lzh" to "密钥", "ja" to "パスワード", "ko" to "비밀번호",
            "es" to "Contraseña", "fr" to "Mot de passe", "vi" to "Mật khẩu").forEach { (tag, expected) ->
            compose.runOnIdle { localeTag = tag }
            textIn(VaultV2ItemType.PASSWORD, expected).assertExists()
            verifyGrid(2, tag)
        }
    }

    @Test fun narrowScreensAndLargeTextReflowToOneColumn() {
        frameWidth = 320.dp
        show()
        verifyGrid(1, "russian-narrow")
        capture("russian-narrow")
        compose.runOnIdle { textScale = 2f }
        verifyGrid(1, "russian-large")
        capture("russian-large-top")
        tile(VaultV2ItemType.BILLING_ADDRESS).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(VaultV2ItemType.BILLING_ADDRESS), opened)
        capture("russian-large-bottom")
    }

    @Test fun widthAndLanguageChangesRemeasureTheWholeGrid() {
        localeTag = "zh-CN"
        show()
        val compactHeight = verifyGrid(2, "resize-before")
        compose.runOnIdle { localeTag = "ru"; frameWidth = 360.dp }
        assertTrue(verifyGrid(2, "resize-wrapped") >= compactHeight)
        compose.runOnIdle { frameWidth = 320.dp }
        verifyGrid(1, "resize-narrow")
        compose.runOnIdle { localeTag = "zh-CN"; frameWidth = 390.dp }
        assertEquals(compactHeight, verifyGrid(2, "resize-restored"), 0.6f)
    }

    @Test fun largeCountsKeepLabelWidthAndEveryFilterKeepsItsClickTarget() {
        show()
        val labelWidth = textIn(VaultV2ItemType.PASSWORD, label(VaultV2ItemType.PASSWORD)).getUnclippedBoundsInRoot().let { it.right - it.left }
        compose.runOnIdle { counts = VaultV2ItemType.entries.associateWith { Int.MAX_VALUE } }
        verifyGrid(2, "large-counts")
        assertEquals(labelWidth, textIn(VaultV2ItemType.PASSWORD, label(VaultV2ItemType.PASSWORD)).getUnclippedBoundsInRoot().let { it.right - it.left })
        VaultV2ItemType.entries.forEach { type -> tile(type).performScrollTo().assertIsDisplayed().performClick() }
        assertEquals(VaultV2ItemType.entries, opened)
        compose.onNodeWithTag("overview_modules").performScrollToIndex(0)
        val first = tile(VaultV2ItemType.PASSWORD).fetchSemanticsNode().boundsInRoot
        val second = tile(VaultV2ItemType.AUTHENTICATOR).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset((first.right + second.left) / 2, first.center.y)) }
        assertEquals("Tapping a gap must not trigger a neighboring filter", 7, opened.size)
        tile(VaultV2ItemType.PASSWORD).performTouchInput { down(center) }
        capture("russian-held-tile")
        tile(VaultV2ItemType.PASSWORD).performTouchInput { cancel() }
        compose.onNodeWithTag("overview_toggle_TYPES").performClick()
        compose.onNodeWithTag("overview_type_grid").assertDoesNotExist()
        compose.onNodeWithTag("overview_toggle_TYPES").performClick()
        compose.onNodeWithTag("overview_type_grid").assertExists()
    }
}
