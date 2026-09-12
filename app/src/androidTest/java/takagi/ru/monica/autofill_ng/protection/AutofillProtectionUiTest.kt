package takagi.ru.monica.autofill_ng.protection

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.AutofillProtectionContent
import takagi.ru.monica.ui.screens.ProtectionEvent
import takagi.ru.monica.ui.screens.ShizukuRecoveryRiskDialog
import takagi.ru.monica.ui.theme.MonicaTheme

@RunWith(AndroidJUnit4::class)
class AutofillProtectionUiTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(AutofillProtectionState())
    private val events = mutableListOf<ProtectionEvent>()

    private fun show(fontScale: Float = 1f, riskDialog: Boolean = false, onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            val localizedContext = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MonicaTheme(darkTheme = false) {
                    Box(Modifier.width(360.dp)) {
                        AutofillProtectionContent(state, false, false, remember { SnackbarHostState() }, {}, events::add)
                        if (riskDialog) ShizukuRecoveryRiskDialog(onConfirm, onDismiss)
                    }
                }
            }
        }
    }

    @Test fun togglingBackgroundEmitsOneExplicitAction() {
        show()
        compose.onNodeWithTag("background-protection-toggle").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(ProtectionEvent.Background(true)), events) }
        saveScreenshot("protection-default.png")
    }

    @Test fun permissionEnabledAndServiceConnectedAreShownSeparately() {
        state = state.copy(accessibility = AccessibilityConnection.DISCONNECTED)
        show()
        compose.onNodeWithTag("accessibility-status").assertTextEquals("无障碍已开启，但尚未连接")
        compose.runOnIdle { state = state.copy(accessibility = AccessibilityConnection.CONNECTED) }
        compose.onNodeWithTag("accessibility-status").assertTextEquals("无障碍服务运行中")
    }

    @Test fun rootBackendCannotEnableEnhancedRecovery() {
        state = state.copy(backgroundEnabled = true, runtime = ProtectionRuntime.RUNNING, shizuku = ShizukuAccess.ROOT_MODE)
        show()
        compose.onNodeWithTag("enhanced-recovery-toggle").performScrollTo().assertIsNotEnabled()
    }

    @Test fun revokedBackendStillAllowsTurningRecoveryOff() {
        state = state.copy(backgroundEnabled = true, enhancedRecoveryEnabled = true, shizuku = ShizukuAccess.UNAVAILABLE)
        show()
        compose.onNodeWithTag("enhanced-recovery-toggle").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(ProtectionEvent.EnhancedRecovery(false)), events) }
    }

    @Test fun manualRecoveryIsOnlyOfferedForAnEnabledDisconnectedService() {
        state = state.copy(
            backgroundEnabled = true, enhancedRecoveryEnabled = true,
            accessibility = AccessibilityConnection.DISCONNECTED, shizuku = ShizukuAccess.ADB_AUTHORIZED,
        )
        show()
        compose.onNodeWithTag("recover-accessibility").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ProtectionEvent.RecoverAccessibility), events)
            state = state.copy(accessibility = AccessibilityConnection.DISABLED)
        }
        compose.onNodeWithTag("recover-accessibility").assertDoesNotExist()
    }

    @Test fun riskAcknowledgementIsRequiredBeforeAuthorization() {
        var confirmed = 0
        show(riskDialog = true, onConfirm = { confirmed++ })
        compose.onNodeWithTag("shizuku-risk-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("shizuku-risk-acknowledgement").performScrollTo().performClick()
        compose.onNodeWithTag("shizuku-risk-confirm").assertIsEnabled()
        saveScreenshot("protection-shizuku-risk.png")
        compose.onNodeWithTag("shizuku-risk-confirm").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun cancellingTheRiskDialogDoesNotAuthorizeAnything() {
        var confirmed = 0
        var dismissed = 0
        show(riskDialog = true, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        // Dialogs use their window context, which need not match the preview content locale.
        val cancelLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cancel)
        compose.onNodeWithText(cancelLabel).performClick()
        compose.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test fun settingsRemainReachableWithLargeText() {
        show(fontScale = 1.6f)
        compose.onNodeWithText("打开 Shizuku／使用说明").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ProtectionEvent.OpenSettings(ProtectionSettingsDestination.SHIZUKU)), events)
        }
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "protection-test").apply { mkdirs() }
        val screenshotNode = if (name.contains("risk")) compose.onNode(isDialog()) else compose.onRoot()
        screenshotNode.captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
