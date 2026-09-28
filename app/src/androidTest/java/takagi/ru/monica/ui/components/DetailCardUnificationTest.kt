package takagi.ru.monica.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.bringAboveFloatingActions
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.PasswordDetailScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File

class DetailCardUnificationTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(name: String) {
        File(context.getExternalFilesDir(null), "detail-unified-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun changingEntryOrSecretMasksPreviouslyRevealedCustomField() {
        var field by mutableStateOf(CustomField(id = 1, entryId = 1, title = "Recovery", value = "first-secret", isProtected = true))
        compose.setContent { MaterialTheme { Surface { CustomFieldDisplayCard(listOf(field)) } } }
        val show = context.getString(R.string.custom_field_show_content)
        compose.onNodeWithContentDescription(show).performClick()
        compose.onNodeWithText("first-secret").assertIsDisplayed()
        compose.runOnIdle { field = field.copy(value = "replacement-secret") }
        compose.onNodeWithText("replacement-secret").assertDoesNotExist()
        compose.onNodeWithContentDescription(show).performClick()
        compose.onNodeWithText("replacement-secret").assertIsDisplayed()
        compose.runOnIdle { field = field.copy(entryId = 2) }
        compose.onNodeWithText("replacement-secret").assertDoesNotExist()
    }

    @Test fun narrowLargeTextKeepsFullLabelAndEveryActionReachable() {
        var copies = 0
        val title = "A long recovery label for this work account"
        val value = "recovery-team-with-long-name@example.invalid"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme()) { Surface {
                    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()).padding(12.dp).testTag("narrow")) {
                        CustomFieldDisplayCard(listOf(CustomField(id = 1, entryId = 1, title = title, value = value)),
                            onCopyField = { _, _ -> copies++ })
                    }
                } }
            }
        }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(value).assertIsDisplayed()
        val copy = compose.onNodeWithContentDescription(context.getString(R.string.copy))
        copy.performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, copies)
            assertEquals(value, (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString())
        }
        val more = compose.onNodeWithContentDescription(context.getString(R.string.field_action_more))
        val bounds = compose.onNodeWithTag("narrow").fetchSemanticsNode().boundsInRoot
        for (node in listOf(copy, more)) {
            val action = node.fetchSemanticsNode().boundsInRoot
            assertTrue(action.left >= bounds.left && action.right <= bounds.right)
            assertTrue(action.top >= compose.onNodeWithText(value).fetchSemanticsNode().boundsInRoot.bottom)
        }
        capture("narrow-dark")
        more.performClick()
        compose.onNodeWithText(context.getString(R.string.field_action_show_large)).assertIsDisplayed()
    }

    @Test fun passwordOtpDisplayReadsFiveTypesWithoutRewritingStoredPayload() {
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val security = SecurityManager(context)
        val model = PasswordViewModel(PasswordRepository(database.passwordEntryDao(), categoryDao = database.categoryDao()),
            security, strings = AppLocaleStringResolver(context))
        try {
            for (type in takagi.ru.monica.data.model.OtpType.entries) {
                val data = takagi.ru.monica.data.model.TotpData(
                    secret = if (type == takagi.ru.monica.data.model.OtpType.MOTP) "a1b2c3d4e5f60708" else "JBSWY3DPEHPK3PXP",
                    otpType = type, counter = if (type == takagi.ru.monica.data.model.OtpType.HOTP) 42 else 0,
                    pin = if (type in listOf(takagi.ru.monica.data.model.OtpType.MOTP, takagi.ru.monica.data.model.OtpType.YANDEX)) "0421" else "",
                    issuer = "Example", accountName = "alice",
                )
                val payload = takagi.ru.monica.util.TotpDataResolver.toBitwardenPayload("Example", data)
                val ciphertext = security.encryptDataLegacyCompat(payload)
                val entry = PasswordEntry(title = "Example", website = "", username = "alice", password = "", authenticatorKey = ciphertext)
                val display = requireNotNull(model.resolvePasswordDetailAuthenticator(entry))
                assertEquals(type, display.otpType)
                assertEquals(data.counter, display.counter)
                assertEquals(data.pin, display.pin)
                assertEquals(ciphertext, entry.authenticatorKey)
                assertEquals(takagi.ru.monica.util.TotpGenerator.generateOtp(data, currentSeconds = 1234567890),
                    takagi.ru.monica.util.TotpGenerator.generateOtp(display, currentSeconds = 1234567890))
            }
            assertNull(model.resolvePasswordDetailAuthenticator(PasswordEntry(title = "Empty", website = "", username = "", password = "")))
        } finally { model.viewModelScope.cancel(); database.close() }
    }

    @Test fun realPasswordDetailUsesUnifiedFieldsInLightTheme() = realPasswordDetail(large = false)
    @Test fun realPasswordDetailUsesUnifiedFieldsInDarkLargeText() = realPasswordDetail(large = true)

    private fun realPasswordDetail(large: Boolean) {
        SessionManager.markUnlocked()
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val security = SecurityManager(context)
        val model = PasswordViewModel(
            PasswordRepository(database.passwordEntryDao(), categoryDao = database.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(database.customFieldDao()), strings = AppLocaleStringResolver(context),
        )
        try {
            val id = runBlocking {
                val id = database.passwordEntryDao().insertPasswordEntry(PasswordEntry(
                    title = "GitHub · UI fixture", website = "https://github.com", username = "alice@example.invalid",
                    password = security.encryptData("fixture-login-secret"), notes = "Synthetic UI verification only",
                    authenticatorKey = security.encryptDataLegacyCompat("otpauth://hotp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&counter=42"),
                    creditCardNumber = "4242424242424242", creditCardHolder = "ALICE EXAMPLE",
                    creditCardExpiry = "09/2030", creditCardCVV = "123",
                ))
                model.saveCustomFieldsForEntry(id, listOf(
                    CustomFieldDraft(id = -1, title = "Recovery address", value = "backup@example.invalid"),
                    CustomFieldDraft(id = -2, title = "Recovery code", value = "fixture-recovery-code", isProtected = true),
                ))
                id
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else 1f),
                    LocalEntryContentStyle provides true) {
                    MaterialTheme(colorScheme = if (large) darkColorScheme() else lightColorScheme()) {
                        PasswordDetailScreen(model, passwordId = id, biometricEnabled = false,
                            onNavigateBack = {}, onEditPassword = {})
                    }
                }
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("alice@example.invalid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("fixture-login-secret").assertDoesNotExist()
            capture(if (large) "password-top-dark-large" else "password-top-light")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("password_authenticator_card"))
            compose.onNodeWithTag("password_authenticator_card").assertIsDisplayed()
            val copyOtp = compose.onNode(hasAnyAncestor(hasTestTag("password_authenticator_card")) and
                hasContentDescription(context.getString(R.string.copy_verification_code)))
            compose.bringAboveFloatingActions(copyOtp, compose.onNode(hasScrollToIndexAction()))
            capture(if (large) "otp-before-copy-dark-large" else "otp-before-copy-light")
            copyOtp.performClick()
            compose.runOnIdle {
                val expected = takagi.ru.monica.util.TotpGenerator.generateOtp(takagi.ru.monica.data.model.TotpData(
                    secret = "JBSWY3DPEHPK3PXP", otpType = takagi.ru.monica.data.model.OtpType.HOTP, counter = 42))
                assertEquals(expected, (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip!!.getItemAt(0).text.toString())
            }
            capture(if (large) "otp-dark-large" else "otp-light")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("password_payment_face"))
            compose.onNodeWithTag("password_payment_face").assertIsDisplayed()
            compose.onNodeWithText("4242424242424242").assertDoesNotExist()
            capture(if (large) "payment-dark-large" else "payment-light")
            compose.onNodeWithTag("password_payment_expand").performScrollTo().performClick()
            compose.onNodeWithText("4242424242424242").assertDoesNotExist()
            compose.onNodeWithText("123").assertDoesNotExist()
            compose.onNodeWithTag("password_payment_expand").performScrollTo().performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Recovery address"))
            compose.onNodeWithText("backup@example.invalid").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("fixture-recovery-code").assertDoesNotExist()
            val reveal = context.getString(R.string.custom_field_show_content)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(reveal))
            compose.bringAboveFloatingActions(compose.onNodeWithContentDescription(reveal), compose.onNode(hasScrollToIndexAction()))
            capture(if (large) "password-custom-dark-large" else "password-custom-light")
            compose.onNodeWithContentDescription(reveal).performClick()
            compose.onNodeWithText("fixture-recovery-code").performScrollTo().assertIsDisplayed()
            val hide = compose.onNodeWithContentDescription(context.getString(R.string.custom_field_hide_content))
            compose.bringAboveFloatingActions(hide, compose.onNode(hasScrollToIndexAction()))
            hide.performClick()
            compose.onNodeWithText("fixture-recovery-code").assertDoesNotExist()
        } finally {
            model.viewModelScope.cancel()
            database.close()
            SessionManager.markLocked()
        }
    }
}
