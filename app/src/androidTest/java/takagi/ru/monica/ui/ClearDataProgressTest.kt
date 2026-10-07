package takagi.ru.monica.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.components.ClearDataProgressSheet
import takagi.ru.monica.ui.components.ClearDataSheetContent
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.ClearDataViewModel

class ClearDataProgressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(dark: Boolean = false, scale: Float = 1f, content: @Composable () -> Unit) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(config) }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MonicaTheme(darkTheme = dark) { content() }
            }
        }
    }
    private fun capture(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(context.cacheDir, "clear-data-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun progressStaysVisibleOnBackAndShowsCommittedCountThenCompletion() {
        var progress by mutableStateOf(ClearDataProgress(ClearDataPhase.PASSWORDS, 500, 3000))
        var dismissed = false
        render { ClearDataProgressSheet(progress) { dismissed = true } }
        compose.onNodeWithTag("clear_data_count").assertTextEquals("已清空 500 / 3000 条")
        compose.onNodeWithTag("clear_data_done").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertFalse(dismissed)
        compose.onNodeWithTag("clear_data_progress_sheet").assertIsDisplayed()
        capture("running")
        compose.runOnIdle { progress = progress.copy(clearedEntries = 3000, status = ClearDataStatus.COMPLETED) }
        compose.onNodeWithTag("clear_data_status").assertTextEquals("清空完成")
        capture("completed")
        compose.onNodeWithTag("clear_data_done").performClick()
        assertTrue(dismissed)
    }

    @Test fun failureRemainsReadableWithLargeFontAndDarkTheme() {
        var dismissed = false
        render(dark = true, scale = 1.7f) {
            ClearDataProgressSheet(ClearDataProgress(ClearDataPhase.NOTES, 500, 800, ClearDataStatus.FAILED)) { dismissed = true }
        }
        compose.onNodeWithTag("clear_data_status").assertTextEquals("未能全部清空")
        compose.onNodeWithTag("clear_data_count").assertTextEquals("已清空 500 / 800 条")
        compose.onNodeWithTag("clear_data_done").performScrollTo().assertIsDisplayed()
        capture("failed-dark-large")
        compose.onNodeWithTag("clear_data_done").performClick()
        assertTrue(dismissed)
    }

    @Test fun verificationDisablesRepeatConfirmationAndSelection() {
        render {
            ClearDataSheetContent(List(6) { true }, { _, _ -> error("Selection should be disabled") },
                "fixture", {}, true, {}, { error("Confirmation should be disabled") }, verifying = true)
        }
        compose.onNodeWithTag("clear_data_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("clear_data_type_0").assertIsNotEnabled()
        compose.onNodeWithTag("clear_data_password").assertIsNotEnabled()
    }

    @Test fun activityRecreationRetainsTheRunningOperation() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ClearDataViewModel { _, report ->
                calls++
                report(ClearDataProgress(ClearDataPhase.PASSWORDS, 500, 1000))
                gate.await()
                report(ClearDataProgress(ClearDataPhase.PASSWORDS, 1000, 1000, ClearDataStatus.COMPLETED))
            } as T
        }
        lateinit var original: ClearDataViewModel
        compose.runOnUiThread {
            original = ViewModelProvider(compose.activity, factory)[ClearDataViewModel::class.java]
            original.start(ClearDataSelection(passwords = true))
        }
        compose.waitUntil { original.progress.value?.clearedEntries == 500 }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            val restored = ViewModelProvider(activity, factory)[ClearDataViewModel::class.java]
            assertSame(original, restored)
            assertEquals(500, restored.progress.value!!.clearedEntries)
            restored.start(ClearDataSelection(passwords = true))
        }
        gate.complete(Unit)
        compose.waitUntil { original.progress.value?.status == ClearDataStatus.COMPLETED }
        assertEquals(1, calls)
    }
}
