package takagi.ru.monica.ui.cardwallet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.ui.screens.PageAdjustmentCustomizationScreen
import takagi.ru.monica.utils.PageAdjustmentSettingsSnapshot
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.SettingsViewModel

@RunWith(AndroidJUnit4::class)
class WalletStackLoopSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = SettingsManager(context)
    private val store = ViewModelStore()
    private var original = false

    @Before fun prepare() = runBlocking {
        original = manager.settingsFlow.first().walletStackLoopEnabled
        manager.updateWalletStackLoopEnabled(false)
        withTimeout(10_000) { manager.settingsFlow.first { !it.walletStackLoopEnabled } }
        Unit
    }

    @After fun restore() {
        compose.runOnIdle { store.clear() }
        runBlocking { manager.updateWalletStackLoopEnabled(original) }
    }

    @Test fun customizationSwitchPersistsAndRoundTripsThroughPageSettings() {
        assertFalse(AppSettings().walletStackLoopEnabled)
        assertFalse(PageAdjustmentSettingsSnapshot().walletStackLoopEnabled)
        val viewModel = SettingsViewModel(manager)
        store.put("settings", viewModel)
        compose.setContent {
            MaterialTheme {
                PageAdjustmentCustomizationScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onNavigateToPasswordListCustomization = {},
                    onNavigateToPasswordCardAdjustment = {},
                    onNavigateToAuthenticatorCardAdjustment = {},
                    onNavigateToPasswordFieldCustomization = {},
                    onNavigateToIconSettings = {},
                )
            }
        }
        compose.onNodeWithTag("wallet_stack_loop_setting").performScrollTo()
        val toggle = compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("wallet_stack_loop_setting")))
        toggle.assertIsOff().performClick()
        compose.waitUntil(10_000) { viewModel.settings.value.walletStackLoopEnabled }
        toggle.assertIsOn()
        val snapshot = runBlocking { manager.exportPageAdjustmentSettings() }
        assertTrue(snapshot.walletStackLoopEnabled)

        toggle.performClick()
        compose.waitUntil(10_000) { !viewModel.settings.value.walletStackLoopEnabled }
        toggle.assertIsOff()
        runBlocking {
            manager.importPageAdjustmentSettings(snapshot)
            withTimeout(10_000) { manager.settingsFlow.first { it.walletStackLoopEnabled } }
        }
        compose.waitUntil(10_000) { viewModel.settings.value.walletStackLoopEnabled }
        toggle.assertIsOn()
        assertTrue(runBlocking { SettingsManager(context).settingsFlow.first().walletStackLoopEnabled })
    }
}
