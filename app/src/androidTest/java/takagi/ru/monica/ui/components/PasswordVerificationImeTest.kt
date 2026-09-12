package takagi.ru.monica.ui.components

import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.security.MasterPasswordPolicy

@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class PasswordVerificationImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val verifiedPasswords = mutableListOf<String>()
    private val createdPasswords = mutableListOf<String>()
    private var successes = 0
    private var authenticated by mutableStateOf(false)
    @Volatile private var imeVisible = false
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val field get() = compose.onNode(hasSetTextAction())
    private val validPassword = "test-password"

    private fun label(id: Int): String = instrumentation.targetContext.getString(id)

    private fun show(firstTime: Boolean = false, keepFormAfterSuccess: Boolean = false) {
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        compose.setContent {
            MaterialTheme {
                val visible = WindowInsets.isImeVisible
                SideEffect { imeVisible = visible }
                if (authenticated && !keepFormAfterSuccess) {
                    // Both application callers replace this form after authentication.
                    Text("Unlocked")
                } else {
                    PasswordVerificationContent(
                        isFirstTime = firstTime,
                        persistVaultUnlockToSession = false,
                        onVerifyPassword = {
                            verifiedPasswords += it
                            it == validPassword
                        },
                        onSetPassword = { createdPasswords += it },
                        onSuccess = {
                            successes++
                            authenticated = true
                        },
                    )
                }
            }
        }
        field.performClick().assertIsFocused()
        compose.waitUntil(10_000) { imeVisible }
    }

    private fun assertNoAuthentication() {
        compose.runOnIdle {
            assertEquals(0, successes)
            assertTrue(createdPasswords.isEmpty())
        }
    }

    @Test
    fun emptyDoneDoesNotAttemptAuthentication() {
        show()
        field.performImeAction()
        compose.onNodeWithText(label(R.string.unlock)).assertIsNotEnabled()
        field.assertIsFocused()
        assertNoAuthentication()
        compose.runOnIdle { assertTrue(verifiedPasswords.isEmpty()) }
    }

    @Test
    fun wrongPasswordShowsErrorAndDoneCanRetry() {
        show()
        field.performTextInput("wrong-password")
        field.performImeAction()
        compose.onNodeWithText(label(R.string.error_invalid_password)).assertIsDisplayed()
        field.assertIsFocused()
        assertNoAuthentication()

        field.performTextReplacement(validPassword)
        field.performImeAction()
        compose.waitUntil(10_000) { !imeVisible }
        compose.runOnIdle {
            assertEquals(listOf("wrong-password", validPassword), verifiedPasswords)
            assertEquals(1, successes)
        }
    }

    @Test
    fun doneSubmitsOnlyOnceAndHidesTheKeyboard() {
        show(keepFormAfterSuccess = true)
        field.performTextInput(validPassword)
        field.performImeAction()
        compose.waitUntil(10_000) { !imeVisible }
        // Keep the component mounted to cover repeated actions before navigation removes it.
        field.performImeAction()
        compose.onNodeWithText(label(R.string.unlock)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(validPassword), verifiedPasswords)
            assertEquals(1, successes)
        }
    }

    @Test
    fun nativeEnterAlsoSubmitsThePassword() {
        show()
        field.performTextInput(validPassword)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(10_000) { !imeVisible }
        compose.runOnIdle {
            assertEquals(listOf(validPassword), verifiedPasswords)
            assertEquals(1, successes)
        }
    }

    @Test
    fun confirmationButtonUsesTheSameValidation() {
        show()
        field.performTextInput("wrong-password")
        compose.onNodeWithText(label(R.string.unlock)).performClick()
        compose.onNodeWithText(label(R.string.error_invalid_password)).assertIsDisplayed()
        assertNoAuthentication()

        field.performTextReplacement(validPassword)
        compose.onNodeWithText(label(R.string.unlock)).performClick()
        compose.onNodeWithText("Unlocked").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("wrong-password", validPassword), verifiedPasswords)
            assertEquals(1, successes)
        }
        compose.waitUntil(10_000) { !imeVisible }
    }

    @Test
    fun setupUsesNextThenDoneAndRequiresConfirmation() {
        show(firstTime = true)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        field.performTextInput(validPassword)
        field.performImeAction()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
        field.assertIsFocused()
        // An immediate second action cannot confirm the still-empty confirmation field.
        field.performImeAction()
        assertNoAuthentication()

        field.performTextInput(validPassword)
        field.performImeAction()
        compose.waitUntil(10_000) { !imeVisible }
        compose.runOnIdle {
            assertEquals(listOf(validPassword), createdPasswords)
            assertTrue(verifiedPasswords.isEmpty())
            assertEquals(1, successes)
        }
    }

    @Test
    fun setupRejectsMismatchedConfirmation() {
        show(firstTime = true)
        field.performTextInput(validPassword)
        field.performImeAction()
        field.performTextInput("different-password")
        field.performImeAction()
        compose.onNodeWithText(label(R.string.error_passwords_not_match)).assertIsDisplayed()
        assertNoAuthentication()
        compose.runOnIdle { assertTrue(verifiedPasswords.isEmpty()) }
    }

    @Test
    fun setupPreservesMinimumLengthValidation() {
        val shortPassword = "a".repeat(MasterPasswordPolicy.MIN_LENGTH - 1)
        show(firstTime = true)
        field.performTextInput(shortPassword)
        field.performImeAction()
        field.performTextInput(shortPassword)
        field.performImeAction()
        compose.onNodeWithText(label(R.string.password_too_short)).assertIsDisplayed()
        assertNoAuthentication()
        compose.runOnIdle { assertTrue(verifiedPasswords.isEmpty()) }
    }
}
