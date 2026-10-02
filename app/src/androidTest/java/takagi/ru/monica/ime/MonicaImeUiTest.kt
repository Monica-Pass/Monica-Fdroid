package takagi.ru.monica.ime

import android.content.res.Configuration
import android.graphics.Bitmap
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.data.ImeCustomFieldRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.awaitCancellation

@RunWith(AndroidJUnit4::class)
class MonicaImeUiTest {
    @get:Rule val compose = createComposeRule()

    private val panels = listOf(
        MonicaImePanel.PASSWORDS, MonicaImePanel.AUTHENTICATORS, MonicaImePanel.DOCUMENTS
    )
    private var state by mutableStateOf(sampleState())
    private lateinit var editor: EditText
    private var density = 1f
    private var contentLocale = Locale.SIMPLIFIED_CHINESE
    private var autofillOpenCount = 0
    private var undoCount = 0
    private var walletQuickFillCount = 0
    private var observeFields: (Long) -> Flow<List<ImeCustomFieldRow>> = { flowOf(emptyList()) }
    private var fillField: (Long, Long) -> Unit = { _, _ -> }

    private fun showKeyboard(
        width: Dp = 360.dp,
        fontScale: Float = 1f,
        dark: Boolean = false,
        language: Locale = Locale.SIMPLIFIED_CHINESE
    ) {
        contentLocale = language
        compose.setContent {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocale(language)
                this.fontScale = fontScale
            }
            val localizedContext = context.createConfigurationContext(configuration)
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale)
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    AndroidView(
                        factory = { viewContext ->
                            EditText(viewContext).apply {
                                editor = this
                                inputType = InputType.TYPE_CLASS_TEXT
                                showSoftInputOnFocus = false
                                setSingleLine()
                                requestFocus()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    Box(Modifier.width(width).testTag("ime_test_keyboard")) {
                        MonicaImeContent(
                            settings = AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                            uiState = state,
                            onDatabaseScopeSelected = { state = state.copy(selectedDatabaseScope = it) },
                            onInsertPassword = { commit(it.password) },
                            onInsertUsername = { commit(it.username) },
                            onInsertWebsite = { commit(it.website) },
                            onSmartFillPassword = {},
                            onInsertAuthenticatorCode = { commit(it.code) },
                            onInsertCardWalletValue = { _, field -> commit(field.value) },
                            onSmartFillCardWallet = { walletQuickFillCount++ },
                            onKeyPressed = { value ->
                                if (state.isSearchEditing) {
                                    state = state.copy(query = appendImeSearchQuery(state.query, value))
                                } else commit(value)
                            },
                            onBackspace = { state = state.copy(query = removeLastImeSearchCharacter(state.query)) },
                            onDeleteAll = {}, onUndoDeleteAll = { undoCount++ },
                            onEnter = { state = state.copy(isSearchEditing = false) },
                            onSpace = {},
                            onShiftToggle = { state = state.copy(isUppercase = !state.isUppercase) },
                            onKeyboardModeChange = { state = state.copy(keyboardMode = it) },
                            onOpenUnlockApp = {}, onOpenAutofillSettings = { autofillOpenCount++ },
                            onSearchEditRequested = { state = state.startVaultSearch() },
                            onSearchEditFinished = { state = state.copy(isSearchEditing = false) },
                            onSearchCleared = { state = state.copy(query = "") },
                            onPanelSelected = { state = state.selectVaultPanel(it, isLoading = false) },
                            onSwitchInputMethod = {}, onDismiss = {},
                            observeCustomFields = { observeFields(it) },
                            onInsertCustomField = { entry, id -> fillField(entry.id, id) }
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("ime_vault_pane").assertIsDisplayed()
    }

    private fun commit(value: String) {
        checkNotNull(editor.onCreateInputConnection(EditorInfo())).commitText(value, 1)
    }

    private fun text(id: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration).apply { setLocale(contentLocale) }
        return context.createConfigurationContext(config).getString(id)
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertControlsAlignWithList() {
        val filter = bounds("ime_vault_database_filter")
        val search = bounds("ime_vault_search")
        val rail = bounds("ime_vault_scroll_rail")
        val card = bounds("ime_vault_entry_1")
        assertTrue("Search must stop before the reserved rail area", search.right <= rail.left - 8f * density + 1f)
        assertTrue("Cards need a gap before the rail", card.right <= rail.left - 8f * density + 1f)
        assertEquals(40f * density, rail.width, 1f)
        assertEquals("The filter starts at the card edge", card.left, filter.left, 1f)
        assertEquals("Search ends at the card edge", card.right, search.right, 1f)
        assertEquals("Filter and search share the row evenly", filter.width, search.width, 1f)
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("ime_test_keyboard").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "ime-ui-tests").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun toolbarPressFeedbackStaysCircularInDarkMode() {
        showKeyboard(dark = true)
        assertCircularToolbarPresses("dark")
    }

    @Test fun toolbarPressFeedbackStaysCircularOnNarrowLightKeyboard() {
        showKeyboard(width = 320.dp, fontScale = 1.5f)
        assertCircularToolbarPresses("light-narrow")
    }

    @Test fun autofillShortcutKeepsItsFullTouchTargetAndTemporaryUndoAction() {
        showKeyboard()
        val shortcut = compose.onNodeWithTag("ime_toolbar_more")
        shortcut.assertHasClickAction()
        // This corner is outside the visible 40dp circle but inside the existing touch target.
        shortcut.performTouchInput { down(Offset(1f, 1f)); up() }
        compose.runOnIdle {
            assertEquals(1, autofillOpenCount)
            assertEquals(0, undoCount)
            state = state.copy(pendingClearedInput = "Synthetic cleared input")
        }
        compose.onNodeWithContentDescription(text(R.string.ime_clear_all_undo_action)).performClick()
        compose.runOnIdle {
            assertEquals(1, autofillOpenCount)
            assertEquals(1, undoCount)
            state = state.copy(pendingClearedInput = null)
        }
        shortcut.performClick()
        compose.runOnIdle { assertEquals(2, autofillOpenCount) }
    }

    private fun assertCircularToolbarPresses(mode: String) {
        listOf("keyboard", "passwords", "authenticators", "documents", "generator", "more", "hide").forEach { name ->
            val button = compose.onNodeWithTag("ime_toolbar_$name")
            val before = button.captureToImage().asAndroidBitmap()
            button.performTouchInput { down(center) }
            try {
                compose.mainClock.advanceTimeBy(400)
                // Material ripples use Android's rendering clock, not Compose's test clock.
                android.os.SystemClock.sleep(400)
                val pressed = button.captureToImage().asAndroidBitmap()
                if (name == "passwords" || name == "more") capture("toolbar-$mode-$name-pressed")
                var outsideChanged = 0
                var insideChanged = 0
                val radius = 20f * density
                for (y in 0 until before.height) for (x in 0 until before.width) {
                    val a = before.getPixel(x, y)
                    val b = pressed.getPixel(x, y)
                    val delta = listOf(0, 8, 16).maxOf { shift ->
                        kotlin.math.abs(((a shr shift) and 255) - ((b shr shift) and 255))
                    }
                    if (delta <= 3) continue
                    val dx = x + 0.5f - before.width / 2f
                    val dy = y + 0.5f - before.height / 2f
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (distance > radius + density * 1.5f) outsideChanged++
                    if (distance < radius - density * 1.5f) insideChanged++
                }
                assertEquals("$mode/$name: rectangular feedback outside the circle", 0, outsideChanged)
                assertTrue("$mode/$name: retain visible feedback inside the circle", insideChanged > 10)
            } finally {
                button.performTouchInput { cancel() }
                compose.mainClock.advanceTimeBy(400)
                android.os.SystemClock.sleep(400)
            }
        }
        compose.runOnIdle {
            assertEquals(MonicaImePanel.PASSWORDS, state.activePanel)
            assertEquals(0, autofillOpenCount)
        }
    }

    @Test fun threeListsShareGeometryAndLeaveSpaceForFastScrolling() {
        showKeyboard()
        val expectedCard = bounds("ime_vault_entry_1")
        val expectedSearch = bounds("ime_vault_search")
        val expectedFilter = bounds("ime_vault_database_filter")
        panels.forEach { panel ->
            compose.runOnIdle { state = state.selectVaultPanel(panel, isLoading = false) }
            capture(panel.name.lowercase())
            assertControlsAlignWithList()
            val card = bounds("ime_vault_entry_1")
            assertEquals(expectedCard.width, card.width, 1f)
            assertEquals(expectedCard.height, card.height, 1f)
            assertEquals(expectedCard.top, card.top, 1f)
            assertEquals(expectedSearch, bounds("ime_vault_search"))
            assertEquals(expectedFilter, bounds("ime_vault_database_filter"))
        }
    }

    @Test fun everySearchButtonOpensTheKeyboardAndUsesItsOwnResultCount() {
        showKeyboard()
        panels.forEach { panel ->
            compose.runOnIdle {
                state = state.selectVaultPanel(panel, isLoading = false).copy(query = "")
                editor.setText("untouched")
            }
            compose.onNodeWithTag("ime_vault_search").assertHasClickAction().performClick()
            compose.onNodeWithTag("ime_search_toolbar").assertIsDisplayed()
            compose.onNodeWithTag("ime_search_result_count").assertTextEquals(state.activeEntryCount.toString())
            compose.onNodeWithTag("ime_key_letter_q").performClick()
            compose.runOnIdle {
                assertEquals("q", state.query)
                assertEquals("untouched", editor.text.toString())
                assertEquals(panel, state.activePanel)
            }
            compose.onNodeWithContentDescription(text(R.string.confirm)).performClick()
            compose.onNodeWithTag("ime_vault_search").assertIsDisplayed()
        }
    }

    @Test fun databaseSelectionWorksInsideEveryPanel() {
        showKeyboard()
        panels.forEach { panel ->
            compose.runOnIdle {
                state = state.selectVaultPanel(panel, isLoading = false)
                    .copy(selectedDatabaseScope = MonicaImeDatabaseScope.All)
            }
            compose.onNodeWithTag("ime_vault_database_filter").performClick()
            val option = compose.onNodeWithText("Demo KeePass")
            option.assertIsDisplayed()
            val optionBounds = option.fetchSemanticsNode().boundsInRoot
            val pane = bounds("ime_vault_pane")
            assertTrue(optionBounds.top >= pane.top && optionBounds.bottom <= pane.bottom)
            option.performClick()
            compose.runOnIdle { assertEquals(MonicaImeDatabaseScope.KeePass(7), state.selectedDatabaseScope) }
        }
    }

    @Test fun websiteActionFillsTheSavedAddressAndKeepsTheEntryExpanded() {
        showKeyboard()
        compose.onNodeWithText("Aster Mail").performClick()
        val initialHeight = bounds("ime_vault_entry_1").height
        compose.onNodeWithText(text(R.string.website)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(state.entries.first().website, editor.text.toString()) }
        compose.onNodeWithText(text(R.string.website)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.username)).assertHasClickAction()
        compose.onNodeWithText(text(R.string.password)).assertHasClickAction()
        capture("website-expanded")
        assertEquals(initialHeight, bounds("ime_vault_entry_1").height, 1f)
        capture("website-expanded")
    }

    @Test fun embeddedAddressActionsStayOnOneRowAtLargeFontAndFillChosenField() {
        state = state.copy(activePanel = MonicaImePanel.DOCUMENTS, cardWalletEntries = listOf(
            MonicaImeCardWalletEntry(-41, "Shopping · Office", "Shanghai", "账单地址", false, "Monica",
                listOf("姓名", "街道地址", "城市", "省/州", "邮编", "国家", "公司", "电话", "邮箱").map {
                    MonicaImeCardWalletField(it, if (it == "邮箱") "test@example.invalid" else "fixture")
                }, supportsQuickFill = false)))
        showKeyboard(width = 320.dp, fontScale = 1.5f, dark = true)
        compose.onNodeWithText("Shopping · Office").performClick()
        compose.onNodeWithText(text(R.string.ime_quick_fill)).assertDoesNotExist()
        val height = bounds("ime_vault_entry_-41").height
        val first = compose.onNodeWithText("姓名").fetchSemanticsNode().boundsInRoot.top
        capture("wallet-address-dark-large")
        compose.onNodeWithText("邮箱").performScrollTo().assertIsDisplayed()
        assertEquals(first, compose.onNodeWithText("邮箱").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(height, bounds("ime_vault_entry_-41").height, 1f)
        capture("wallet-address-scrolled")
        compose.onNodeWithText("邮箱").performClick()
        compose.runOnIdle { assertEquals("test@example.invalid", editor.text.toString()) }
        compose.onNodeWithTag("ime_wallet_actions_-41").assertIsDisplayed()
        assertEquals(height, bounds("ime_vault_entry_-41").height, 1f)
        compose.runOnIdle { editor.setText("") }
        compose.onNodeWithText("城市").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("fixture", editor.text.toString()) }
        compose.onNodeWithTag("ime_wallet_actions_-41").assertIsDisplayed()
        capture("wallet-after-consecutive-fills")
        compose.onNodeWithText("Shopping · Office").performClick()
        compose.onNodeWithTag("ime_wallet_actions_-41").assertDoesNotExist()
        compose.onNodeWithText("Shopping · Office").performClick()
        compose.onNodeWithTag("ime_wallet_actions_-41").assertIsDisplayed()
    }

    @Test fun cardQuickFillKeepsActionsOpenUntilManuallyCollapsed() {
        state = state.copy(activePanel = MonicaImePanel.DOCUMENTS,
            cardWalletEntries = listOf(state.cardWalletEntries.first()))
        showKeyboard()
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithText(text(R.string.ime_quick_fill)).performClick()
        compose.runOnIdle { assertEquals(1, walletQuickFillCount) }
        compose.onNodeWithTag("ime_wallet_actions_1").assertIsDisplayed()
        compose.onNodeWithText("Card number").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("4242424242424242", editor.text.toString()) }
        compose.onNodeWithTag("ime_wallet_actions_1").assertIsDisplayed()
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithTag("ime_wallet_actions_1").assertDoesNotExist()
    }

    @Test fun blankWebsitesDoNotExposeAnEmptyFillAction() {
        state = state.copy(entries = listOf(state.entries.first().copy(website = " \n ")))
        showKeyboard()
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithText(text(R.string.website)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.username)).assertIsDisplayed()
    }

    @Test fun narrowScreenScrollsWebsiteActionWithoutGrowingOrCrowdingTheRail() {
        showKeyboard(width = 320.dp, fontScale = 1.3f)
        compose.onNodeWithText("Aster Mail").performClick()
        val initialHeight = bounds("ime_vault_entry_1").height
        val quickFill = compose.onNodeWithText(text(R.string.ime_quick_fill)).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(text(R.string.website)).performScrollTo()
        capture("narrow-website")
        compose.onNodeWithText(text(R.string.website)).assertIsDisplayed()
        assertControlsAlignWithList()
        val website = compose.onNodeWithText(text(R.string.website)).fetchSemanticsNode().boundsInRoot
        val rail = bounds("ime_vault_scroll_rail")
        assertEquals("Actions remain on one horizontally scrolling row", quickFill.top, website.top, 1f)
        assertEquals(initialHeight, bounds("ime_vault_entry_1").height, 1f)
        assertTrue(website.right < rail.left)
    }

    @Test fun manyCustomFieldsStayCompactAndOnlyTheExpandedEntryIsObserved() {
        state = state.copy(entries = state.entries.map { it.copy(hasCustomFields = true) })
        val active = mutableSetOf<Long>()
        observeFields = { id -> flow {
            active.add(id)
            try {
                emit(List(80) { ImeCustomFieldRow(it + 1L, if (it == 0) "密保答案" else "Field $it", it % 2 == 0) })
                awaitCancellation()
            } finally { active.remove(id) }
        } }
        fillField = { _, id -> commit("synthetic-$id") }
        showKeyboard(width = 320.dp, fontScale = 1.5f, dark = true)
        compose.runOnIdle { assertTrue(active.isEmpty()) }
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithTag("ime_custom_field_1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(1L), active) }
        val height = bounds("ime_vault_entry_1").height
        val pane = bounds("ime_vault_pane")
        capture("custom-fields-dark-large")
        compose.onNodeWithTag("ime_custom_fields_1").performScrollToIndex(79)
        compose.onNodeWithTag("ime_custom_field_80").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("synthetic-80", editor.text.toString()) }
        assertEquals(height, bounds("ime_vault_entry_1").height, 1f)
        assertEquals(pane.height, bounds("ime_vault_pane").height, 1f)
        capture("custom-fields-scrolled")
        compose.onNodeWithTag("ime_vault_list").performScrollToIndex(1)
        compose.onNodeWithText("Birch Account").performClick()
        compose.runOnIdle { assertEquals(setOf(2L), active) }
        compose.onNodeWithTag("ime_custom_fields_1").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(unlocked = false) }
        compose.onNodeWithTag("ime_custom_fields_2").assertDoesNotExist()
        compose.runOnIdle { assertTrue(active.isEmpty()) }
    }

