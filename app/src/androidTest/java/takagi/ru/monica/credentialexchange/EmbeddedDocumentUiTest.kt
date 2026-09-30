package takagi.ru.monica.credentialexchange

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.attachments.EmbeddedWalletEditorResult
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.DocumentViewModel
import java.io.File

class EmbeddedDocumentUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: TransferFixture
    private lateinit var model: DocumentViewModel
    @Before fun setup() {
        fixture = TransferFixture()
        model = DocumentViewModel(fixture.secureItems, fixture.context, fixture.db.localKeePassDatabaseDao(), fixture.security,
            strings = AppLocaleStringResolver(fixture.context))
    }
    @After fun cleanup() { model.viewModelScope.cancel(); runBlocking { fixture.close() } }

    private fun source() = SecureItem(itemType = ItemType.DOCUMENT, title = "${fixture.prefix}-identity", notes = "Original identity notes",
        itemData = """{"documentType":"PASSPORT","documentNumber":"000123","fullName":"张伟","firstName":"Wei","lastName":"Zhang",
            "nationality":"CN","address3":"Unit 3","passportNumber":"P0002","licenseNumber":"L0003",
            "future":{"opaque":true},"customFields":[{"label":"Secret","value":"protected","type":"HIDDEN"},
            {"label":"Future","value":{"nested":1},"type":"FUTURE"}]}""")

    @Test fun completeEmbeddedEditorEditsNotesWithoutRewritingIdentityOrUnknownFields() {
        val source = source()
        val snapshot = EmbeddedWalletContent.create(source)
        var result: EmbeddedWalletEditorResult? = null
        compose.setContent { CompositionLocalProvider(takagi.ru.monica.ui.LocalUiSecurityManager provides fixture.security) {
            MaterialTheme { AddEditDocumentScreen(model, onNavigateBack = {}, embeddedDraft = snapshot, onEmbeddedSave = { result = it }) }
        } }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("embedded_document_editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source.notes).performScrollTo().performTextReplacement("Edited identity notes")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("document_editor_save").performClick()
        compose.waitUntil(10000) { result != null }
        assertEquals(snapshot.itemData, result!!.snapshot.itemData)
        assertEquals("Edited identity notes", result!!.snapshot.notes)
        assertEquals(source.notes, snapshot.notes)
        assertTrue(runBlocking { fixture.db.secureItemDao().getAllItems().first().none { it.title == source.title } })
        val file = File(fixture.context.getExternalFilesDir(null), "embedded-document-editor.png")
        file.outputStream().use { compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun failedSaveAndBackKeepOriginalSourceAndSnapshotUnchanged() {
        val source = source()
        val id = runBlocking { fixture.db.secureItemDao().insertItem(source) }
        val snapshot = EmbeddedWalletContent.create(source.copy(id = id))
        val original = snapshot.encode()
        var attempts = 0
        var dismissed = false
        compose.setContent { CompositionLocalProvider(takagi.ru.monica.ui.LocalUiSecurityManager provides fixture.security) {
            MaterialTheme { AddEditDocumentScreen(model, onNavigateBack = { dismissed = true }, embeddedDraft = snapshot,
                onEmbeddedSave = { attempts++; error("Injected preparation failure") }) }
        } }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("embedded_document_editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source.notes).performScrollTo().performTextReplacement("Unsaved change")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("document_editor_save").performClick()
        compose.waitUntil(10000) { attempts == 1 }
        compose.onNodeWithTag("embedded_document_editor").assertExists()
        compose.onNodeWithContentDescription(fixture.context.getString(R.string.back)).performClick()
        assertTrue(dismissed)
        assertEquals(original, snapshot.encode())
        assertEquals(source.notes, runBlocking { fixture.db.secureItemDao().getItemById(id) }!!.notes)
    }

    @Test fun passwordMenuCopiesDocumentAndReopensIndependentDetails() {
        val passwordModel = takagi.ru.monica.viewmodel.PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = takagi.ru.monica.repository.CustomFieldRepository(fixture.db.customFieldDao()),
            context = fixture.context, localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(),
            strings = AppLocaleStringResolver(fixture.context))
        val source = source()
        val sourceId = runBlocking { fixture.db.secureItemDao().insertItem(source) }
        var saved by mutableStateOf<Long?>(null)
        var detail by mutableStateOf(false)
        try {
            compose.setContent { CompositionLocalProvider(takagi.ru.monica.ui.LocalUiSecurityManager provides fixture.security) {
                MaterialTheme {
                    if (detail) takagi.ru.monica.ui.screens.PasswordDetailScreen(passwordModel, passwordId = saved!!,
                        biometricEnabled = false, onNavigateBack = {}, onEditPassword = {})
                    else takagi.ru.monica.ui.screens.AddEditPasswordScreen(passwordModel, passwordId = null, initialStorageExplicit = true,
                        initialDraft = takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft(
                            title = "${fixture.prefix}-password", username = "alice", password = "test-password"),
                        onSaveCompleted = { saved = it }, onNavigateBack = {})
                }
            } }
            compose.waitUntil(20000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("password_content_add"))
            compose.onNodeWithTag("password_content_editor").performTouchInput { swipeUp() }
            compose.onNodeWithTag("password_content_add").performClick()
            compose.onNodeWithTag("password_content_choose_DOCUMENT").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("embedded_document_editor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(fixture.context.getString(R.string.embedded_copy_document)).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText(source.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(source.title).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("张伟").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("document_editor_save").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("embedded_document_editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(20000) { saved != null }
            val field = runBlocking { passwordModel.getCustomFieldsByEntryIdSync(saved!!).single {
                it.title == EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.DOCUMENT) } }
            val copy = (EmbeddedWalletContent.read(field.value) as EmbeddedWalletContent.ReadResult.Available).snapshot
            assertEquals(EmbeddedWalletContent.create(source).itemData, copy.itemData)
            runBlocking { fixture.db.secureItemDao().deleteItemById(sourceId) }
            compose.runOnIdle { detail = true }
            compose.waitUntil(20000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("password_document_face"))
            compose.onNodeWithTag("password_document_face").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("embedded_wallet_detail").fetchSemanticsNodes().isNotEmpty() }
            val file = File(fixture.context.getExternalFilesDir(null), "embedded-document-password-detail.png")
            file.outputStream().use { compose.onNodeWithTag("embedded_wallet_detail").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { passwordModel.viewModelScope.cancel() }
    }
}
