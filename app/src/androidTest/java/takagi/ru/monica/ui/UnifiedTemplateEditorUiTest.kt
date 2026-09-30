package takagi.ru.monica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

class UnifiedTemplateEditorUiTest {
    @Test fun wifiCreateAndDetailKeepNetworkNameAndExposeActions() {
        var detail by mutableStateOf(false)
        compose.setContent { key(detail) { CompositionLocalProvider(LocalUiSecurityManager provides security) { MaterialTheme {
            if (detail) takagi.ru.monica.ui.screens.WifiDetailScreen(model, requireNotNull(savedId), {}, {})
            else AddEditPasswordScreen(model, passwordId = null, initialLoginType = "WIFI", initialStorageExplicit = true,
                onSaveCompleted = { savedId = it }, onNavigateBack = {})
        } } } }
        compose.onNodeWithTag("password_editor_title").performTextReplacement("Fixture;网络")
        compose.onNodeWithTag("template_field_password").performScrollTo().performTextReplacement(" synthetic-wifi ")
        closeFocusedKeyboard()
        capture("wifi-editor")
        compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
        compose.waitUntil(20000) { savedId != null }
        val saved = runBlocking { model.getPasswordEntryById(savedId!!)!! }
        assertEquals("Fixture;网络", WifiEntryDetails.from(saved).ssid)
        assertEquals(" synthetic-wifi ", saved.password)
        compose.runOnIdle { detail = true }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("wifi_detail_ssid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Fixture;网络") and hasAnyAncestor(hasTestTag("wifi_detail_ssid")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("wifi_detail_connect").assertIsEnabled()
        capture("wifi-detail")
        compose.onNodeWithTag("wifi_detail_qr").assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.wifi_qr_dialog_title, "Fixture;网络")).assertExists()
        capture("wifi-detail-qr")
        androidx.test.espresso.Espresso.pressBack()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shellOutput(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            compose.onNodeWithTag("wifi_detail_connect").performClick()
            // Accessibility roots can be stale when instrumentation runs in a secondary user.
            // Assert the actual resumed system Wi-Fi activity, not merely an emitted intent.
            compose.waitUntil(15000) {
                shellOutput("dumpsys activity activities").lineSequence().any {
                    ("mResumedActivity" in it || "topResumedActivity" in it) &&
                        "com.android.settings/" in it && "WifiSettingsActivity" in it
                }
            }
            capture("wifi-system-settings")
        } finally {
            shellOutput("input keyevent KEYCODE_BACK")
        }
    }
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    private var editId by mutableStateOf<Long?>(null)
    private var savedId: Long? = null

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }
    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose idleness does not include Android's window enter/exit animation.
        android.os.SystemClock.sleep(400)
        val folder = java.io.File(context.filesDir, "unified-template-verification").apply { mkdirs() }
        val file = java.io.File(folder, "$name.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun apiKeyUsesSharedContentAndPreservesExactSecretWhenReopened() {
        compose.setContent { key(editId) { CompositionLocalProvider(LocalUiSecurityManager provides security) { MaterialTheme {
            AddEditPasswordScreen(model, passwordId = editId, initialLoginType = "API_KEY", initialStorageExplicit = true,
                initialDraft = AddEditPasswordInitialDraft(title = "API fixture"),
                onSaveCompleted = { savedId = it }, onNavigateBack = {})
        } } } }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("template_field_key").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("template_field_key").performScrollTo().performTextReplacement("  fixture-key  ")
        compose.onNodeWithTag("template_field_url").performScrollTo().performTextReplacement("https://example.org/api")
        closeFocusedKeyboard()
        capture("api-key-editor")
        val list = compose.onNode(hasTestTag("password_content_editor") or hasTestTag("password_classic_editor"))
        list.performScrollToNode(hasTestTag("password_content_add"))
        compose.bringAboveFloatingActions(compose.onNodeWithTag("password_content_add"), list)
        compose.onNodeWithTag("password_content_add").performClick()
        compose.onNodeWithTag("password_content_choose_ADDRESS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("password_content_choose_CONTACT").assertDoesNotExist()
        pressFocusedBack()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(20000) { savedId != null }
        val original = runBlocking { model.getPasswordEntryById(requireNotNull(savedId))!! }
        assertEquals("API_KEY", original.loginType)
        assertEquals("  fixture-key  ", original.password)
        compose.runOnIdle { editId = savedId; savedId = null }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("template_field_key").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("template_field_key").assertTextContains("  fixture-key  ")
        compose.onNodeWithTag("template_field_url").assertTextContains("https://example.org/api")
    }

    @Test fun apiKeyPlainAddressesAndOtpSaveThenEditWithoutDisablingSave() {
        compose.setContent { key(editId) { CompositionLocalProvider(LocalUiSecurityManager provides security) { MaterialTheme {
            AddEditPasswordScreen(model, passwordId = editId, initialLoginType = "API_KEY", initialStorageExplicit = true,
                initialDraft = if (editId == null) AddEditPasswordInitialDraft(title = "API plain address fixture") else null,
                onSaveCompleted = { savedId = it }, onNavigateBack = {})
        } } } }
        compose.onNodeWithTag("template_website").performScrollTo().performTextReplacement("hxhxjdjff")
        compose.onNodeWithTag("template_field_key").performScrollTo().performTextReplacement("  合成密钥-test  ")
        compose.onNodeWithTag("template_field_url").performScrollTo().performTextReplacement("hdhxnd")
        compose.onNodeWithTag("password_otp_secret").performScrollTo().performTextReplacement("JBSWY3DPEHPK3PXP")
        closeFocusedKeyboard()
        compose.onNodeWithTag("password_editor_save").assertIsEnabled()
        capture("api-plain-address-enabled")
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(20000) { savedId != null }
        val original = runBlocking { model.getPasswordEntryById(requireNotNull(savedId))!! }
        assertEquals("API_KEY", original.loginType)
        assertEquals("  合成密钥-test  ", original.password)
        assertEquals("hxhxjdjff", original.website)
        assertNotEquals("JBSWY3DPEHPK3PXP", original.authenticatorKey)
        assertEquals("JBSWY3DPEHPK3PXP", security.decryptDataIfMonicaCiphertext(original.authenticatorKey))
        assertEquals("hdhxnd", runBlocking { model.getCustomFieldsByEntryIdSync(savedId!!).single { it.title == ApiKeyEntryFields.API_URL }.value })
        compose.runOnIdle { editId = savedId; savedId = null }
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("template_field_key") and hasText("  合成密钥-test  ")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("template_field_url").performScrollTo().assertTextContains("hdhxnd")
        compose.onNodeWithTag("template_field_url").performTextReplacement("localhost:11434/v1")
        closeFocusedKeyboard()
        compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
        compose.waitUntil(20000) { savedId != null }
        val updated = runBlocking { model.getPasswordEntryById(requireNotNull(savedId))!! }
        assertEquals(original.id, updated.id)
        assertEquals(original.password, updated.password)
        assertEquals(original.website, updated.website)
        assertEquals("JBSWY3DPEHPK3PXP", security.decryptDataIfMonicaCiphertext(updated.authenticatorKey))
        assertEquals("localhost:11434/v1", runBlocking { model.getCustomFieldsByEntryIdSync(savedId!!).single { it.title == ApiKeyEntryFields.API_URL }.value })
    }

    @Test fun stackReusesWalletBrowserAndCollapsesWithSystemBack() {
        val bank = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BANK_CARD, title = "Bank",
            itemData = CardWalletDataCodec.encodeBankCardData(BankCardData(cardNumber = "4111111111111111", cardholderName = "Holder", expiryMonth = "12", expiryYear = "2030"))))
        val address = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BILLING_ADDRESS, title = "Address",
            itemData = CardWalletDataCodec.encodeBillingAddressData(BillingAddressData(fullName = "Resident"))))
        val parent = PasswordEntry(id = 7, title = "Parent", username = "", password = "", website = "")
        compose.setContent { MaterialTheme {
            takagi.ru.monica.ui.components.DetailWalletStack(listOf(bank, address), parent, "Wallet")
        } }
        compose.onNodeWithTag("detail_wallet_cover").performClick()
        compose.onNodeWithTag("wallet_stack_browser").assertIsDisplayed()
        capture("wallet-page")
        pressFocusedBack()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("wallet_stack_browser").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("detail_wallet_cover").assertIsDisplayed()
    }

    @Test fun realWalletCardsOpenCompleteBankAndBillingDetails() {
        val bank = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BANK_CARD, title = "Fixture bank",
            itemData = CardWalletDataCodec.encodeBankCardData(BankCardData(cardNumber = "4111111111111111", cardholderName = "Fixture Holder", expiryMonth = "12", expiryYear = "2030"))))
        val address = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.BILLING_ADDRESS, title = "Fixture address",
            itemData = CardWalletDataCodec.encodeBillingAddressData(BillingAddressData(fullName = "Fixture Resident", streetAddress = "42 Test Street", email = "fixture@example.test"))))
        val entry = PasswordEntry(id = 7, title = "Parent", username = "", password = "", website = "")
        compose.setContent { MaterialTheme {
            takagi.ru.monica.ui.components.DetailWalletStack(listOf(bank, address), entry, "Wallet")
        } }
        compose.onNodeWithTag("detail_wallet_cover").performClick()
        capture("real-wallet-stack")
        compose.onNodeWithTag("wallet_stack_card_1").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("embedded_wallet_detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Fixture Holder") and hasAnyAncestor(hasTestTag("embedded_wallet_detail"))
            and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Fixture Holder"))))
            .performScrollTo().assertIsDisplayed()
        capture("embedded-bank-detail")
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.onNodeWithTag("wallet_stack_scroll").performTouchInput { swipeUp() }
        compose.onNodeWithTag("wallet_stack_card_2").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("embedded_wallet_detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("42 Test Street") and hasAnyAncestor(hasTestTag("embedded_wallet_detail"))
            and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("42 Test Street"))))
            .performScrollTo().assertIsDisplayed()
        capture("embedded-billing-detail")
    }

    @Test fun embeddedTemplateCancelDoesNotCommitAndUsesCompleteCoreForm() {
        var saved = false
        var open by mutableStateOf(true)
        val block = PasswordContentBlocks.create(PasswordContentBlocks.Kind.SSH_KEY)
        compose.setContent { MaterialTheme {
            if (open) takagi.ru.monica.ui.components.PasswordContentBlockEditor(block,
                onSave = { saved = true }, onDismiss = { open = false })
        } }
        compose.onNodeWithTag("block_field_algorithm").assertExists()
        compose.onNodeWithText(context.getString(R.string.ssh_key_generate)).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.runOnIdle { assertFalse(saved); assertFalse(open) }
    }
}