    @Test fun customOnlyEntryCanRetryLoadingAndFillWithoutExposingQuickFill() {
        state = state.copy(entries = listOf(state.entries.first().copy(username = "", password = "", website = "", hasCustomFields = true)))
        var attempts = 0
        observeFields = { flow {
            attempts++
            if (attempts == 1) error("Synthetic failure")
            emit(listOf(ImeCustomFieldRow(41, "Group number", false)))
        } }
        fillField = { _, _ -> commit("fixture-group") }
        showKeyboard()
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithText(text(R.string.retry)).performClick()
        compose.onNodeWithText(text(R.string.ime_quick_fill)).assertDoesNotExist()
        compose.onNodeWithTag("ime_custom_field_41").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, attempts); assertEquals("fixture-group", editor.text.toString()) }
        capture("custom-only-light")
    }

    @Test fun longEnglishLabelsAndLargeTextStayWithinTheContentArea() {
        state = state.copy(
            query = "a very long search query for a saved account",
            databaseOptions = state.databaseOptions.mapIndexed { index, option ->
                if (index == 0) option.copy(label = "All databases with a very long name") else option
            }
        )
        showKeyboard(width = 320.dp, fontScale = 1.5f, dark = true, language = Locale.US)
        assertControlsAlignWithList()
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithText(text(R.string.website)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(state.entries.first().website, editor.text.toString()) }
        capture("english-large-text-dark")
    }

    @Test fun theSharedScrollRailNavigatesEachList() {
        val original = state
        state = state.copy(
            entries = ('A'..'Z').mapIndexed { i, letter -> original.entries.first().copy(id = i + 1L, title = "$letter Demo") },
            authenticatorEntries = ('A'..'Z').mapIndexed { i, letter -> original.authenticatorEntries.first().copy(id = i + 1L, title = "$letter Demo") },
            cardWalletEntries = ('A'..'Z').mapIndexed { i, letter -> original.cardWalletEntries.first().copy(id = i + 1L, title = "$letter Demo") }
        )
        showKeyboard()
        panels.forEach { panel ->
            compose.runOnIdle { state = state.selectVaultPanel(panel, isLoading = false) }
            compose.onNodeWithTag("ime_vault_scroll_rail").performTouchInput {
                swipe(Offset(center.x, height * 0.1f), Offset(center.x, height * 0.98f), 500)
            }
            compose.onNodeWithText("Z Demo").assertIsDisplayed()
        }
    }

    private fun sampleState(): MonicaImeUiState {
        val titles = listOf("Aster Mail", "Birch Account", "Cedar Notes", "Dune Vault", "Elm Card")
        return MonicaImeUiState(
            unlocked = true, activePanel = MonicaImePanel.PASSWORDS, isAutofillPanelVisible = true,
            entries = titles.take(3).mapIndexed { index, title ->
                MonicaImePasswordEntry(
                    id = index + 1L, title = title, username = "demo@example.com",
                    website = "https://example.com/账户?q=a%20b#details", packageName = "",
                    password = "Demo-only-value", isFavorite = false, sourceLabel = "Local"
                )
            },
            authenticatorEntries = titles.take(4).mapIndexed { index, title ->
                MonicaImeAuthenticatorEntry(index + 1L, title, "Demo", "demo@example.com", "123456", 24, false, "Local")
            },
            cardWalletEntries = titles.mapIndexed { index, title ->
                MonicaImeCardWalletEntry(
                    index + 1L, title, "•••• 4242", "Visa", false, "Local",
                    listOf(MonicaImeCardWalletField("Card number", "4242424242424242"))
                )
            },
            databaseOptions = listOf(
                MonicaImeDatabaseOption(MonicaImeDatabaseScope.All, "全部数据库"),
                MonicaImeDatabaseOption(MonicaImeDatabaseScope.Local, "Monica"),
                MonicaImeDatabaseOption(MonicaImeDatabaseScope.KeePass(7), "Demo KeePass")
            )
        )
    }
}
