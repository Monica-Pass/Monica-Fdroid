package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.navigation.Screen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.SettingsViewModel

class SettingsSearchInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = SettingsManager(context)
    private val model = SettingsViewModel(manager)
    private val store = androidx.lifecycle.ViewModelStore().apply { put("settings-search", model) }
    private lateinit var nav: NavHostController
    private lateinit var searchNavigation: SettingsSearchNavigation
    private var destructiveCalls = 0

    @After fun finish() {
        compose.runOnIdle { store.clear() }
    }

    @Composable private fun Root() {
        SettingsScreen(
            viewModel = model,
            onNavigateBack = { nav.popBackStack() },
            onResetPassword = { destructiveCalls++ },
            onSecurityQuestions = { destructiveCalls++ },
            onNavigateToMasterPasswordLocking = {},
            onClearAllData = { _, _, _, _, _, _ -> destructiveCalls++ },
        )
    }

    private fun show(dark: Boolean = false, large: Boolean = false, plus: Boolean = false) {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            nav = rememberNavController()
            val navigation = rememberSettingsSearchNavigation(nav)
            searchNavigation = navigation
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, if (large) 1.5f else 1f),
                LocalSettingsSearchNavigation provides navigation,
            ) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(if (large) 320.dp else 390.dp).fillMaxHeight()) {
                        NavHost(navController = nav, startDestination = "search-test") {
                            composable("search-test") { Root() }
                            composable(Screen.Settings.route) { Root() }
                            composable(Screen.AutofillSettings.route) {
                                AutofillSettingsV2Screen(
                                    onNavigateBack = { nav.popBackStack() },
                                    onNavigateToBlockedFields = {},
                                    onNavigateToSaveBlockedTargets = {},
                                )
                            }
                            composable(Screen.IconSettings.route) { IconSettingsScreen(model, onNavigateBack = { nav.popBackStack() }) }
                            composable(Screen.PasswordFieldCustomization.route) { PasswordFieldCustomizationScreen(model, onNavigateBack = { nav.popBackStack() }) }
                            composable(Screen.MasterPasswordLockingSettings.route) {
                                MasterPasswordLockingSettingsScreen(model, { nav.popBackStack() }, {}, {})
                            }
                            composable(Screen.Extensions.route) { ExtensionsScreen(onNavigateBack = { nav.popBackStack() }, isPlusActivated = plus) }
                            composable(Screen.SyncBackup.route) { SyncBackupScreen(onNavigateBack = { nav.popBackStack() }) }
                            composable(Screen.PageAdjustmentCustomization.route) {
                                PageAdjustmentCustomizationScreen(model, { nav.popBackStack() }, {}, {}, {}, {}, {})
                            }
                            composable(Screen.PasswordListCustomization.route) { PasswordListCustomizationScreen(model, { nav.popBackStack() }) }
                            composable(Screen.PasswordCardAdjustment.route) { PasswordCardAdjustmentScreen(model, { nav.popBackStack() }) }
                            composable(Screen.AuthenticatorCardAdjustment.route) { AuthenticatorCardAdjustmentScreen(model, { nav.popBackStack() }) }
                        }
                    }
                }
            }
        }
    }

    private fun query(text: String) {
        compose.onNodeWithTag("settings_search_input").performScrollTo().performTextReplacement(text)
    }

    private fun hasToggleableState() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun keyboardResultNavigatesToSpecificRowWithoutTogglingAndReturnsToQuery() {
        val before = runBlocking { takagi.ru.monica.autofill_ng.AutofillPreferences(context).imeKeyboardOptions.first() }
        show()
        query("键盘 隐藏")
        compose.onNodeWithTag("settings_search_result_ime_hide_pin_preview_title").assertIsDisplayed()
        compose.onAllNodes(hasToggleableState()).assertCountEquals(0)
        capture("settings-search-keyboard-results.png")
        compose.onNodeWithTag("settings_search_result_ime_hide_pin_preview_title").performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.AutofillSettings.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
        capture("settings-search-keyboard-target.png")
        assertEquals(before, runBlocking { takagi.ru.monica.autofill_ng.AutofillPreferences(context).imeKeyboardOptions.first() })
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithTag("settings_search_input").assertTextContains("键盘 隐藏")
    }

    @Test fun destructiveResultOnlyLocatesTheOriginalEntry() {
        val before = runBlocking { manager.settingsFlow.first() }
        show()
        query("清空数据")
        compose.onNodeWithTag("settings_search_result_clear_all_data").performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.Settings.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, destructiveCalls)
        assertEquals(before, runBlocking { manager.settingsFlow.first() })
        compose.onNodeWithTag("settings_search_results").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithTag("settings_search_input").assertTextContains("清空数据")
    }

    @Test fun aliasSearchAndIconNavigationWorkInDarkNarrowLargeText() {
        show(dark = true, large = true)
        query("ＧＲＯＫ bot")
        compose.onAllNodes(hasToggleableState()).assertCountEquals(0)
        compose.onNodeWithTag("settings_search_result_icon_settings_app_icon_title").assertIsDisplayed()
        capture("settings-search-dark-large.png")
        compose.onNodeWithTag("settings_search_result_icon_settings_app_icon_title").performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.IconSettings.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun lazyPasswordFieldSettingsLocateTheRequestedField() {
        show()
        query("支付信息")
        compose.onNodeWithTag("settings_search_result_password_field_customization_payment_info_title").performScrollTo().performClick()
        compose.waitUntil(10_000) { nav.currentDestination?.route == Screen.PasswordFieldCustomization.route }
        compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun localizedCatalogContainsUniqueConcreteItemsAndEmptySearchHasNoControls() {
        val zh = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        val entries = settingsSearchEntries(zh)
        assertTrue(entries.size >= 100)
        assertEquals(entries.size, entries.distinctBy { it.id }.size)
        assertEquals(Screen.AutofillSettings.route, entries.single { it.id == "ime_hide_pin_preview_title" }.route)
        assertEquals(Screen.Settings.route, entries.single { it.id == "developer_settings" }.route)
        show()
        query("不可能存在的设置项")
        compose.onAllNodes(hasToggleableState()).assertCountEquals(0)
        capture("settings-search-empty.png")
    }

    @Test fun everyIndexedSpecificSettingHasOneVisibleTargetOnItsRealPage() {
        val zh = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        show(plus = true)
        val failures = mutableListOf<String>()
        val entries = settingsSearchEntries(zh, isPlusActivated = true).filter { it.focusTitleRes != 0 }
        for (entry in entries) {
            compose.runOnIdle { searchNavigation.open(entry) }
            try {
                compose.onNode(SemanticsMatcher.expectValue(SettingsSearchTarget, true), useUnmergedTree = true).assertIsDisplayed()
            } catch (error: AssertionError) {
                failures += "${entry.id}: ${error.message}"
                capture("settings-search-failure-${entry.id}.png")
            }
            compose.runOnIdle { nav.popBackStack() }
        }
        assertEquals(0, destructiveCalls)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
