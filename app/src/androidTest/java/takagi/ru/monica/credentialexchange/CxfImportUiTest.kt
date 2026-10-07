package takagi.ru.monica.credentialexchange

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.CredentialExchangeMetadata
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.screens.PasswordDetailScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

class CxfImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<CxfImportTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun resetContent() = show { }

    private fun show(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CxfImportTestActivity.content = content }
        compose.waitForIdle()
    }

    @Test fun receiveReasonsRemainReadableOnSmallScreenWithLargeText() {
        val reasons = linkedMapOf(CxfCredentialCodec.SkipReason.UNSUPPORTED_TOTP to 2,
            CxfCredentialCodec.SkipReason.PASSKEY_PRF to 1, CxfCredentialCodec.SkipReason.PASSKEY_BLOB to 1)
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).height(480.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        TransferCredentialCounts(5, 1, 4, skippedReasons = reasons)
                    }
                }
            }
        }
        for ((resource, count) in listOf(R.string.exchange_skip_totp to 2, R.string.exchange_skip_prf to 1,
            R.string.exchange_skip_blob to 1)) {
            compose.onNodeWithText(context.getString(resource, count)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.exchange_skip_private_key, 1)).assertDoesNotExist()
        capture("receive-large-text")
    }

    @Test fun finalSummaryRetainsDecodeReasonsSeparatelyFromWriteFailures() {
        show { MaterialTheme {
            Column(Modifier.width(340.dp).height(560.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                TransferSummary(ImportResultSummary(6, 5, 1, false,
                    mapOf(CxfCredentialCodec.SkipReason.PASSKEY_BLOB to 4)))
            }
        } }
        compose.onNodeWithText(context.getString(R.string.exchange_result, 6, 5, 1)).assertExists()
        compose.onNodeWithText(context.getString(R.string.exchange_skip_reasons)).assertExists()
        compose.onNodeWithText(context.getString(R.string.exchange_skip_blob, 4)).performScrollTo().assertIsDisplayed()
        capture("summary")
    }

    @Test fun appScopeStaysHiddenAndSurvivesEditingAndRemovingUserFields() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val security = SecurityManager(context)
        val model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
        val scope = """[{"bundleId":"com.example.synthetic","name":"Synthetic app","certificate":{"hashAlg":"sha512","fingerprint":"AQID"}}]"""
        val id = db.passwordEntryDao().insert(PasswordEntry(title = "Imported fixture", username = "fixture-user",
            password = security.encryptData("synthetic-password"), website = "https://example.invalid"))
        db.customFieldDao().insertAll(listOf(
            CustomField(entryId = id, title = CredentialExchangeMetadata.ANDROID_APPS, value = scope),
            CustomField(entryId = id, title = "Visible field", value = "ordinary value", sortOrder = 1)))
        var editing by mutableStateOf(false)
        var saved by mutableStateOf(false)
        try {
            show {
                CompositionLocalProvider(LocalUiSecurityManager provides security) {
                    MaterialTheme {
                        if (editing) AddEditPasswordScreen(model, passwordId = id, initialStorageExplicit = true,
                            onSaveCompleted = { saved = true }, onNavigateBack = {})
                        else PasswordDetailScreen(model, passwordId = id, biometricEnabled = false,
                            onNavigateBack = {}, onEditPassword = { editing = true })
                    }
                }
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("fixture-user").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Visible field").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(CredentialExchangeMetadata.ANDROID_APPS, substring = true).assertDoesNotExist()
            compose.onNodeWithText(scope, substring = true).assertDoesNotExist()
            capture("detail")
            compose.runOnIdle { editing = true }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
            val editor = compose.onNodeWithTag("password_content_editor")
            editor.performScrollToNode(hasTestTag("content_panel_CUSTOM_FIELDS"))
            compose.onNodeWithTag("content_panel_CUSTOM_FIELDS").performClick()
            compose.onNodeWithText(CredentialExchangeMetadata.ANDROID_APPS, substring = true).assertDoesNotExist()
            compose.onNodeWithText("ordinary value").performScrollTo().performClick()
            compose.onNode(hasText("ordinary value") and hasSetTextAction()).performScrollTo().performTextReplacement("updated value")
            compose.onNodeWithTag("content_detail_back").performClick()
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(15000) { saved }
            var fields = db.customFieldDao().getFieldsByEntryIdSync(id)
            assertEquals(scope, fields.single { it.title == CredentialExchangeMetadata.ANDROID_APPS }.value)
            assertEquals("updated value", fields.single { it.title == "Visible field" }.value)
            compose.runOnIdle { saved = false }
            editor.performScrollToNode(hasTestTag("content_actions_CUSTOM_FIELDS"))
            compose.onNodeWithTag("content_actions_CUSTOM_FIELDS").performClick()
            compose.onNodeWithTag("content_remove").performClick()
            compose.onNodeWithTag("content_remove_confirm").performClick()
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(15000) { saved }
            fields = db.customFieldDao().getFieldsByEntryIdSync(id)
            assertEquals(scope, fields.single { it.title == CredentialExchangeMetadata.ANDROID_APPS }.value)
            assertFalse(fields.any { it.title == "Visible field" })
            assertEquals(CxfAndroidAppScope.restore(scope), CxfAndroidAppScope.restore(fields.single {
                it.title == CredentialExchangeMetadata.ANDROID_APPS }.value))
        } finally { show { }; model.viewModelScope.cancel(); db.close() }
    }

    private fun capture(name: String) {
        val directory = File(context.filesDir, "cxf-import-317").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
