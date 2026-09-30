package takagi.ru.monica.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
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
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.*
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class ApiKeyFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: PasswordDatabase
    private lateinit var passwords: PasswordViewModel
    private val editors = mutableListOf<ApiKeyEditorViewModel>()
    private var hideIme: () -> Unit = {}
    @Volatile private var imeVisible = false

    @Before fun setup() {
        SessionManager.markUnlocked()
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        passwords = PasswordViewModel(PasswordRepository(database.passwordEntryDao(), categoryDao = database.categoryDao()),
            SecurityManager(context), customFieldRepository = CustomFieldRepository(database.customFieldDao()),
            strings = AppLocaleStringResolver(context))
    }

    @After fun cleanup() {
        passwords.viewModelScope.cancel()
        editors.forEach { it.viewModelScope.cancel() }
        database.close()
        SessionManager.markLocked()
    }

    @Test fun passwordCreationMenuOpensApiKeyForm() {
        compose.setContent { TestTheme {
            AddEditPasswordScreen(viewModel = passwords, passwordId = null,
                onSwitchToWifi = {}, onNavigateBack = {})
        } }
        val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        compose.onNodeWithContentDescription(context.createConfigurationContext(config).getString(R.string.entry_type_chip_content_description))
            .performClick()
        compose.onNodeWithText("API Key").assertIsDisplayed()
        capture("type-menu.png")
        compose.onNodeWithText("API Key").performClick()
        compose.onNodeWithTag("password_editor_title").assertIsDisplayed()
        compose.onNodeWithTag("template_field_key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("password_editor_save").assertIsDisplayed()
        capture("new-entry.png")
    }

    @Test fun createReopenCopyAndEditPreserveKeyAndAllFiveFields() {
        var savedId: Long? = null
        var phase by mutableIntStateOf(0)
        val editor = ApiKeyEditorViewModel().also(editors::add)
        val edit = ApiKeyEditorViewModel().also(editors::add)
        var detail: PasswordEntry? = null
        var fields: List<CustomField> = emptyList()
        compose.setContent { TestTheme {
            key(phase) {
                when (phase) {
                    1 -> PasswordDetailScreen(passwords, passwordId = requireNotNull(savedId),
                        biometricEnabled = false, onNavigateBack = {}, onEditPassword = { phase = 2 })
                    else -> ApiKeyScreen(passwords, onBack = {}, passwordId = if (phase == 2) savedId else null,
                        onSaved = { savedId = it }, editor = if (phase == 2) edit else editor)
                }
            }
        } }
        input("password_editor_title", "Claude 官方")
        input("template_website", "https://console.anthropic.com")
        input("template_field_key", "sk-api-fixture-13579")
        input("template_field_url", "https://api.anthropic.com/v1")
        input("password_content_notes", "个人开发\n测试环境")
        compose.onNodeWithTag("template_field_key").performScrollTo().assert(hasPassword())
        compose.onNodeWithTag("password_editor_favorite").performClick()
        hideKeyboard()
        capture("filled-editor.png")
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(20_000) { savedId != null }
        runBlocking {
            val stored = requireNotNull(database.passwordEntryDao().getPasswordEntryById(savedId!!))
            assertTrue(stored.isApiKeyEntry())
            assertFalse(stored.password.contains("sk-api-fixture"))
            assertTrue(stored.isFavorite)
            detail = passwords.getPasswordEntryById(savedId!!)
            fields = passwords.getCustomFieldsByEntryIdSync(savedId!!)
            assertEquals("sk-api-fixture-13579", detail!!.password)
            assertEquals("https://api.anthropic.com/v1", fields.first { it.title == ApiKeyEntryFields.API_URL }.value)
            assertEquals("https://console.anthropic.com", detail!!.website)
            assertEquals("个人开发\n测试环境", detail!!.notes)
        }
        compose.runOnIdle { phase = 1 }
        compose.onNodeWithTag("api_key_detail_secret").assertTextEquals("••••••••••••••••")
        compose.onNodeWithText("sk-api-fixture-13579").assertDoesNotExist()
        compose.onNodeWithTag("api_key_detail_secret_copy").performScrollTo().performClick()
        compose.runOnIdle {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
            assertEquals("sk-api-fixture-13579", clip.getItemAt(0).text.toString())
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            }
        }
        compose.onNodeWithTag("api_key_detail_secret_reveal").performClick()
        compose.onNodeWithTag("api_key_detail_secret").assertTextEquals("sk-api-fixture-13579")
        compose.onNodeWithTag("api_key_detail_secret_reveal").performClick()
        capture("detail.png")
        compose.runOnIdle { phase = 2 }
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("template_field_key") and hasText("sk-api-fixture-13579")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("template_field_key").performScrollTo().assert(hasPassword())
        compose.onNodeWithTag("template_field_key").assertTextContains("sk-api-fixture-13579")
        compose.onNodeWithTag("password_editor_favorite").assertIsSelected()
        compose.onNodeWithTag("template_field_url").performScrollTo().performTextClearance()
        input("password_editor_title", "Claude 备用", clear = true)
        savedId = null
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(20_000) { savedId != null }
        runBlocking {
            val updated = requireNotNull(passwords.getPasswordEntryById(savedId!!))
            assertEquals("Claude 备用", updated.title)
            assertEquals("sk-api-fixture-13579", updated.password)
            assertEquals("https://console.anthropic.com", updated.website)
            assertTrue(passwords.getCustomFieldsByEntryIdSync(savedId!!).none { it.title == ApiKeyEntryFields.API_URL })
        }
    }

    @Test fun missingRequiredKeyStaysInEditorAndDarkLargeTextRemainsUsable() {
        val editor = ApiKeyEditorViewModel().also(editors::add)
        var saved = false
        compose.setContent { TestTheme(large = true) {
            ApiKeyScreen(passwords, onBack = {}, onSaved = { saved = true }, editor = editor)
        } }
        compose.onNodeWithTag("password_editor_save").performClick()
        assertFalse(saved)
        compose.onNodeWithTag("template_field_key").assertExists()
        input("password_editor_title", "我的中转站")
        input("template_field_url", "local-service")
        compose.onNodeWithTag("password_editor_save").performClick()
        assertFalse(saved)
        compose.onNodeWithTag("template_field_url").performScrollTo().assertIsDisplayed()
        hideKeyboard()
        capture("dark-large-validation.png")
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("password_editor_heading").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithTag("password_editor_save").assertIsDisplayed()
    }

    @Test fun lockClearsSecretDraftAndPreventsSave() {
        val editor = ApiKeyEditorViewModel().also(editors::add)
        var saved = false
        compose.setContent { TestTheme {
            ApiKeyScreen(passwords, onBack = {}, onSaved = { saved = true }, editor = editor)
        } }
        input("password_editor_title", "Local AI")
        input("template_field_key", "synthetic-lock-test")
        compose.runOnIdle { SessionManager.markLocked() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("template_field_key").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("password_editor_save").assertDoesNotExist()
        assertFalse(saved)
    }

    private fun input(tag: String, value: String, clear: Boolean = false) {
        if (tag == "password_content_notes") {
            closeFocusedKeyboard()
            val list = compose.onNode(hasTestTag("password_content_editor") or hasTestTag("password_classic_editor"))
            list.performScrollToNode(hasTestTag("password_content_add"))
            compose.onNodeWithTag("password_content_add").performClick()
            compose.onNodeWithTag("password_content_choose_NOTES").performClick()
        }
        val node = compose.onNodeWithTag(tag).performScrollTo()
        if (clear) node.performTextClearance()
        node.performTextInput(value)
    }

    private fun hasPassword() = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)

    private fun hideKeyboard() {
        compose.runOnIdle { hideIme() }
        compose.waitUntil(5_000) { !imeVisible }
        compose.waitForIdle()
    }

    @Composable private fun TestTheme(large: Boolean = false, content: @Composable () -> Unit) {
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val shown = WindowInsets.isImeVisible
        SideEffect { imeVisible = shown; hideIme = { focusManager.clearFocus(); keyboard?.hide() } }
        val host = LocalContext.current
        val localized = remember(host) {
            val config = android.content.res.Configuration(host.resources.configuration).apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
            }
            val res = host.createConfigurationContext(config).resources
            object : android.content.ContextWrapper(host) {
                override fun getResources() = res
                override fun getAssets() = res.assets
            }
        }
        CompositionLocalProvider(LocalContext provides localized,
            androidx.compose.ui.platform.LocalResources provides localized.resources,
            androidx.compose.ui.platform.LocalConfiguration provides localized.resources.configuration,
            LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
            if (large) MaterialTheme(colorScheme = darkColorScheme(), content = content)
            else MaterialTheme(content = content)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val target = File(context.getExternalFilesDir("api-key-verification"), name)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
