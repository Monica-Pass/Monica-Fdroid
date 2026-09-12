package takagi.ru.monica.bitwarden.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel

@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class BitwardenLoginImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Volatile private var imeVisible = false
    @Volatile private var imeBottom = 0
    @Volatile private var imeTargetBottom = 0

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun showSelfHostedLogin(fontScale: Float = 1f) {
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    val visible = WindowInsets.isImeVisible
                    val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    val targetBottom = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current)
                    SideEffect {
                        imeVisible = visible
                        imeBottom = bottom
                        imeTargetBottom = targetBottom
                    }
                    BitwardenLoginScreen(
                        viewModel = viewModel<BitwardenViewModel>(),
                        onNavigateBack = {},
                        onLoginSuccess = {},
                    )
                }
            }
        }
        compose.onNodeWithText("服务器").performScrollTo().performClick()
        compose.onNodeWithText("自托管").performClick()
    }

    private fun assertAboveKeyboard(label: String) {
        val input = field(label).assertIsFocused()
        compose.waitUntil(10_000) {
            imeVisible && imeBottom > 0 && imeBottom == imeTargetBottom
        }
        val windowHeight = compose.runOnIdle { compose.activity.window.decorView.height }
        // assertIsDisplayed alone also passes when the keyboard covers part of a field.
        compose.waitUntil("$label must be fully above the keyboard", 5_000) {
            val node = input.fetchSemanticsNode()
            node.positionInWindow.y + node.size.height <= windowHeight - imeBottom + 1
        }
        input.assertIsDisplayed()
    }

    private fun hideKeyboard() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { !imeVisible && imeBottom == 0 }
    }

    @Test
    fun selfHostedUrlRemainsVisibleWhenKeyboardOpensAndReopens() {
        showSelfHostedLogin()
        val url = field("自托管服务器 URL")
        url.performScrollTo().performClick()
        assertAboveKeyboard("自托管服务器 URL")
        url.performTextInput("https://vault.example.com")

        hideKeyboard()
        url.assertIsFocused().performClick()
        assertAboveKeyboard("自托管服务器 URL")
    }

    @Test
    fun expandedCertificateFieldsRemainVisibleWithLargerText() {
        showSelfHostedLogin(fontScale = 1.3f)
        compose.onNodeWithText("证书与 mTLS 设置").performScrollTo().performClick()
        field("系统证书别名（可选）").performScrollTo().performClick()
        assertAboveKeyboard("系统证书别名（可选）")
        field("自签 CA 证书 PEM（可选）").performScrollTo().performClick()
        assertAboveKeyboard("自签 CA 证书 PEM（可选）")

        hideKeyboard()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        field("客户端证书密码（可选）").performScrollTo().performClick()
        assertAboveKeyboard("客户端证书密码（可选）")
    }

    @After
    fun saveScreenshot() {
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            val directory = instrumentation.targetContext.getExternalFilesDir("bitwarden-ime")!!
            File(directory, "${testName.methodName}.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }
}
