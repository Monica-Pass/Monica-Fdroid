package takagi.ru.monica.localization

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.QuickStatusDeleteBar
import takagi.ru.monica.ui.components.QuickStatusDeletePhase
import takagi.ru.monica.ui.components.QuickStatusDeleteState
import takagi.ru.monica.ui.screens.LanguageSelectionDialog
import takagi.ru.monica.ui.screens.QuickSetupScreen
import takagi.ru.monica.ui.screens.rememberScreenStrings
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache
import takagi.ru.monica.viewmodel.SettingsViewModel

@RunWith(AndroidJUnit4::class)
class ClassicalChineseUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = SettingsManager(context)
    private val store = ViewModelStore()
    private lateinit var originalLocale: Locale
    private lateinit var originalLanguage: Language

    @Before fun prepare() = runBlocking {
        originalLocale = Locale.getDefault()
        originalLanguage = manager.settingsFlow.first().language
        manager.updateLanguage(Language.ENGLISH)
    }

    @After fun restore() {
        compose.runOnIdle { store.clear() }
        runBlocking { manager.updateLanguage(originalLanguage) }
        Locale.setDefault(originalLocale)
    }

    @Test fun settingsLanguageSelectorIncludesAndSelectsClassicalChinese() {
        val english = LocaleHelper.setLocale(context, Language.ENGLISH)
        var selected: Language? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides english.resources.configuration) {
                MaterialTheme {
                    LanguageSelectionDialog(Language.ENGLISH, { selected = it }, {})
                }
            }
        }
        compose.onNodeWithTag("language_option_ENGLISH").assertIsSelected()
        compose.onNodeWithTag("language_options")
            .performScrollToNode(hasTestTag("language_option_CLASSICAL_CHINESE"))
        compose.onNodeWithText("文言文（华夏）").assertIsDisplayed()
        compose.onNodeWithText("Classical Chinese (Huaxia)").assertIsDisplayed()
        capture("classical-chinese-language-selector.png")
        compose.onNodeWithTag("language_option_CLASSICAL_CHINESE").performClick()
        compose.runOnIdle { assertEquals(Language.CLASSICAL_CHINESE, selected) }
    }

    @Test fun quickSetupPersistsTheLanguageAndRefreshesItsWelcomeText() {
        val viewModel = SettingsViewModel(manager)
        val securityManager = SecurityManager(context)
        store.put("classical-language-settings", viewModel)
        val english = LocaleHelper.setLocale(context, Language.ENGLISH)
        val label = english.getString(R.string.language_classical_chinese)
        compose.setContent {
            val settings by viewModel.settings.collectAsState()
            val localized = remember(settings.language) { LocaleHelper.setLocale(context, settings.language) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MaterialTheme {
                    QuickSetupScreen(
                        settingsViewModel = viewModel, securityManager = securityManager,
                        onSkip = {}, onFinish = {}, onOpenMasterPassword = {}, onOpenSecurityQuestions = {},
                        onOpenAutofillSettings = {}, onOpenBitwardenSettings = {}, onOpenWebDavBackup = {},
                        onOpenLocalKeePass = {}, onOpenImportData = {}, onOpenMonicaPlus = {},
                    )
                }
            }
        }
        compose.waitUntil(10_000) { viewModel.settings.value.language == Language.ENGLISH }
        compose.onNodeWithText(english.getString(R.string.qs_change)).performScrollTo().performClick()
        compose.onNodeWithTag("language_options")
            .performScrollToNode(hasTestTag("language_option_CLASSICAL_CHINESE"))
        compose.onNodeWithTag("language_option_CLASSICAL_CHINESE").performClick()
        compose.waitUntil(10_000) { viewModel.settings.value.language == Language.CLASSICAL_CHINESE }
        val classical = LocaleHelper.setLocale(context, Language.CLASSICAL_CHINESE)
        compose.onNodeWithText(classical.getString(R.string.qs_welcome_heading)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label).assertIsDisplayed()
        assertEquals(Language.CLASSICAL_CHINESE, StartupLanguageCache.read(context))
        capture("classical-chinese-quick-setup.png")
    }

    @Test fun rememberedMessagesAndExtractedUiRefreshWhenLanguageChanges() {
        var language by mutableStateOf(Language.ENGLISH)
        compose.setContent {
            val localized = remember(language) { LocaleHelper.setLocale(context, language) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                val strings = rememberScreenStrings()
                MaterialTheme {
                    Column {
                        Text(strings.get(R.string.password))
                        QuickStatusDeleteBar(
                            QuickStatusDeleteState(processed = 3, total = 3, phase = QuickStatusDeletePhase.SUCCESS)
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Deleted 3 entries").assertIsDisplayed()
        compose.runOnIdle { language = Language.CLASSICAL_CHINESE }
        compose.onNodeWithText("密钥").assertIsDisplayed()
        compose.onNodeWithText("事成，已删 3 条。").assertIsDisplayed()
        compose.runOnIdle { language = Language.CHINESE }
        compose.onNodeWithText("密码").assertIsDisplayed()
        compose.onNodeWithText("删除成功，已删除3条").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
