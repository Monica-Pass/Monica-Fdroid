package takagi.ru.monica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
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
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

@RunWith(AndroidJUnit4::class)
class NewEntrySecurityReuseTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var viewModel: PasswordViewModel
    private lateinit var security: SecurityManager

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        viewModel = PasswordViewModel(
            PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()),
            security, customFieldRepository = CustomFieldRepository(db.customFieldDao()),
            strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() { viewModel.viewModelScope.cancel(); db.close() }

    @Test fun createAndSavePasswordWithActivitySecurityManager() {
        var savedId: Long? = null
        compose.setContent {
            CompositionLocalProvider(LocalUiSecurityManager provides security) {
                MaterialTheme {
                    AddEditPasswordScreen(
                        viewModel = viewModel, passwordId = null,
                        initialStorageExplicit = true,
                        initialDraft = AddEditPasswordInitialDraft(
                            title = "Navigation regression", username = "fixture-user",
                            password = "fixture-secret-2026"),
                        onSaveCompleted = { savedId = it }, onNavigateBack = {})
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.waitUntil(15_000) { savedId != null }
        val entry = runBlocking { db.passwordEntryDao().getPasswordEntryById(savedId!!) }!!
        assertEquals("Navigation regression", entry.title)
        assertEquals("fixture-user", entry.username)
        assertNotEquals("fixture-secret-2026", entry.password)
        assertEquals("fixture-secret-2026", security.decryptData(entry.password))
    }

    @Test fun qrEditorKeepsItsTypeWhenSaving() {
        var savedId: Long? = null
        val payload = "https://example.test/qr-regression"
        compose.setContent {
            CompositionLocalProvider(LocalUiSecurityManager provides security) {
                takagi.ru.monica.ui.theme.MonicaTheme {
                    AddEditPasswordScreen(
                        viewModel = viewModel, passwordId = null,
                        initialStorageExplicit = true,
                        initialLoginType = takagi.ru.monica.data.model.LOGIN_TYPE_BARCODE,
                        initialDraft = AddEditPasswordInitialDraft(title = "QR type regression"),
                        onSwitchToWifi = {},
                        onSaveCompleted = { savedId = it }, onNavigateBack = {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.login_type_password)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.login_type_sso)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.barcode_manual_input_label))
            .performScrollTo().performTextInput(payload)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val out = java.io.File(context.getExternalFilesDir(null), "qr-editor-314.png")
        out.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.waitUntil(15_000) { savedId != null }
        val entry = runBlocking { db.passwordEntryDao().getPasswordEntryById(savedId!!) }!!
        assertEquals(takagi.ru.monica.data.model.LOGIN_TYPE_BARCODE, entry.loginType)
        assertEquals(payload, security.decryptData(entry.password))
    }
}
