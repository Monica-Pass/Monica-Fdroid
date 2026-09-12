package takagi.ru.monica.ui.vaultv2

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.SettingsManager

/** End-to-end regression; opt in with -e overviewFreshInstall true on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class VaultOverviewMainActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualLoginCreationAndCardDetailReturn() {
        assumeTrue("This test configures a fresh disposable emulator",
            InstrumentationRegistry.getArguments().getString("overviewFreshInstall") == "true" &&
                (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val security = SecurityManager(context)
        assumeFalse("Use the fresh dedicated emulator only", security.isMasterPasswordSet())
        val settings = SettingsManager(context)
        val database = PasswordDatabase.getDatabase(context)
        runBlocking {
            settings.updateQuickSetupCompleted(true)
            settings.updateLanguage(Language.ENGLISH)
            settings.updateBiometricEnabled(false)
            settings.updateScreenshotProtectionEnabled(false)
            settings.updateVaultOverviewEnabled(true)
            settings.updateBottomNavVisibility(BottomNavContentTab.VAULT_V2, true)
            BottomNavContentTab.entries.filter { it != BottomNavContentTab.VAULT_V2 }.forEach {
                settings.updateBottomNavVisibility(it, false)
            }
            val card = SecureItem(itemType = ItemType.BANK_CARD, title = "Overview smoke card", createdAt = Date(10),
                itemData = Json.encodeToString(BankCardData(cardNumber = "4111111111111111", bankName = "Demo bank",
                    cardholderName = "DEMO", expiryMonth = "09", expiryYear = "2030")))
            val id = database.secureItemDao().insertItem(card)
            val passwords = listOf("GitHub" to "https://github.com", "Google" to "https://google.com").map { (title, website) ->
                val entry = PasswordEntry(title = title, website = website, username = "demo@example.com", password = "", isFavorite = true)
                entry.copy(id = database.passwordEntryDao().insertPasswordEntry(entry))
            }
            settings.updateVaultOverviewConfig { it.copy(
                pinnedCards = listOf(card.copy(id = id).vaultOverviewKey()),
                pinnedItems = passwords.map { password -> password.vaultOverviewKey() },
            ) }
        }
        security.setMasterPassword("overview311")
        SecurityManager.clearRuntimeUnlockCache()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("vault_overview_screen").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodes(hasSetTextAction())[0].performTextInput("overview311")
                compose.onAllNodes(hasSetTextAction())[0].performImeAction()
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("vault_overview_screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("vault_add_fab").performClick()
            compose.onAllNodesWithText(context.getString(R.string.item_type_password), useUnmergedTree = true).onLast().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.add_password_title)).fetchSemanticsNodes().isNotEmpty() }
            val evidence = File(context.getExternalFilesDir("overview-verification"), "main-creation-semantics.txt")
            evidence.writeText(compose.onRoot(useUnmergedTree = true).printToString())
            Espresso.pressBack()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("vault_overview_screen").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("wallet_stack_overview:local").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("wallet_stack_overview:local").performClick()
            compose.onNodeWithTag("wallet_stack_browser").assertIsDisplayed()
            compose.onNodeWithContentDescription("Overview smoke card").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("wallet_stack_browser").fetchSemanticsNodes().isEmpty() }
            Espresso.pressBack()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("wallet_stack_browser").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            // The browser used to remain visible but reject this action after the real
            // navigation transition. A standalone screen test does not reproduce it.
            compose.onNodeWithTag("wallet_stack_collapse").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("wallet_stack_browser").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("overview_card_deck").assertIsDisplayed()
            File(context.getExternalFilesDir("overview-verification"), "main-overview.png").outputStream().use { out ->
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }
}
