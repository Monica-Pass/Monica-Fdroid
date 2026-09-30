package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.components.BackupProblemDialog
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.util.Locale

class BackupProblemDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var localized: android.content.Context
    private var skipped = emptyList<FailedItem>()
    private var retries = 0
    private var dismissed = false
    private fun show(large: Boolean = false, partial: Boolean = false, genericOnly: Boolean = false) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        localized = ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(config) }
        val entries = (1L..(if (large) 12L else 2L)).map { id ->
            FailedItem(id, if (genericOnly) "Other" else localized.getString(R.string.backup_content_passkeys),
                "example$id.com", localized.getString(if (id % 2L == 0L) R.string.backup_passkey_device_bound else R.string.backup_passkey_unavailable))
        }
        val report = BackupReport(partial, ItemCounts(), ItemCounts(), if (partial) emptyList() else entries,
            emptyList(), skippedItems = if (partial) entries else emptyList())
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                MonicaTheme(darkTheme = large) {
                    BackupProblemDialog(report, { dismissed = true }, { retries++ }, { skipped = it }, partial)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onNodeWithTag("backup_problem_dialog").isDisplayed() }
    }
    private fun capture(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(context.filesDir, "backup-dialog-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun failureActionsShowAdviceAndSkipOnlyOnExplicitClick() {
        show()
        assertTrue(skipped.isEmpty())
        compose.onNodeWithText(localized.getString(R.string.passkey_device_bound_solution)).performScrollTo().assertIsDisplayed()
        capture("light")
        compose.onNodeWithTag("backup_retry").performClick()
        assertEquals(1, retries)
        assertTrue(skipped.isEmpty())
        compose.onNodeWithTag("backup_skip_passkeys").performClick()
        assertEquals(listOf(1L, 2L), skipped.map { it.id })
        compose.onNodeWithTag("backup_problem_close").performClick()
        assertTrue(dismissed)
    }
    @Test fun largeTextLongFailureListKeepsActionsReachable() {
        show(large = true)
        compose.onNodeWithText("example12.com").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("backup_skip_passkeys").assertIsDisplayed()
        compose.onNodeWithTag("backup_problem_close").assertIsDisplayed()
        capture("large-dark")
    }
    @Test fun partialSuccessRemainsExplicitAndDoesNotOfferAnotherSkip() {
        show(partial = true)
        compose.onNodeWithText(localized.getString(R.string.passkey_partial_saved)).assertIsDisplayed()
        compose.onNodeWithTag("backup_skip_passkeys").assertDoesNotExist()
        compose.onNodeWithTag("backup_retry").assertDoesNotExist()
        capture("partial")
        compose.onNodeWithTag("backup_problem_close").performClick()
        assertTrue(dismissed)
    }
    @Test fun unrelatedFailureCannotBeSilentlySkipped() {
        show(genericOnly = true)
        compose.onNodeWithTag("backup_skip_passkeys").assertDoesNotExist()
        compose.onNodeWithTag("backup_retry").assertIsDisplayed()
    }
}
