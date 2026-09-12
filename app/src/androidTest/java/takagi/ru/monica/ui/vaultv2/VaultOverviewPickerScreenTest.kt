package takagi.ru.monica.ui.vaultv2

import android.graphics.Bitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_SIMPLE

@RunWith(AndroidJUnit4::class)
class VaultOverviewPickerScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cards = (1L..3L).map { id ->
        val secure = SecureItem(id = id, title = "Everyday card", itemType = ItemType.BANK_CARD,
            bitwardenVaultId = if (id == 3L) 2 else null, createdAt = Date(10),
            itemData = CardWalletDataCodec.encodeBankCardData(BankCardData(
                cardNumber = if (id == 3L) "6222021234569876" else "411111111111000$id",
                cardholderName = "Demo", bankName = if (id == 3L) "Work Bank" else "Test bank $id",
                expiryMonth = "09", expiryYear = "2030")))
        VaultV2Item("bank_card:$id", VaultV2ItemType.BANK_CARD, secure.title, "", false, "$id", emptyList(), secureItem = secure)
    }
    private val passwords = (10L..12L).map { id -> buildVaultV2PasswordItems(listOf(PasswordEntry(
        id = id, title = if (id == 11L) "GitHub" else "Google", username = "demo$id@example.test", password = "",
        website = "google.com", bitwardenVaultId = if (id == 12L) 2 else null, createdAt = Date(10),
        customIconType = if (id == 11L) PASSWORD_ICON_TYPE_SIMPLE else "NONE", customIconValue = if (id == 11L) "github" else null,
    ))).single() }
    private val sources = listOf(VaultOverviewSource("local", "Personal", "Monica"),
        VaultOverviewSource("bitwarden:2", "Work", "Bitwarden"), VaultOverviewSource("keepass:3", "Locked", "KeePass", true),
        VaultOverviewSource("mdbx:4", "Project MDBX", "MDBX"))
    private var config by mutableStateOf(VaultOverviewConfig(pinnedCards = cards.take(2).map { it.overviewIdentity() },
        pinnedItems = passwords.map { it.overviewIdentity() }))
    private var shown by mutableStateOf(true)

    private fun showPicker(wallet: Boolean, scope: String = "all", dark: Boolean = false,
        pickerItems: List<VaultV2Item> = cards + passwords, frequentItems: List<VaultV2Item> = emptyList()) {
        val security = SecurityManager(context)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                TextButton(onClick = { shown = true }) { Text("Open picker") }
                if (shown) VaultOverviewPickerSheet(
                    cards = wallet, items = pickerItems.filter { scope == "all" || it.overviewSource() == scope },
                    currentFrequentItems = frequentItems,
                    sources = sources, currentScope = scope,
                    keepassDatabases = listOf(LocalKeePassDatabase(3, "Locked", "locked.kdbx")),
                    mdbxDatabases = listOf(LocalMdbxDatabase(4, "Project MDBX", "project.mdbx")),
                    bitwardenVaults = listOf(BitwardenVault(id = 2, email = "work@example.test", displayName = "Work", isLocked = false)),
                    config = config, securityManager = security, onConfigChange = { config = it(config).normalized() },
                    onDismiss = { shown = false },
                )
            }
        }
        waitForPicker()
    }

    @Test fun bankBrandAndTailDistinguishCardsAndSelectionsSurviveDatabaseFilters() {
        showPicker(wallet = true, dark = true)
        val first = compose.onNodeWithTag("overview_pin_row_bank_card:1")
        first.assertIsOn().assertTextContains("Test bank 1").assertTextContains("•••• 0001")
        compose.onNodeWithTag("overview_pin_brand_bank_card:1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_row_password:10").assertDoesNotExist()
        val position = first.fetchSemanticsNode().boundsInRoot
        first.performClick().assertIsOff()
        assertEquals(position, first.fetchSemanticsNode().boundsInRoot)
        first.performClick().assertIsOn()
        chooseDatabase("work@example.test")
        first.assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_row_bank_card:3").assertIsOff().assertTextContains("Work Bank").performClick().assertIsOn()
        chooseDatabase(context.getString(R.string.category_selection_menu_local_database))
        first.assertIsOn()
        compose.onNodeWithTag("overview_pin_row_bank_card:3").assertDoesNotExist()
        chooseDatabase(context.getString(R.string.category_all))
        capturePicker("picker-cards-dark.png")
        compose.onNodeWithTag("overview_pin_done").performClick()
        compose.onNodeWithTag("overview_pin_sheet").assertDoesNotExist()
        compose.onNodeWithText("Open picker").performClick()
        compose.onNodeWithTag("overview_pin_row_bank_card:3").assertIsOn()
        compose.runOnIdle { assertEquals("local", config.scope); assertEquals(3, config.pinnedCards.size) }
    }

    @Test fun accountIconsAndSearchRemainVisibleAfterKeyboardBackAndImeSearch() {
        showPicker(wallet = false)
        compose.onNodeWithTag("overview_pin_row_password:10").assertTextContains("Google").assertTextContains("demo10@example.test")
        compose.onNodeWithTag("overview_pin_icon_password:11", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_row_bank_card:1").assertDoesNotExist()
        capturePicker("picker-items-light.png")
        chooseDatabase("work@example.test")
        compose.onNodeWithTag("overview_pin_row_password:10").assertDoesNotExist()
        val search = compose.onNodeWithTag("overview_pin_search")
        search.performClick().performTextInput("demo")
        try {
            compose.waitUntil(5000) {
                compose.onAllNodesWithText(context.getString(R.string.vault_overview_pin_hint)).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            // The platform IME animates in a different process from Compose.
            // Let that animation settle for the visual evidence, not assertions.
            android.os.SystemClock.sleep(500)
            File(context.getExternalFilesDir("overview-verification"), "picker-items-keyboard.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("overview_pin_done").assertIsDisplayed()
        assertSheetMeetsKeyboard()
        Espresso.pressBack()
        compose.onNodeWithTag("overview_pin_sheet").assertIsDisplayed()
        search.assertTextContains("demo")
        compose.onNodeWithTag("overview_pin_row_password:12").assertIsDisplayed()
        search.performClick().performImeAction()
        compose.onNodeWithTag("overview_pin_sheet").assertIsDisplayed()
        search.assertTextContains("demo")
        chooseDatabase(context.getString(R.string.category_selection_menu_local_database))
        search.assertTextContains("demo")
        compose.onNodeWithTag("overview_pin_row_password:10").assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_row_password:12").assertDoesNotExist()
        compose.runOnIdle { assertEquals("local", config.scope) }
    }

    @Test fun bankSearchLockedAndEmptyDatabasesHaveDistinctStates() {
        showPicker(wallet = true)
        val search = compose.onNodeWithTag("overview_pin_search")
        search.performTextInput("bank 2")
        search.performImeAction()
        compose.onNodeWithTag("overview_pin_row_bank_card:2").assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_row_bank_card:1").assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_clear").performClick()
        chooseDatabase("Locked")
        compose.onNodeWithText(context.getString(R.string.vault_overview_locked_hint)).assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_row_bank_card:1").assertDoesNotExist()
        chooseDatabase("Project MDBX")
        compose.onNodeWithText(context.getString(R.string.vault_overview_empty_pins)).assertIsDisplayed()
        chooseDatabase(context.getString(R.string.category_all))
        compose.onNodeWithTag("overview_pin_row_bank_card:1").assertIsDisplayed()
    }

    @Test fun aSingleDatabaseStaysScopedAndThePinLimitNeverSilentlyDropsASelection() {
        config = config.copy(pinnedCards = listOf(cards[0].overviewIdentity()) + (1..198).map { "previous:$it" })
        showPicker(wallet = true, scope = "local")
        compose.onNodeWithTag("overview_pin_scope").assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_row_bank_card:3").assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_row_bank_card:2").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(200, config.pinnedCards.size) }
        compose.onNodeWithText(context.getString(R.string.vault_overview_picker_limit, 200)).assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_row_bank_card:1").performClick().assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("overview_pin_row_bank_card:2").assertIsOn()
        compose.runOnIdle { assertEquals(199, config.pinnedCards.size) }
    }

    @Test fun frequentCardsLeadThePickerAndTogglingDoesNotMoveRows() = verifyFrequentOrder(wallet = true)

    @Test fun frequentItemsLeadThePickerAndTogglingDoesNotMoveRows() = verifyFrequentOrder(wallet = false)

    private fun verifyFrequentOrder(wallet: Boolean) {
        val entries = if (wallet) cards else passwords
        config = if (wallet) config.copy(pinnedCards = listOf(entries[2].overviewIdentity()))
            else config.copy(pinnedItems = listOf(entries[2].overviewIdentity()))
        showPicker(wallet, frequentItems = listOf(entries[2], entries[1]))
        assertPickerOrder(entries[2], entries[1], entries[0])
        val last = compose.onNodeWithTag("overview_pin_row_${entries[2].key}")
        val position = last.fetchSemanticsNode().boundsInRoot
        last.assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("overview_pin_row_${entries[0].key}").assertIsOff().performClick().assertIsOn()
        assertEquals(position, last.fetchSemanticsNode().boundsInRoot)
        assertPickerOrder(entries[2], entries[1], entries[0])
        compose.onNodeWithTag("overview_pin_done").performClick()
        compose.onNodeWithText("Open picker").performClick()
        waitForPicker()
        assertPickerOrder(entries[0], entries[2], entries[1])
        capturePicker(if (wallet) "picker-cards-priority.png" else "picker-items-priority.png")
    }

    @Test fun eightItemLimitIsGlobalAcrossDatabasesAndSearchAndAllowsReplacingASelection() {
        val entries = buildVaultV2PasswordItems((1L..10L).map { id -> PasswordEntry(
            id = id, title = "Account $id", username = "demo$id@example.test", password = "", website = "",
            bitwardenVaultId = if (id > 8) 2 else null, createdAt = Date(10),
        ) })
        config = config.copy(pinnedItems = entries.take(8).map { it.overviewIdentity() }, recommendItems = false)
        showPicker(wallet = false, pickerItems = entries)
        compose.onNodeWithTag("overview_pin_selected_count")
            .assertTextEquals(context.getString(R.string.vault_overview_picker_selected, 8))
        compose.onNodeWithText(context.getString(R.string.vault_overview_picker_limit, 8)).assertIsDisplayed()
        chooseDatabase("work@example.test")
        val ninth = compose.onNodeWithTag("overview_pin_row_password:9")
        ninth.assertIsOff().assertIsNotEnabled()
        val search = compose.onNodeWithTag("overview_pin_search")
        search.performTextInput("demo9")
        search.performImeAction()
        ninth.assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("overview_pin_row_password:10").assertDoesNotExist()
        compose.onNodeWithTag("overview_pin_clear").performClick()
        chooseDatabase(context.getString(R.string.category_selection_menu_local_database))
        compose.onNodeWithTag("overview_pin_row_password:1").assertIsOn().assertIsEnabled().performClick().assertIsOff()
        compose.onNodeWithTag("overview_pin_selected_count")
            .assertTextEquals(context.getString(R.string.vault_overview_picker_selected, 7))
        chooseDatabase("work@example.test")
        ninth.assertIsEnabled().performClick().assertIsOn()
        compose.onNodeWithTag("overview_pin_row_password:10").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(8, config.pinnedItems.size)
            assertTrue(entries[8].overviewIdentity() in config.pinnedItems)
            assertFalse(entries[0].overviewIdentity() in config.pinnedItems)
        }
        capturePicker("picker-items-limit.png")
        compose.onNodeWithTag("overview_pin_done").performClick()
        compose.onNodeWithText("Open picker").performClick()
        waitForPicker()
        chooseDatabase("work@example.test")
        ninth.assertIsOn().assertIsEnabled()
        compose.onNodeWithTag("overview_pin_row_password:10").assertIsNotEnabled()
    }

    private fun waitForPicker() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("overview_pin_results").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertPickerOrder(vararg entries: VaultV2Item) {
        val tops = entries.map { entry ->
            compose.onNodeWithTag("overview_pin_row_${entry.key}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Expected picker order ${entries.map { it.key }}, got positions $tops", tops.zipWithNext().all { (a, b) -> a < b })
    }

    private fun chooseDatabase(label: String) {
        compose.onNodeWithTag("overview_pin_scope").performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("overview_pin_database_menu"))).performClick()
    }

    private fun assertSheetMeetsKeyboard() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val originalFlags = info.flags
        try {
            info.flags = originalFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            val ime = automation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            assertNotNull("A real keyboard must be visible for this check", ime)
            val bounds = android.graphics.Rect()
            ime!!.getBoundsInScreen(bounds)
            // Measure a child after the sheet's placement modifier. The outer
            // sheet semantics are attached before its draggable offset.
            val footer = compose.onNodeWithTag("overview_pin_footer").fetchSemanticsNode().boundsInWindow
            assertTrue("Footer bottom ${footer.bottom} must meet IME top ${bounds.top}", kotlin.math.abs(footer.bottom - bounds.top) <= 4f)
        } finally {
            info.flags = originalFlags
            automation.serviceInfo = info
        }
    }

    private fun capturePicker(name: String) {
        compose.waitForIdle()
        File(context.getExternalFilesDir("overview-verification"), name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
