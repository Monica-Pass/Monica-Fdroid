package takagi.ru.monica.ui.vaultv2

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.SettingsManager

/** Runs the real navigation and animation on an explicitly disposable emulator. */
@RunWith(AndroidJUnit4::class)
class OverviewTakeoverRegressionTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val cardIds = mutableListOf<Long>()
    private val setup = object : ExternalResource() {
        override fun before() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("takeoverFreshInstall") == "true" &&
                (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")))
            val context = instrumentation.targetContext
            val database = PasswordDatabase.getDatabase(context)
            val settings = SettingsManager(context)
            runBlocking {
                database.clearAllTables()
                settings.updateQuickSetupCompleted(true)
                settings.updateLanguage(Language.ENGLISH)
                settings.updateBiometricEnabled(false)
                settings.updateScreenshotProtectionEnabled(false)
                settings.updateVaultOverviewEnabled(true)
                BottomNavContentTab.entries.forEach {
                    settings.updateBottomNavVisibility(it, it == BottomNavContentTab.VAULT_V2)
                }
                val cards = (1..4).map { index ->
                    val card = SecureItem(
                        itemType = ItemType.BANK_CARD, title = "Audit card $index", createdAt = Date(index.toLong()),
                        itemData = Json.encodeToString(BankCardData(
                            cardNumber = "411111111111${index.toString().padStart(4, '0')}",
                            bankName = "Audit bank $index", cardholderName = "DEMO", expiryMonth = "09", expiryYear = "2030",
                        )),
                    )
                    card.copy(id = database.secureItemDao().insertItem(card)).also { cardIds += it.id }
                }
                val password = PasswordEntry(title = "Audit login", username = "audit-user@example.test",
                    website = "https://example.test", password = "", isFavorite = true)
                val saved = password.copy(id = database.passwordEntryDao().insertPasswordEntry(password))
                settings.updateVaultOverviewConfig { VaultOverviewConfig(
                    scope = "local", pinnedCards = cards.map { it.vaultOverviewKey() },
                    pinnedItems = listOf(saved.vaultOverviewKey()),
                ) }
            }
            SecurityManager(context).setMasterPassword("audit-overview311")
            SecurityManager.clearRuntimeUnlockCache()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(setup).around(compose)

    private fun unlock() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("overview_card_deck").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("audit-overview311")
            compose.onAllNodes(hasSetTextAction())[0].performImeAction()
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("wallet_stack_overview:local").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun collapseAndCheckEveryFrame(expected: Rect, label: String) {
        val positions = mutableListOf<Rect>()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("wallet_stack_collapse").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("wallet_stack_collapse").performClick()
            repeat(35) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onAllNodesWithTag("wallet_stack_card_${cardIds.first()}").fetchSemanticsNodes()
                    .firstOrNull()?.let { node -> positions += node.boundsInWindow }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        val directory = instrumentation.targetContext.getExternalFilesDir("takeover-verification")!!
        File(directory, "$label-frames.txt").writeText("Expected: $expected\n" + positions.joinToString("\n"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue("No collapse animation frames were captured", positions.isNotEmpty())
        val last = positions.last()
        assertTrue("$label ends at $last, but the real overview cover is at $expected",
            abs(last.left - expected.left) <= 2f && abs(last.top - expected.top) <= 2f &&
                abs(last.right - expected.right) <= 2f && abs(last.bottom - expected.bottom) <= 2f)
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
        compose.onNodeWithTag("overview_card_deck").assertIsDisplayed()
    }

    @Test fun cardReturnsToTheSameBoundsWithAndWithoutOpeningDetail() {
        unlock()
        compose.waitForIdle()
        val expected = compose.onNodeWithTag("wallet_stack_cover", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        compose.onNodeWithTag("wallet_stack_overview:local").performClick()
        collapseAndCheckEveryFrame(expected, "direct-return")

        compose.onNodeWithTag("wallet_stack_overview:local").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("wallet_stack_card_${cardIds.first()}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("wallet_stack_card_${cardIds.first()}").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("wallet_stack_browser").fetchSemanticsNodes().isEmpty() }
        Espresso.pressBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("wallet_stack_browser").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        collapseAndCheckEveryFrame(expected, "detail-return")
    }

    @Test fun pullSearchStaysInOverviewAndKeepsResultsAfterKeyboardBack() {
        unlock()
        compose.onNodeWithTag("overview_modules").performTouchInput {
            swipe(start = center.copy(y = height * 0.08f), end = center.copy(y = height * 0.78f), durationMillis = 700)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("audit-user")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("overview_search_results").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Audit login").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNodeWithText("Audit login").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("overview_card_deck").assertIsDisplayed()
    }

    @Test fun searchButtonKeepsTheQueryWhenReturningFromPasswordDetail() {
        unlock()
        compose.onNodeWithTag("overview_search").performClick()
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("audit-user")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Audit login").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Audit login").performClick()
        compose.waitUntil(15_000) { !compose.onNodeWithTag("overview_search_results").isDisplayed() }
        Espresso.pressBack()
        compose.waitUntil(15_000) { compose.onNodeWithTag("overview_search_results").isDisplayed() }
        compose.onNode(hasSetTextAction()).assertTextEquals("audit-user")
        compose.onNodeWithText("Audit login").assertIsDisplayed()
    }

    @Test fun frenchLanguageSurvivesRestartAndLocalizesTheOverview() {
        unlock()
        runBlocking { SettingsManager(instrumentation.targetContext).updateLanguage(Language.FRENCH) }
        compose.activityRule.scenario.recreate()
        unlock()
        val french = instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale.FRENCH) })
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(french.getString(R.string.vault_overview_cards)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(french.getString(R.string.vault_overview_title)) and
            hasAnyAncestor(hasTestTag("overview_top_bar"))).assertIsDisplayed()
        compose.onNodeWithText(french.getString(R.string.vault_overview_cards)).assertIsDisplayed()
        assertEquals("Monica", french.getString(R.string.app_name))
        assertEquals("KeePass", french.getString(R.string.filter_keepass))
        assertEquals("Steam", french.getString(R.string.nav_steam))
        assertEquals("Coffre", french.getString(R.string.nav_v2_vault_short))
        assertEquals("Param.", french.getString(R.string.nav_settings_short))
        val directory = instrumentation.targetContext.getExternalFilesDir("takeover-verification")!!
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "french-overview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
