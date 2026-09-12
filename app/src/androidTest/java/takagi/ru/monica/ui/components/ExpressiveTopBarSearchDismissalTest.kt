package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R

@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class ExpressiveTopBarSearchDismissalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var expanded by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var showDialog by mutableStateOf(false)
    @Volatile private var imeVisible = false
    @Volatile private var navigations = 0
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun pressSystemBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private fun show() {
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        compose.setContent {
            MaterialTheme {
                val visible = WindowInsets.isImeVisible
                SideEffect { imeVisible = visible }
                BackHandler { navigations++ }
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    ExpressiveTopBar(
                        title = "卡包",
                        searchQuery = query,
                        onSearchQueryChange = { query = it },
                        isSearchExpanded = expanded,
                        onSearchExpandedChange = { expanded = it },
                        actions = {
                            TextButton(
                                onClick = { expanded = true },
                                modifier = Modifier.testTag("top-actions"),
                            ) { Text("Search") }
                        },
                    )
                    Text("Results: $query")
                }
                if (showDialog) {
                    AlertDialog(
                        onDismissRequest = { showDialog = false },
                        confirmButton = {
                            TextButton(onClick = { showDialog = false }) { Text("Close dialog") }
                        },
                        text = { Text("Another window") },
                    )
                }
            }
        }
        compose.onNodeWithTag("top-actions").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.waitUntil(10_000) { imeVisible }
        compose.onNode(hasSetTextAction()).performTextInput("visa")
        compose.waitForIdle()
    }

    private fun assertClosed() {
        compose.waitUntil(10_000) { !expanded && !imeVisible }
        compose.onNodeWithTag("top-actions").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("", query)
            assertEquals(0, navigations)
        }
    }

    private fun assertResultsPreserved() {
        compose.waitUntil(10_000) { !imeVisible }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("Results: visa").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(expanded)
            assertEquals("visa", query)
            assertEquals(0, navigations)
        }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir("search-tests"))
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    fun firstBackHidesTheKeyboardAndSecondBackExitsSearch() {
        show()
        screenshot("search-open.png")
        pressSystemBack()
        assertResultsPreserved()
        screenshot("search-results-keyboard-hidden.png")
        pressSystemBack()
        assertClosed()
        screenshot("search-closed.png")
        pressSystemBack()
        compose.waitUntil(5_000) { navigations == 1 }
    }

    @Test
    fun keyboardSearchKeepsResultsUntilTheNextBack() {
        show()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertResultsPreserved()
        pressSystemBack()
        assertClosed()
    }

    @Test
    fun theCloseButtonExitsSearchAndHidesTheKeyboardTogether() {
        show()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.topbar_close_search)
        compose.onNodeWithContentDescription(label).performClick()
        assertClosed()
    }

    @Test
    fun hidingTheKeyboardKeepsResultsAndTappingSearchRestoresInput() {
        show()
        compose.runOnUiThread {
            val window = compose.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        assertResultsPreserved()
        compose.onNode(hasSetTextAction()).performTouchInput { click() }
        compose.waitUntil(10_000) { imeVisible }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertTrue(expanded)
            assertEquals("visa", query)
            assertEquals(0, navigations)
        }
    }

    @Test
    fun losingWindowFocusToADialogDoesNotClearTheSearch() {
        show()
        compose.runOnIdle { showDialog = true }
        compose.onNodeWithText("Another window").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(expanded)
            assertEquals("visa", query)
        }
        compose.onNodeWithText("Close dialog").performClick()
        compose.runOnIdle {
            assertTrue(expanded)
            assertEquals("visa", query)
        }
    }
}
