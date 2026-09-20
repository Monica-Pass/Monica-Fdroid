package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.ui.components.TotpCodeCard
import takagi.ru.monica.ui.theme.MonicaTheme

@RunWith(AndroidJUnit4::class)
class AuthenticatorCardGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tilesWithoutAccountsDoNotReserveEmptySubtitle() = verify(tile = true, scale = 1f, digits = 6)
    @Test fun compactTilesShrinkNextBeforeHiding() = verify(tile = true, scale = 1.3f)
    @Test fun releaseTilesKeepCompleteCodesWithLargeText() = verify(tile = true, scale = 1.5f)
    @Test fun listTitlesDoNotChangeCardHeight() = verify(tile = false, scale = 1f, digits = 6)
    @Test fun largeTextListKeepsCodeOnOneLine() = verify(tile = false, scale = 1.5f)
    @Test fun listReservesBothConfiguredMetadataRows() = verify(tile = false, scale = 1.5f,
        fields = listOf(AuthenticatorCardDisplayField.ISSUER, AuthenticatorCardDisplayField.ACCOUNT_NAME))

    @Test fun hiddenTileMasksCurrentAndNextCodeUntilTapped() {
        compose.setContent {
            MonicaTheme {
                Box(Modifier.width(170.dp)) {
                    TileFixture(0, hidden = true) {}
                }
            }
        }
        val numeric = SemanticsMatcher("visible OTP") { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text.replace(" ", "").matches(Regex("[0-9]{8}")) }
        }
        compose.onAllNodes(numeric, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("card0").performClick()
        compose.onAllNodes(numeric, useUnmergedTree = true).assertCountEquals(2)
    }

    private fun verify(tile: Boolean, scale: Float, digits: Int = 8,
        fields: List<AuthenticatorCardDisplayField> = AuthenticatorCardDisplayField.DEFAULT_ORDER) {
        var copied = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MonicaTheme(darkTheme = scale > 1f) {
                    Surface(Modifier.width(360.dp).testTag("fixture")) {
                        val cards: @Composable () -> Unit = {
                            (0..1).forEach { index ->
                                val title = if (index == 0) "Github: a very long account title" else "stripe"
                                val data = TotpData(secret = "JBSWY3DPEHPK3PXP",
                                    issuer = if (index == 0 && AuthenticatorCardDisplayField.ISSUER in fields) "Github" else "",
                                    accountName = if (index == 0) "account@example.test" else "", digits = digits)
                                TotpCodeCard(
                                    item = SecureItem(id = index.toLong(), itemType = ItemType.TOTP,
                                        title = title, itemData = ""),
                                    parsedTotpData = data,
                                    modifier = Modifier.testTag("card$index"),
                                    onCopyCode = { copied = it },
                                    compactTile = tile,
                                    uniformAuthenticatorLayout = true,
                                    sharedTickSeconds = 1_700_000_010L,
                                    appSettings = AppSettings(iconCardsEnabled = false,
                                        authenticatorCardDisplayFields = fields,
                                        validatorSmoothProgress = false,
                                        validatorUnifiedProgressBar = UnifiedProgressBarMode.ENABLED)
                                )
                            }
                        }
                        if (tile) {
                            // The actual grid constraints: 12dp outer margins and an 8dp gutter.
                            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                                modifier = Modifier.wrapContentHeight(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item { TileFixture(0, digits = digits) { copied = it } }
                                item { TileFixture(1, digits = digits) { copied = it } }
                            }
                        } else Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { cards() }
                    }
                }
            }
        }
        compose.waitForIdle()
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "authenticator-${if (tile) "tile" else "list"}-$scale-${fields.size}.png")
        out.outputStream().use { compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (tile) {
            // Original compact TOTP tile size; equal but inflated tiles are a regression.
            compose.onNodeWithTag("card0").assertHeightIsEqualTo(142.dp)
            compose.onNodeWithTag("card1").assertHeightIsEqualTo(142.dp)
        }
        val first = compose.onNodeWithTag("card0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("card1").fetchSemanticsNode().boundsInRoot
        assertEquals("Cards keep equal outer heights regardless of account text", first.height, second.height, 1f)
        val numeric = SemanticsMatcher("OTP text") { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text.replace(" ", "").matches(Regex("[0-9]{$digits}")) }
        }
        val codes = compose.onAllNodes(numeric, useUnmergedTree = true)
        val expectedCodes = if (tile && scale >= 1.5f) 2 else 4
        assertEquals("Current codes remain visible; tight tiles may hide only the preview",
            expectedCodes, codes.fetchSemanticsNodes().size)
        val baselines = mutableListOf<Float>()
        repeat(expectedCodes) { i ->
            val layouts = mutableListOf<TextLayoutResult>()
            codes[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("All digits must fit: node=$i layouts=" + layouts.map {
                "size=${it.size}, lines=${it.lineCount}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, style=${it.layoutInput.style}"
            }, layouts.all {
                it.lineCount == 1 && !it.hasVisualOverflow
            })
            if (tile && scale == 1.3f && i % 2 == 1) {
                assertTrue("The preview shrinks before it is hidden",
                    layouts.single().layoutInput.style.fontSize.value < 14f)
            }
            baselines += codes[i].fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
        }
        if (tile) assertTrue("No account means no empty subtitle or forced code alignment",
            baselines[expectedCodes / 2] < baselines[0] - 1f)
        compose.onNodeWithTag("card1").performClick()
        assertTrue("Copy still returns a complete code", copied.matches(Regex("[0-9]{$digits}")))
    }

    @Composable private fun TileFixture(index: Int, hidden: Boolean = false, digits: Int = 8, onCopy: (String) -> Unit) {
        TotpCodeCard(
            item = SecureItem(id = index.toLong(), itemType = ItemType.TOTP,
                title = if (index == 0) "Github: a very long account title" else "stripe", itemData = ""),
            parsedTotpData = TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = "",
                accountName = if (index == 0) "account@example.test" else "", digits = digits),
            modifier = Modifier.testTag("card$index"), onCopyCode = onCopy,
            compactTile = true, uniformAuthenticatorLayout = true, sharedTickSeconds = 1_700_000_010L,
            appSettings = AppSettings(iconCardsEnabled = false, validatorSmoothProgress = false,
                authenticatorCardHideCodeByDefault = hidden,
                validatorUnifiedProgressBar = UnifiedProgressBarMode.ENABLED)
        )
    }
}
