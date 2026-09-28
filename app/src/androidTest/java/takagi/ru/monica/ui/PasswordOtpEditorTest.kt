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

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()),
            security, customFieldRepository = CustomFieldRepository(db.customFieldDao()),
            strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }

    @Test fun totpCreateAndReedit() = verify(OtpType.TOTP)
    @Test fun hotpCreateAndReedit() = verify(OtpType.HOTP)
    @Test fun steamCreateAndReedit() = verify(OtpType.STEAM)
    @Test fun yandexCreateAndReedit() = verify(OtpType.YANDEX)
    @Test fun motpCreateAndReedit() = verify(OtpType.MOTP)

    private fun show(qr: String? = null) {
        compose.setContent {
            key(editId) {
                CompositionLocalProvider(LocalUiSecurityManager provides security) {
                    MaterialTheme {
                        AddEditPasswordScreen(viewModel = model, passwordId = editId,
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
        compose.onNodeWithTag("password_editor_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun save() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.waitUntil(20_000) { savedId != null }
    }
    private fun payload() = runBlocking {
        val entry = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!))
        requireNotNull(TotpDataResolver.fromAuthenticatorKey(security.decryptDataIfMonicaCiphertext(entry.authenticatorKey)))
    }

    private fun verify(type: OtpType) {
        show()
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
        capture(type.name.lowercase())
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

    private fun capture(name: String) {
        val dir = File(context.getExternalFilesDir(null), "password-otp-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
