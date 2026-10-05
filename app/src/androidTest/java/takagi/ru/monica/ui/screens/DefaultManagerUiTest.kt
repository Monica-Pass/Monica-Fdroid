package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.defaultmanager.DefaultManagerAccess
import takagi.ru.monica.autofill_ng.defaultmanager.ShizukuDefaultManager

class DefaultManagerUiTest {
    @get:Rule val compose = createAndroidComposeRule<DefaultManagerTestActivity>()

    @Test fun confirmationPrecedesAnyWriteAndSheetRenders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (ShizukuDefaultManager.access() == DefaultManagerAccess.READY) {
            compose.waitUntil(30000) {
                compose.onAllNodesWithTag("default_manager_apply").fetchSemanticsNodes().any {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithTag("default_manager_apply").performClick()
            compose.onNodeWithText(context.getString(R.string.default_manager_confirm)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        } else {
            compose.onNodeWithTag("default_manager_apply").assertIsNotEnabled()
            compose.onNodeWithTag("default_manager_restore").assertIsNotEnabled()
        }
        capture(context, "default-manager.png")
        compose.activityRule.scenario.onActivity { it.intent.putExtra("dark", true) }
        compose.activityRule.scenario.recreate()
        if (ShizukuDefaultManager.access() == DefaultManagerAccess.READY) {
            compose.waitUntil(30000) {
                compose.onAllNodesWithTag("default_manager_apply").fetchSemanticsNodes().any {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
            }
        }
        compose.waitForIdle()
        capture(context, "default-manager-dark-large.png")
    }

    private fun capture(context: android.content.Context, name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
