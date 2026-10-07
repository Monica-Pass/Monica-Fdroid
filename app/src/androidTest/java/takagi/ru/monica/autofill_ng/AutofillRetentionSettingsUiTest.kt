package takagi.ru.monica.autofill_ng

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.suggestions.CommonInfoTestActivity
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.ui.screens.AutofillSettingsV2Screen

class AutofillRetentionSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager by lazy { SettingsManager(context) }
    private lateinit var original: AppSettings
    @Before fun setup() = runBlocking {
        original = manager.settingsFlow.first()
        manager.updateAutofillAuthRequired(true)
        manager.updateAutofillKeepUnlocked(false)
    }
    @After fun restore() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CommonInfoTestActivity.content = {} }
        manager.updateAutofillKeepUnlocked(original.autofillKeepUnlocked)
        manager.updateAutofillAuthRequired(original.autofillAuthRequired)
    }
    @Test fun switchDefaultsOffPersistsOnRecreationAndFitsSmallLargeFontScreen() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CommonInfoTestActivity.content = {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f)) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        MaterialTheme { AutofillSettingsV2Screen({}, {}, {}) }
                    }
                }
            }
        }
        val title = context.getString(R.string.autofill_keep_unlocked)
        val row = compose.onNode(hasText(title) and isToggleable())
        row.performScrollTo().assertIsOff().assertIsDisplayed()
        compose.onAllNodes(hasText(title) and isToggleable()).assertCountEquals(1)
        val shot = java.io.File(context.filesDir, "issue-153-screens/settings-default-large-font.png").apply { parentFile?.mkdirs() }
        shot.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        row.performClick()
        compose.waitUntil(10000) { runBlocking { manager.settingsFlow.first().autofillKeepUnlocked } }
        row.assertIsOn()
        compose.activityRule.scenario.recreate()
        row.performScrollTo().assertIsOn()
        val auth = compose.onNode(hasText(context.getString(R.string.autofill_auth_required)) and isToggleable())
        auth.performScrollTo().performClick()
        compose.waitUntil(10000) { !runBlocking { manager.settingsFlow.first().autofillAuthRequired } }
        compose.onNodeWithText(title).assertDoesNotExist()
    }
}
