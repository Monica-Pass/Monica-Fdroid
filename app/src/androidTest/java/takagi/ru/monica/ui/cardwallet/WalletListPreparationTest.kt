package takagi.ru.monica.ui.cardwallet

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.screens.CardWalletTab
import takagi.ru.monica.viewmodel.LoadedListState
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WalletListPreparationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingEmptyResultsAndFilterChangesUseTheCorrectSnapshot() {
        val row = WalletListItem(1, WalletListItemType.BANK_CARD,
            SecureItem(id = 1, itemType = ItemType.BANK_CARD, title = "Visa", itemData = "{}"))
        var source by mutableStateOf(LoadedListState<WalletListItem>())
        var query by mutableStateOf("")
        val rendered = AtomicReference("")
        compose.setContent {
            val state by rememberFilteredWallet(source, CardWalletTab.ALL, query, UnifiedCategoryFilterSelection.All)
            val text = if (!state.isReady) "Loading" else state.items.joinToString { it.item.title }.ifEmpty { "Empty" }
            androidx.compose.runtime.SideEffect { rendered.set(text) }
            Text(text)
        }
        compose.onNodeWithText("Loading").assertIsDisplayed()
        compose.runOnIdle { source = LoadedListState(listOf(row), true) }
        compose.waitUntil(10000) { rendered.get() == "Visa" }
        compose.runOnIdle { query = "missing" }
        compose.waitUntil(10000) { rendered.get() == "Empty" }
        compose.runOnIdle { query = "Visa" }
        compose.waitUntil(10000) { rendered.get() == "Visa" }
        compose.runOnIdle { source = LoadedListState(emptyList(), true) }
        compose.waitUntil(10000) { rendered.get() == "Empty" }
    }
}
