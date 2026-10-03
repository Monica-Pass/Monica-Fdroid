package takagi.ru.monica.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.SettingsViewModel

@RunWith(AndroidJUnit4::class)
class DeveloperLogsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepTestWindowVisible() {
        compose.runOnUiThread {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val data = DeveloperLogSnapshot("complete report with all sources",
        parseDeveloperLogEvents("11:20:00 [ERROR] [Sync] Synthetic upload failed\n    at Fixture.upload(Fixture.kt:42)", DeveloperLogSource.MDBX) +
        parseDeveloperLogEvents("11:20:01 [INFO] [Fill] Synthetic candidates ready", DeveloperLogSource.AUTOFILL), 1727868000000)
    private fun show(state: DeveloperLogsState = DeveloperLogsState(data, false),
        onClear: () -> Unit = {}, onCopy: (DeveloperLogEvent) -> Unit = {}, onShare: () -> Unit = {}, onRefresh: () -> Unit = {}) {
        compose.setContent { MaterialTheme {
            DeveloperLogsContent(state, {}, onRefresh, onClear, onShare, onCopy)
        } }
        compose.waitForIdle()
    }
    private fun scroll(tag: String) {
        compose.onNodeWithTag("logs_list").performScrollToNode(hasTestTag(tag))
    }
    private fun screenshot(name: String) {
        compose.runOnUiThread {
            androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250) // UiAutomation captures the compositor after its next visible frame.
        val file = File(context.filesDir, "developer-logs-$name.png")
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(bitmap)
        file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @Test fun searchFindsWholeStackAndShareRemainsFullReport() {
        var copied = ""; var shared = 0
        show(onCopy = { copied = it.text }, onShare = { shared++ })
        compose.onNodeWithTag("logs_search").performTextReplacement("Fixture.kt:42")
        compose.waitForIdle()
        scroll("log_event_MDBX:0")
        compose.onNodeWithTag("log_event_MDBX:0").assertTextEquals(data.events[0].text)
        compose.onNodeWithTag("log_event_MDBX:0").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(data.events[0].text, copied) }
        compose.onNodeWithTag("logs_share").performClick()
        compose.runOnIdle { assertEquals(1, shared) }
        screenshot("expanded")
    }
    @Test fun sourceAndErrorFiltersShowHonestEmptyState() {
        show()
        compose.onNodeWithTag("logs_source").performClick()
        compose.onNodeWithTag("logs_source_AUTOFILL").performClick()
        compose.onNodeWithTag("logs_filter_ERROR").performClick()
        compose.waitForIdle()
        scroll("logs_empty")
        compose.onNodeWithTag("logs_empty").assertIsDisplayed()
        compose.onNodeWithTag("log_event_MDBX:0").assertDoesNotExist()
        screenshot("no-matches")
    }
    @Test fun clearingRequiresConfirmationAndCancelDoesNothing() {
        var clears = 0
        show(onClear = { clears++ })
        compose.onNodeWithTag("logs_more").performClick()
        compose.onNodeWithText(context.getString(R.string.developer_clear_log_buffer)).performClick()
        compose.onNodeWithTag("logs_cancel_clear").performClick()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithTag("logs_more").performClick()
        compose.onNodeWithText(context.getString(R.string.developer_clear_log_buffer)).performClick()
        screenshot("clear-confirmation")
        compose.onNodeWithTag("logs_confirm_clear").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }
    @Test fun loadingAndFailureKeepActionsConsistent() {
        var state by mutableStateOf(DeveloperLogsState())
        var refreshes = 0
        compose.setContent { MaterialTheme { DeveloperLogsContent(state, {}, { refreshes++ }, {}, {}, {}) } }
        compose.onNodeWithTag("logs_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("logs_share").assertIsNotEnabled()
        compose.runOnIdle { state = DeveloperLogsState(loading = false, error = "fixture read failure") }
        compose.onNodeWithTag("logs_refresh").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithTag("logs_share").assertIsNotEnabled()
    }
    @Test fun filtersSurviveSavedStateRestorationWithLargeDarkText() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme()) { DeveloperLogsContent(DeveloperLogsState(data, false), {}, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("logs_search").performTextReplacement("not-found")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("logs_search").assertTextContains("not-found")
        compose.waitForIdle(); scroll("logs_empty")
        compose.onNodeWithTag("logs_empty").assertIsDisplayed()
        screenshot("dark-large-font")
    }
    @Test fun chineseSettingsAndLogLayoutRemainReadable() {
        val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(config)
        val settings = SettingsViewModel(SettingsManager(context))
        var logs by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides localized,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity) {
                MaterialTheme {
                    if (logs) DeveloperLogsContent(DeveloperLogsState(data, false), { logs = false }, {}, {}, {}, {})
                    else DeveloperSettingsScreen(settings, {}, {}, { logs = true })
                }
            }
        }
        compose.onNodeWithText(localized.getString(R.string.developer_view_logs)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.developer_clear_log_buffer)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.developer_launch_autofill_v2_test)).assertDoesNotExist()
        compose.onNodeWithText(localized.getString(R.string.developer_clear_log_buffer)).performClick()
        screenshot("settings-clear-zh")
        compose.onNodeWithText(localized.getString(R.string.developer_logs_clear_confirmation)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.cancel)).performClick()
        screenshot("settings-zh")
        compose.onNodeWithText(localized.getString(R.string.developer_view_logs)).performClick()
        compose.waitForIdle()
        screenshot("logs-zh")
    }
    @Test fun collectorIncludesPersistentSourcesAndShareGrantsFullReport() = kotlinx.coroutines.runBlocking {
        val marker = "developer-log-fixture-${System.nanoTime()}"
        takagi.ru.monica.mdbx.MdbxDiagLogger.initialize(context)
        takagi.ru.monica.mdbx.MdbxDiagLogger.append("[INFO] [LogViewerFixture] $marker")
        android.util.Log.i("LogViewerFixture", marker)
        var snapshot = DeveloperLogDebugHelper.collectLogs(context)
        repeat(10) {
            if (snapshot.events.none { it.source == DeveloperLogSource.MDBX && it.text.contains(marker) }) {
                kotlinx.coroutines.delay(100)
                snapshot = DeveloperLogDebugHelper.collectLogs(context)
            }
        }
        assertTrue(snapshot.events.any { it.source == DeveloperLogSource.MDBX && it.text.contains(marker) })
        assertTrue(snapshot.events.any { it.source == DeveloperLogSource.SYSTEM })
        assertTrue(snapshot.report.contains("=== Passkey Persisted Logs ==="))
        val intent = DeveloperLogDebugHelper.createShareIntent(context, snapshot.report)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        val report = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(snapshot.report, report)
        assertTrue(report.contains(marker))
    }
    @Test fun developerSettingsOpensIndependentScreenAndBackReturns() {
        var showLogs by mutableStateOf(false)
        val settings = SettingsViewModel(SettingsManager(context))
        compose.setContent { MaterialTheme {
            if (showLogs) DeveloperLogsScreen(onNavigateBack = { showLogs = false })
            else DeveloperSettingsScreen(settings, {}, {}, { showLogs = true })
        } }
        compose.onNodeWithText(context.getString(R.string.developer_view_logs)).assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithText(context.getString(R.string.developer_view_logs)).performClick()
        compose.onNodeWithTag("developer_logs_screen").assertIsDisplayed()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(context.getString(R.string.developer_loading_logs)).fetchSemanticsNodes().isEmpty()
        }
        screenshot("real-logs")
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.onNodeWithText(context.getString(R.string.developer_view_logs)).assertIsDisplayed()
    }
}
