package takagi.ru.monica.ui.vaultv2

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.flowOf
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
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.VaultV2FabMenu
import takagi.ru.monica.ui.VaultV2FabMenuAction
import takagi.ru.monica.ui.cardwallet.WalletStackOverlayHost
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenu
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_NONE
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_SIMPLE

@RunWith(AndroidJUnit4::class)
class VaultOverviewScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cards = (1L..2L).map { id ->
        val secure = SecureItem(id = id, itemType = ItemType.BANK_CARD, title = "Wallet card $id", createdAt = Date(10),
            itemData = Json.encodeToString(BankCardData(cardNumber = "411111111111${id.toString().padStart(4, '0')}",
                cardholderName = "MONICA DEMO", bankName = "Test bank $id", expiryMonth = "09", expiryYear = "2030")))
        VaultV2Item("bank_card:$id", VaultV2ItemType.BANK_CARD, secure.title, "", false, "$id", emptyList(), secureItem = secure)
    }
    private val passwords = (10L..12L).map { id -> buildVaultV2PasswordItems(listOf(PasswordEntry(
        id = id, title = "Password $id", username = "demo", website = if (id != 12L) "https://google.com" else "",
        customIconType = if (id == 11L) PASSWORD_ICON_TYPE_SIMPLE else PASSWORD_ICON_TYPE_NONE,
        customIconValue = if (id == 11L) "github" else null, password = "", createdAt = Date(10),
        bitwardenVaultId = if (id == 12L) 2 else null, isFavorite = true))).single() }
    private val rows = cards + passwords
    private val keepassDatabases = listOf(LocalKeePassDatabase(id = 3L, name = "Locked", filePath = "locked.kdbx"))
    private val mdbxDatabases = listOf(LocalMdbxDatabase(id = 4L, name = "Project MDBX", filePath = "project.mdbx"))
    private val bitwardenVaults = listOf(BitwardenVault(id = 2L, email = "work@example.test", displayName = "Work", isLocked = false))
    private val sources = listOf(VaultOverviewSource("local", "Local", "Monica"),
        VaultOverviewSource("bitwarden:2", "Work", "Bitwarden"), VaultOverviewSource("keepass:3", "Locked", "KeePass", locked = true),
        VaultOverviewSource("mdbx:4", "Project MDBX", "MDBX"))
    private var config by mutableStateOf(VaultOverviewConfig(pinnedCards = cards.map { it.overviewIdentity() },
        pinnedItems = passwords.map { it.overviewIdentity() }))
    private var scopeKey by mutableStateOf("local")
    private var selectedCardKey by mutableStateOf<String?>(null)
    private val cardStack = VaultOverviewCardStackState()
    private var route by mutableStateOf<String?>(null)
    private var created: String? = null
    private lateinit var listState: LazyListState

    private fun showOverview(wideDetail: Boolean = false) {
        val manager = SecurityManager(context)
        compose.setContent {
            MaterialTheme {
                WalletStackOverlayHost {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                    if (route != null) Button(onClick = { route = null }, modifier = Modifier.testTag("return_home")) {
                        Text("Return")
                    }
                    if (route == null || wideDetail) {
                        val snapshot = remember(scopeKey, config.pinnedCards, config.pinnedItems, config.recommendCards, config.recommendItems) {
                            buildVaultOverviewSnapshot(rows, sources, scopeKey, config, emptyMap(), emptyMap(),
                                emptyList(), emptyList(), emptyMap(), emptyList(), aggregate = ::aggregateVaultOverviewKotlin)
                        }
                        VaultOverviewScreen(snapshot, sources, keepassDatabases, mdbxDatabases, bitwardenVaults, scopeKey, config, listState,
                            selectedCardKey = selectedCardKey, onSelectedCardChange = { selectedCardKey = it },
                            cardStackState = cardStack, isDetailVisible = wideDetail && route != null,
                            securityManager = manager, reduceAnimations = false, trashCount = 0,
                            onConfigChange = { config = it(config).normalized() }, onSelectScope = { scopeKey = it },
                            onOpenSource = { route = "source:$it" }, onOpenItem = { route = it.key },
                            onOpenType = { route = "type:${it.name}:$scopeKey" }, onOpenFolder = { route = "folder:${it.key}" },
                            onFavorites = { route = "favorites:$scopeKey" }, onArchive = { route = "archive:$scopeKey" },
                            onTrash = { route = "trash:$scopeKey" }, onAllItems = { route = "all:$scopeKey" },
                            onSearch = { route = "search:$scopeKey" }, onUnlock = { route = "unlock:$scopeKey" })
                        VaultV2FabMenu(0.dp, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                            onExpandStateChanged = {}, menuActions = listOf(
                                VaultV2FabMenuAction(Icons.Default.Add, R.string.item_type_password) { created = "password:$scopeKey" }))
                    }
                }
                }
            }
        }
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
    }

    @Test fun cardAndItemPickersAreSeparateAndUseTheExistingCreationFab() {
        showOverview()
        compose.onNodeWithTag("overview_pin_cards").performClick()
        compose.onNodeWithTag("overview_pin_row_bank_card:1").assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_row_password:10").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_pin_items"))
        compose.onNodeWithTag("overview_pin_items").performClick()
        compose.onNodeWithTag("overview_pin_row_password:10").assertIsDisplayed()
        compose.onNodeWithTag("overview_pin_row_bank_card:1").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithTag("vault_add_fab").performClick()
        compose.onNode(
            hasText(context.getString(R.string.item_type_password)) and
                !hasAnyAncestor(hasTestTag("overview_modules")),
            useUnmergedTree = true,
        ).performClick()
        compose.runOnIdle { assertEquals("password:local", created) }
    }

    @Test fun moduleVisibilityOrderAndCollapseWorkIndependently() {
        showOverview()
        compose.onNodeWithTag("overview_customize").performClick()
        compose.onNodeWithTag("overview_move_down_CARDS").performClick()
        compose.runOnIdle { assertEquals("ITEMS", config.order.first()) }
        compose.onNodeWithTag("overview_visible_CARDS").performClick()
        Espresso.pressBack()
        compose.onNodeWithTag("overview_cards").assertDoesNotExist()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_toggle_FAVORITES"))
        compose.onNodeWithTag("overview_toggle_FAVORITES").performClick()
        compose.runOnIdle { assertTrue("FAVORITES" in config.collapsed); assertFalse("ITEMS" in config.collapsed) }
    }

    @Test fun returningFromACardAndArchivePreservesTheHomeCardAndScroll() {
        showOverview()
        compose.waitUntil(5000) { cardStack.prepared?.cards?.size == 2 }
        compose.onNodeWithTag("wallet_stack_overview:local").performClick()
        compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeUp() }
        compose.waitUntil(5000) { selectedCardKey == cards[1].overviewIdentity() }
        compose.onNodeWithTag("wallet_stack_card_2").performClick()
        compose.runOnIdle { assertEquals(cards[1].key, route) }
        compose.onNodeWithTag("return_home").performClick()
        compose.runOnIdle { assertEquals(cards[1].overviewIdentity(), selectedCardKey) }
        compose.onNodeWithTag("wallet_stack_card_2").assertIsDisplayed()
        compose.onNodeWithTag("wallet_stack_collapse").performClick()
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_archive"))
        var position = 0 to 0
        compose.runOnIdle { position = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
        compose.onNodeWithTag("overview_archive").performClick()
        compose.runOnIdle { assertEquals("archive:local", route) }
        compose.onNodeWithTag("return_home").performClick()
        compose.runOnIdle { assertEquals(position, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset) }
    }

    @Test fun closedDeckHasNoHorizontalPagingAndBackCollapsesTheSharedBrowser() {
        showOverview()
        compose.waitUntil(5000) { cardStack.prepared?.cards?.size == 2 }
        compose.runOnIdle { selectedCardKey = cards[0].overviewIdentity() }
        compose.onNodeWithTag("overview_card_deck").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertNull(route); assertEquals(cards[0].overviewIdentity(), selectedCardKey) }
        if (!cardStack.expanded) compose.onNodeWithTag("wallet_stack_overview:local").performClick()
        compose.onNodeWithTag("wallet_stack_browser").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
        compose.onNodeWithTag("overview_card_deck").assertIsDisplayed()
    }

    @Test fun inlineDetailHidesTheOverlayAndReturnsToTheSameCard() {
        showOverview(wideDetail = true)
        compose.waitUntil(5000) { cardStack.prepared?.cards?.size == 2 }
        compose.onNodeWithTag("wallet_stack_overview:local").performClick()
        compose.onNodeWithTag("wallet_stack_card_1").performClick()
        compose.runOnIdle { assertEquals(cards[0].key, route); assertTrue(cardStack.expanded) }
        compose.onNodeWithTag("wallet_stack_browser").assertDoesNotExist()
        compose.runOnIdle { route = null }
        compose.onNodeWithTag("wallet_stack_card_1").assertIsDisplayed()
        compose.onNodeWithTag("wallet_stack_collapse").performClick()
        compose.onNodeWithTag("overview_card_deck").assertIsDisplayed()
    }

    @Test fun restoringTheCardsModulePreparesItsDeckAgain() {
        showOverview()
        compose.waitUntil(5000) { cardStack.prepared?.cards?.size == 2 }
        compose.onNodeWithTag("overview_toggle_CARDS").performClick()
        compose.onNodeWithTag("overview_card_deck").assertDoesNotExist()
        compose.onNodeWithTag("overview_toggle_CARDS").performClick()
        compose.waitUntil(5000) { cardStack.prepared?.cards?.size == 2 }
        compose.onNodeWithTag("wallet_stack_overview:local").performClick()
        compose.onNodeWithTag("wallet_stack_browser").assertIsDisplayed()
    }

    @Test fun databaseScopeReachesTheExistingTypeListAndLockedDatabasesStayHidden() {
        showOverview()
        compose.onNodeWithTag("overview_scope")
            .assert(hasAnyAncestor(hasTestTag("overview_top_bar"))).performClick()
        compose.onNode(isPopup()).assertExists()
        compose.onNodeWithText(context.getString(R.string.category_selection_menu_quick_filters)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.category_selection_menu_folders)).assertDoesNotExist()
        databaseChoice(context.getString(R.string.category_selection_menu_local_database)).assertIsSelected()
        File(context.getExternalFilesDir("overview-verification"), "overview-database-menu.png").outputStream().use {
            compose.onNodeWithTag("overview_database_menu").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        databaseChoice("work@example.test").performClick()
        compose.onNodeWithTag("overview_database_menu").assertDoesNotExist()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_type_PASSWORD"))
        compose.onNodeWithTag("overview_type_PASSWORD").performClick()
        compose.runOnIdle { assertEquals("type:PASSWORD:bitwarden:2", route) }
        compose.onNodeWithTag("return_home").performClick()
        compose.onNodeWithTag("overview_scope").performClick()
        databaseChoice("work@example.test").assertIsSelected()
        databaseChoice("Project MDBX").performClick()
        compose.runOnIdle { assertEquals("mdbx:4", scopeKey) }
        compose.onNodeWithTag("overview_scope").performClick()
        databaseChoice("Project MDBX").assertIsSelected()
        databaseChoice(context.getString(R.string.category_all)).performClick()
        compose.runOnIdle { assertEquals("all", scopeKey) }
        compose.onNodeWithTag("overview_scope").performClick()
        databaseChoice(context.getString(R.string.category_all)).assertIsSelected()
        databaseChoice(context.getString(R.string.category_selection_menu_local_database)).performClick()
        compose.runOnIdle { assertEquals("local", scopeKey) }
        compose.onNodeWithTag("overview_scope").performClick()
        databaseChoice("Locked").performClick()
        compose.onNodeWithTag("overview_modules").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.vault_overview_unlock)).assertIsDisplayed()
    }

    private fun databaseChoice(label: String) = compose.onNode(
        hasText(label) and hasAnyAncestor(hasTestTag("overview_database_menu")),
    )

    @Test fun theFullCategoryMenuStillShowsQuickFiltersAndFolders() {
        compose.setContent {
            MaterialTheme {
                UnifiedCategoryFilterChipMenu(
                    visible = true, onDismiss = {}, selected = UnifiedCategoryFilterSelection.Local, onSelect = {},
                    categories = listOf(Category(id = 1L, name = "Existing folder")),
                    keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases,
                    bitwardenVaults = bitwardenVaults, getBitwardenFolders = { flowOf(emptyList()) },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.category_selection_menu_databases)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.category_selection_menu_quick_filters)).assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Existing folder").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.category_selection_menu_folders)).assertIsDisplayed()
        compose.onNodeWithText("Existing folder").assertIsDisplayed()
    }

    @Test fun theSharedActionPillSwipeOpensSearchInTheCurrentDatabase() {
        showOverview()
        compose.onNodeWithTag("overview_scope").performClick()
        databaseChoice("work@example.test").performClick()
        compose.onNodeWithTag("overview_customize").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals("search:bitwarden:2", route) }
    }

    @Test fun capturesTheNativeOverview() {
        showOverview()
        compose.waitUntil(5000) { cardStack.prepared?.cards?.size == 2 }
        compose.waitForIdle()
        val file = File(context.getExternalFilesDir("overview-verification"), "native-overview.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        compose.onNodeWithTag("wallet_stack_overview:local").performClick()
        compose.onNodeWithTag("wallet_stack_browser").assertIsDisplayed()
        File(file.parentFile, "native-overview-expanded.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun passwordIconsResolveAndSeparatedRowsKeepIndependentClickTargets() {
        config = config.copy(hidden = setOf(VaultOverviewModule.CARDS.name))
        showOverview()
        fun inItems(tag: String) = compose.onNode(
            hasTestTag(tag) and hasAnyAncestor(hasTestTag("overview_items")), useUnmergedTree = true,
        )
        val automaticIcon = inItems("overview_icon_password:10")
        val customIcon = inItems("overview_icon_password:11")
        // Both entries have a Google URL. The second must honor the chosen GitHub icon.
        compose.waitUntil(10_000) {
            val bitmap = automaticIcon.captureToImage().asAndroidBitmap()
            bitmap.countPixels { color ->
                AndroidColor.red(color) > 180 && AndroidColor.green(color) < 130 && AndroidColor.blue(color) < 130
            } > bitmap.width * bitmap.height / 100
        }
        compose.waitUntil(10_000) {
            val bitmap = customIcon.captureToImage().asAndroidBitmap()
            bitmap.countPixels { color ->
                val channels = listOf(AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))
                channels.max() - channels.min() < 4 && (channels.max() < 75 || channels.min() > 245)
            } > bitmap.width * bitmap.height / 100
        }
        val first = inItems("overview_item_password:10").fetchSemanticsNode().boundsInRoot
        val second = inItems("overview_item_password:11").fetchSemanticsNode().boundsInRoot
        val gap = second.top - first.bottom
        assertTrue("Rows should have a visible narrow gap", gap >= 1f && gap <= with(compose.density) { 4.dp.toPx() })
        compose.onRoot().performTouchInput { click(Offset(first.center.x, (first.bottom + second.top) / 2f)) }
        compose.runOnIdle { assertNull("The gap must not open a neighboring item", route) }
        File(context.getExternalFilesDir("overview-verification"), "overview-grouped-items.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        inItems("overview_item_password:10").performClick()
        compose.runOnIdle { assertEquals("password:10", route) }
        compose.onNodeWithTag("return_home").performClick()
        inItems("overview_item_password:11").performClick()
        compose.runOnIdle { assertEquals("password:11", route) }
        compose.onNodeWithTag("return_home").performClick()
        compose.runOnIdle { scopeKey = "bitwarden:2" }
        inItems("overview_icon_password:12").assertIsDisplayed()
        inItems("overview_item_password:12").performClick()
        compose.runOnIdle { assertEquals("password:12", route) }
    }

    private fun Bitmap.countPixels(predicate: (Int) -> Boolean): Int {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.count(predicate)
    }
}
