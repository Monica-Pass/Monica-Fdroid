package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class MdbxUnknownEntryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownDetailsMaskByDefaultRevealNestedValuesAndKeepBackReachable() {
        val payload = """{"account":"synthetic-user","recovery_codes":["first","第二个",null],"metadata":{"enabled":false,"empty":""}}"""
        compose.setContent {
            MaterialTheme {
                MdbxUnknownEntryContent("CLI 扩展项目", "com.example.recovery-kit", payload, false, {})
            }
        }
        compose.onNodeWithText("com.example.recovery-kit").assertIsDisplayed()
        compose.onNodeWithText("synthetic-user").assertDoesNotExist()
        compose.onNodeWithTag("mdbx-field-hidden-0").assertIsDisplayed()
        compose.onNodeWithTag("mdbx-field-toggle-1").performClick()
        compose.onNodeWithTag("mdbx-field-value-1").assertTextContains("第二个", substring = true)
        compose.onNodeWithTag("mdbx-field-toggle-1").performClick()
        compose.onNodeWithTag("mdbx-field-value-1").assertDoesNotExist()
        compose.onNodeWithTag("mdbx-field-toggle-0").performClick()
        compose.onNodeWithText("synthetic-user").assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.filesDir, "mdbx-unknown-detail.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
