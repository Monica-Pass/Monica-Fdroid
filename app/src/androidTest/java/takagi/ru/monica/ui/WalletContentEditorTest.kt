package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.suggestions.CommonInfoTestActivity
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel

class WalletContentEditorTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val security by lazy { SecurityManager(context) }
    private lateinit var db: PasswordDatabase
    private lateinit var bank: BankCardViewModel
    private lateinit var document: DocumentViewModel
    private var wasUnlocked = false

    @Before fun prepare() {
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val repo = SecureItemRepository(db.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
        bank = BankCardViewModel(repo, strings = AppLocaleStringResolver(context))
        document = DocumentViewModel(repo, strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() {
        show { }
        bank.viewModelScope.cancel(); document.viewModelScope.cancel(); db.close()
        if (!wasUnlocked) SessionManager.markLocked()
    }
    private fun show(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CommonInfoTestActivity.content = {
                CompositionLocalProvider(LocalUiSecurityManager provides security) { MaterialTheme { content() } }
            }
        }
        compose.waitForIdle()
    }
    private fun scroll(root: String, tag: String) {
        compose.onNodeWithTag(root).performScrollToNode(hasTestTag(tag))
    }
    private fun add(root: String, key: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(5000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        scroll(root, "item_editor_add_content")
        compose.onNodeWithTag("item_editor_add_content").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("item_editor_choose_$key").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("item_editor_choose_$key").performScrollTo().performClick()
        compose.onNodeWithTag("wallet_detail_${if (key.startsWith("custom_")) "custom" else key}").assertIsDisplayed()
    }
    private fun closeEditor() { compose.onNodeWithTag("wallet_detail_done").performClick() }
    private fun waitForEditorKeyboard() {
        compose.waitUntil(10000) {
            android.view.inspector.WindowInspector.getGlobalWindowViews().any { view ->
                view.hasWindowFocus() && androidx.core.view.ViewCompat.getRootWindowInsets(view)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File(context.filesDir, "wallet-content-screens/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun newBankCardCanDragContentsAndReopenWithOrderAndSecretsPreserved() = runBlocking {
        var saved by mutableStateOf(false)
        show { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = { saved = true }) }
        scroll("bank_item_editor", "entry_payment_number")
        compose.onNodeWithTag("entry_payment_number").performTextInput("4111111111111111")
        add("bank_item_editor", "notes")
        compose.onNode(hasText(context.getString(R.string.notes)) and hasSetTextAction()).performTextInput("Synthetic note")
        closeEditor()
        add("bank_item_editor", "extended")
        compose.onNodeWithTag("entry_extra_add").performClick()
        compose.onNodeWithTag("entry_extra_choose_pin").performScrollTo().performClick()
        compose.onNodeWithTag("entry_extra_pin").performTextInput("0123")
        closeEditor()
        add("bank_item_editor", "custom_hidden")
        compose.onNodeWithTag("wallet_custom_name_0").performTextInput("Private field")
        compose.onNodeWithTag("wallet_custom_value_0").performTextInput("Synthetic secret")
        closeEditor()
        compose.onNodeWithTag("bank_item_editor").performScrollToIndex(1)
        val first = compose.onNodeWithTag("wallet_content_extended").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("wallet_content_notes").fetchSemanticsNode().boundsInRoot
        val other = compose.onNodeWithTag("wallet_content_custom").fetchSemanticsNode().boundsInRoot
        assertEquals(first.height, last.height, 1f)
        assertEquals(first.width, other.width, 1f)
        compose.onNodeWithTag("wallet_content_notes").performTouchInput {
            down(center); advanceEventTime(650)
            moveTo(center + Offset(0f, first.center.y - last.center.y), delayMillis = 550)
            up()
        }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("wallet_content_notes").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("wallet_content_extended").fetchSemanticsNode().boundsInRoot.top)
        capture("bank-reordered")
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.waitUntil(15000) { saved }
        val item = db.secureItemDao().getAllItems().first().single()
        val data = requireNotNull(bank.parseCardData(item.itemData))
        assertEquals("notes", data.editorSectionOrder.first())
        assertEquals("0123", data.pin)
        assertEquals("Synthetic secret", data.customFields.single().value)
        assertEquals(SecureCustomFieldType.HIDDEN, data.customFields.single().type)
        assertEquals("Synthetic note", item.notes)
        show { }
        show { AddEditBankCardScreen(bank, cardId = item.id, onNavigateBack = {}) }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("bank_item_editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bank_item_editor").performScrollToIndex(1)
        compose.onNodeWithTag("wallet_content_notes").assertIsDisplayed()
        compose.onNodeWithTag("wallet_content_extended").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("wallet_content_notes").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("wallet_content_extended").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun documentMoveMenuAndRecreationKeepExistingIdentityAndSaveOrder() = runBlocking {
        val original = DocumentData(DocumentType.PASSPORT, "SYNTHETIC-001", "Synthetic Name", firstName = "Synthetic",
            address1 = "Example Street", ssn = "synthetic-sensitive")
        val id = db.secureItemDao().insertItem(SecureItem(itemType = ItemType.DOCUMENT, title = "Synthetic Document",
            notes = "Existing note", itemData = CardWalletDataCodec.encodeDocumentData(original)))
        var saved by mutableStateOf(false)
        show { AddEditDocumentScreen(document, documentId = id, onNavigateBack = { saved = true }) }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("document_item_editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("document_item_editor").performScrollToIndex(1)
        compose.onNodeWithTag("wallet_actions_identity").performClick()
        compose.onNodeWithText(context.getString(R.string.move_down)).performClick()
        compose.activityRule.scenario.recreate()
        scroll("document_item_editor", "wallet_content_address")
        assertTrue(compose.onNodeWithTag("wallet_content_address").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("wallet_content_identity").fetchSemanticsNode().boundsInRoot.top)
        capture("document-reordered")
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.waitUntil(15000) { saved }
        val item = db.secureItemDao().getAllItems().first().single()
        val data = requireNotNull(document.parseDocumentData(item.itemData))
        assertEquals(original, data.copy(editorSectionOrder = emptyList()))
        assertEquals("address", data.editorSectionOrder.first())
        assertEquals("Existing note", item.notes)
    }

    @Test fun smallDarkLargeTextEditorUsesConnectedFieldsAndKeepsEmptyAddedFields() {
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { AddEditBankCardScreen(bank, initialStorageExplicit = true, onNavigateBack = {}) }
                }
            }
        }
        add("bank_item_editor", "extended")
        for (key in listOf("validFromMonth", "validFromYear", "pin", "branchCode")) {
            compose.onNodeWithTag("entry_extra_add").performScrollTo().performClick()
            compose.onNodeWithTag("entry_extra_choose_$key").performScrollTo().performClick()
        }
        closeEditor()
        scroll("bank_item_editor", "wallet_content_extended")
        compose.onNodeWithTag("wallet_content_extended").performClick()
        val month = compose.onNodeWithTag("entry_extra_validFromMonth").fetchSemanticsNode().boundsInRoot
        val year = compose.onNodeWithTag("entry_extra_validFromYear").fetchSemanticsNode().boundsInRoot
        val pin = compose.onNodeWithTag("entry_extra_pin").fetchSemanticsNode().boundsInRoot
        assertEquals(month.width, pin.width, 1f)
        assertEquals(month.height, year.height, 1f)
        assertTrue("Connected fields need a small gap", year.top - month.bottom in 0f..8f)
        compose.onNodeWithTag("entry_extra_branchCode").performScrollTo().performClick().performTextInput("00123")
        waitForEditorKeyboard()
        compose.onNodeWithTag("entry_extra_branchCode").assertIsDisplayed()
        compose.onNodeWithTag("wallet_detail_done").assertIsDisplayed()
        capture("extended-dark-large-keyboard")
        closeEditor()
        add("bank_item_editor", "custom_hidden")
        compose.onNodeWithTag("wallet_custom_name_0").performTextInput("Synthetic private field")
        compose.onNodeWithTag("wallet_custom_value_0").performScrollTo().performClick().performTextInput("secret")
        waitForEditorKeyboard()
        compose.onNodeWithTag("wallet_custom_value_0").assertIsDisplayed()
        compose.onNodeWithTag("wallet_detail_done").assertIsDisplayed()
        capture("custom-dark-large-keyboard")
        closeEditor()
    }
}
