package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File

@RunWith(AndroidJUnit4::class)
class PasswordOtpEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    private var editId by mutableStateOf<Long?>(null)
    private var savedId: Long? = null
    private var detailVisible by mutableStateOf(false)
    private var editorOpenCount by mutableStateOf(0)
    private var previousContentEditor = false

    @Before fun setup() {
        runBlocking {
            val settings = takagi.ru.monica.utils.SettingsManager(context)
            previousContentEditor = settings.settingsFlow.first().passwordContentEditorEnabled
            settings.updatePasswordContentEditorEnabled(false)
        }
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()),
            security, customFieldRepository = CustomFieldRepository(db.customFieldDao()),
            strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() {
        model.viewModelScope.cancel(); db.close()
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(previousContentEditor) }
    }

    @Test fun totpCreateAndReedit() = verify(OtpType.TOTP)
    @Test fun hotpCreateAndReedit() = verify(OtpType.HOTP)
    @Test fun steamCreateAndReedit() = verify(OtpType.STEAM)
    @Test fun yandexCreateAndReedit() = verify(OtpType.YANDEX)
    @Test fun motpCreateAndReedit() = verify(OtpType.MOTP)
    @Test fun onDemandEditorAddsMotpAndKeepsItAfterReedit() = verify(OtpType.MOTP, onDemand = true)
    @Test fun onDemandEditorAddsTotpAndKeepsItAfterReedit() = verify(OtpType.TOTP, onDemand = true)
    @Test fun onDemandEditorAddsHotpAndKeepsItAfterReedit() = verify(OtpType.HOTP, onDemand = true)
    @Test fun onDemandEditorAddsSteamAndKeepsItAfterReedit() = verify(OtpType.STEAM, onDemand = true)
    @Test fun onDemandEditorAddsYandexAndKeepsItAfterReedit() = verify(OtpType.YANDEX, onDemand = true)

    @Test fun onDemandContactsAndAddressSurviveSaveReeditAndClearing() {
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show()
        addContent("CONTACT")
        scroll("entry_contact_email_0").performTextReplacement("alice@example.invalid")
        scroll("entry_contact_phone_0").performTextReplacement("+44 20 7946 0123")
        addContent("ADDRESS")
        scroll("entry_address_street").performTextReplacement("12 Example Street")
        scroll("entry_address_city").performTextReplacement("London")
        scroll("entry_address_postal").performTextReplacement("SW1A 1AA")
        scroll("entry_address_country").performTextReplacement("UK")
        save()
        val original = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!)) }
        assertEquals("alice@example.invalid", original.email)
        assertEquals("+44 20 7946 0123", original.phone)
        assertEquals("12 Example Street", original.addressLine)
        assertEquals("London", original.city)
        assertEquals("SW1A 1AA", original.zipCode)
        assertEquals("UK", original.country)
        compose.runOnIdle { editId = savedId; savedId = null }
        // Existing sections may start collapsed; requesting them also opens their panels.
        addContent("CONTACT")
        scroll("entry_contact_email_0").assertTextContains("alice@example.invalid")
        scroll("entry_contact_phone_0").assertTextContains("+44 20 7946 0123")
        scroll("entry_contact_phone_0").performTextReplacement("")
        addContent("ADDRESS")
        scroll("entry_address_postal").assertTextContains("SW1A 1AA")
        scroll("entry_address_city").performTextReplacement("Oxford")
        save()
        runBlocking {
            val edited = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!))
            assertEquals(original.email, edited.email)
            assertEquals("", edited.phone)
            assertEquals("Oxford", edited.city)
            assertEquals(original.addressLine, edited.addressLine)
            assertEquals(original.zipCode, edited.zipCode)
            assertEquals(original.country, edited.country)
        }
        capture("content-contact-address-reedited")
    }

    @Test fun onDemandHiddenCustomFieldSurvivesSaveReeditAndExplicitDeletion() {
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show()
        addContent("CUSTOM_FIELDS")
        scroll("entry_content_add").performClick()
        compose.onNodeWithTag("entry_field_kind_1").performScrollTo().performClick()
        val name = hasSetTextAction() and hasText(context.getString(R.string.custom_field_name_placeholder))
        val value = hasSetTextAction() and hasText(context.getString(R.string.custom_field_value))
        scrollTo(name).performTextReplacement("Recovery phrase")
        scrollTo(value).performTextReplacement("synthetic hidden recovery")
        save()
        val original = runBlocking { db.customFieldDao().getFieldsByEntryIdSync(savedId!!).single() }
        assertEquals("Recovery phrase", original.title)
        assertTrue(original.isProtected)
        assertEquals("synthetic hidden recovery", security.decryptDataIfMonicaCiphertext(original.value))
        compose.runOnIdle { editId = savedId; savedId = null }
        val title = hasText("Recovery phrase")
        scrollTo(title).assertIsDisplayed()
        compose.onNodeWithText("synthetic hidden recovery").assertDoesNotExist()
        save()
        runBlocking {
            val saved = db.customFieldDao().getFieldsByEntryIdSync(savedId!!).single()
            assertEquals(original.title, saved.title)
            assertEquals(original.isProtected, saved.isProtected)
            assertEquals("synthetic hidden recovery", security.decryptDataIfMonicaCiphertext(saved.value))
        }
        // Saving completes the screen; reopen it as navigation does before editing again.
        compose.runOnIdle { editId = savedId; savedId = null; editorOpenCount++ }
        scrollTo(title)
        scrollTo(hasContentDescription(context.getString(R.string.delete))).performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.runOnIdle { savedId = null }
        save()
        runBlocking { assertTrue(db.customFieldDao().getFieldsByEntryIdSync(savedId!!).isEmpty()) }
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val editor = compose.onNode(hasTestTag("password_classic_editor") or hasTestTag("password_content_editor"))
        editor.performScrollToNode(matcher)
        val node = compose.onNode(matcher)
        compose.bringAboveFloatingActions(node, editor)
        return node
    }

    private fun addContent(section: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scrollTo(hasTestTag("password_content_add")).performClick()
        compose.onNodeWithTag("password_content_choose_$section").performScrollTo().performClick()
    }

    @Test fun onDemandPaymentAndAuthenticatorSaveReopenAndAppearTogetherInDetails() {
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP")
        scroll("password_content_add").performClick()
        compose.onNodeWithTag("password_content_choose_ATTACHMENTS").performScrollTo().performClick()
        captureAttachmentFooter("content-attachments-large")
        scroll("password_content_add").performClick()
        compose.onNodeWithTag("password_content_choose_PAYMENT").performScrollTo().performClick()
        scroll("entry_payment_number").performTextReplacement("4242 4242 4242 4242")
        scroll("entry_payment_holder").performTextReplacement("ALICE EXAMPLE")
        scroll("entry_payment_expiry").performTextReplacement("09/2030")
        scroll("entry_payment_cvv").performTextReplacement("123")
        save()
        val original = payload()
        runBlocking {
            val entry = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!))
            assertEquals("4242424242424242", entry.creditCardNumber)
            assertEquals("ALICE EXAMPLE", entry.creditCardHolder)
            assertEquals("09/2030", entry.creditCardExpiry)
            assertEquals("123", entry.creditCardCVV)
        }
        compose.runOnIdle { editId = savedId; savedId = null }
        scroll("otp_type_selector")
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("password_otp_secret") and hasText(original.secret)).fetchSemanticsNodes().isNotEmpty()
        }
        val paymentTitle = context.getString(R.string.payment_info)
        compose.onNodeWithTag("password_content_editor").performScrollToNode(hasText(paymentTitle))
        compose.bringAboveFloatingActions(compose.onNodeWithText(paymentTitle), compose.onNodeWithTag("password_content_editor"))
        compose.onNodeWithText(paymentTitle).performClick()
        scroll("entry_payment_number")
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("entry_payment_number") and hasText("4242424242424242"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        save()
        assertEquals(original, payload())
        compose.runOnIdle { detailVisible = true }
        compose.waitUntil(20_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("password_authenticator_card"))
        compose.onNodeWithTag("password_authenticator_card").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("password_payment_face"))
        compose.onNodeWithTag("password_payment_face").assertIsDisplayed()
        compose.onNodeWithText("4242424242424242").assertDoesNotExist()
        capture("payment-and-otp-detail")
    }

    private fun show(qr: String? = null) {
        compose.setContent {
            key(editId, editorOpenCount) {
                CompositionLocalProvider(LocalUiSecurityManager provides security) {
                    MaterialTheme {
                        if (detailVisible) {
                            takagi.ru.monica.ui.screens.PasswordDetailScreen(model, passwordId = requireNotNull(savedId),
                                biometricEnabled = false, onNavigateBack = {}, onEditPassword = {})
                        } else AddEditPasswordScreen(viewModel = model, passwordId = editId,
                            initialStorageExplicit = true,
                            initialDraft = if (editId == null) AddEditPasswordInitialDraft(
                                title = "OTP fixture", username = "alice", password = "fixture-only-password") else null,
                            pendingQrResult = if (editId == null) qr else null,
                            onSaveCompleted = { savedId = it }, onNavigateBack = {})
                    }
                }
            }
        }
    }

    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNode(hasTestTag("password_classic_editor") or hasTestTag("password_content_editor")).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun save() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNode(hasTestTag("password_editor_save") or
            hasContentDescription(context.getString(R.string.save))).performClick()
        compose.waitUntil(20_000) { savedId != null }
    }
    private fun payload() = runBlocking {
        val entry = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!))
        requireNotNull(TotpDataResolver.fromAuthenticatorKey(security.decryptDataIfMonicaCiphertext(entry.authenticatorKey)))
    }

    private fun verify(type: OtpType, onDemand: Boolean = false) {
        if (onDemand) runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show()
        if (onDemand) {
            scroll("password_content_add").performClick()
            compose.onNodeWithTag("password_content_choose_AUTHENTICATOR").performScrollTo().performClick()
        }
        scroll("password_otp_secret").performTextReplacement(
            if (type == OtpType.MOTP) "a1b2c3d4e5f60708" else "JBSWY3DPEHPK3PXP")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("otp_type_selector").performClick()
        OtpType.entries.forEach { compose.onNodeWithTag("otp_type_${it.name}").assertExists() }
        compose.onNodeWithTag("otp_type_${type.name}").performClick()
        if (type == OtpType.HOTP) scroll("otp_counter").performTextReplacement("42")
        if (type in listOf(OtpType.MOTP, OtpType.YANDEX)) {
            scroll("otp_pin").performTextReplacement("0421")
            compose.onNodeWithTag("otp_pin").assert(
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        capture((if (onDemand) "content-" else "") + type.name.lowercase())
        if (type == OtpType.TOTP && !onDemand) captureAttachmentFooter("classic-attachments-large")
        save()
        val original = payload()
        assertEquals(type, original.otpType)
        if (type == OtpType.HOTP) assertEquals(42L, original.counter)
        if (type in listOf(OtpType.MOTP, OtpType.YANDEX)) assertEquals("0421", original.pin)
        compose.runOnIdle { editId = savedId; savedId = null }
        scroll("otp_type_selector")
        // Wait for the async existing-entry load, then save again without changing OTP.
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("password_otp_secret") and hasText(original.secret))
                .fetchSemanticsNodes().isNotEmpty()
        }
        save()
        assertEquals(original, payload())
    }

    @Test fun hotpQrRestoresCounterAndAlgorithmBeforeChangingSecret() {
        show("otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP&counter=99&digits=8&algorithm=SHA512")
        scroll("otp_counter").assertTextContains("99")
        scroll("password_otp_secret").performTextReplacement("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        save()
        val result = payload()
        assertEquals(OtpType.HOTP, result.otpType)
        assertEquals(99L, result.counter)
        assertEquals(8, result.digits)
        assertEquals("SHA512", result.algorithm)
    }

    @Test fun switchingAndRemovingCredentialsKeepsOtpDraftsSeparate() {
        show()
        scroll("password_otp_secret").performTextReplacement("JBSWY3DPEHPK3PXP")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("otp_type_selector").performClick()
        compose.onNodeWithTag("otp_type_HOTP").performClick()
        scroll("otp_counter").performTextReplacement("42")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription(context.getString(R.string.add_credential)).performClick()
        scroll("password_otp_secret").performTextReplacement("a1b2c3d4e5f60708")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("otp_type_selector").performClick()
        compose.onNodeWithTag("otp_type_MOTP").performClick()
        scroll("otp_pin").performTextReplacement("0421")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        fun select(index: Int) {
            compose.onNodeWithTag("password_credential_switcher").performClick()
            compose.onNodeWithTag("password_credential_$index").performClick()
        }
        select(0)
        scroll("otp_counter").assertTextContains("42")
        select(1)
        scroll("otp_pin").assertTextContains("0421")
        compose.onNodeWithTag("password_credential_switcher").performClick()
        compose.onNodeWithTag("password_credential_remove").performClick()
        compose.onNodeWithTag("password_credential_confirm_remove").performClick()
        scroll("otp_counter").assertTextContains("42")
        save()
        assertEquals(OtpType.HOTP, payload().otpType)
        assertEquals(42L, payload().counter)
    }

    private fun captureAttachmentFooter(name: String) {
        val editor = compose.onNode(hasTestTag("password_classic_editor") or hasTestTag("password_content_editor"))
        val buttonText = context.getString(R.string.attachments_add)
        editor.performScrollToNode(hasText(buttonText))
        compose.bringAboveFloatingActions(compose.onNodeWithText(buttonText), editor)
        capture(name)
    }

    private fun capture(name: String) {
        val dir = File(context.getExternalFilesDir(null), "password-otp-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
