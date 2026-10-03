package takagi.ru.monica.passkey

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import android.content.res.Configuration
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.PasskeyMoveResultDialog
import takagi.ru.monica.ui.theme.MonicaTheme

class PasskeyMoveResultDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun zeroMovedShowsReasonAndCanBeDismissed() {
        var visible by mutableStateOf(true)
        var dismissals = 0
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(original.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val context = original.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalResources provides context.resources) {
            MonicaTheme {
                if (visible) PasskeyMoveResultDialog(PasskeyMoveReport(0, listOf(
                    PasskeyMoveIssue("Test account", "example.invalid", PasskeyMoveIssueReason.BOUND_PASSWORD),
                ))) { dismissals++; visible = false }
            }
            }
        }
        compose.onNodeWithText("移动结果").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.passkey_move_result_summary, 0, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.passkey_move_bound_reason)).assertIsDisplayed()
        compose.onNodeWithText("Test account").assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("passkey_move_result").captureToImage().asAndroidBitmap()
        File(original.filesDir, "passkey-move-single.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.onNodeWithTag("passkey_move_close").performClick()
        compose.onNodeWithTag("passkey_move_result").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun longReportScrollsWithLargeTextAndKeepsCloseVisibleInBothThemes() {
        var dark by mutableStateOf(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(config)
        val report = PasskeyMoveReport(1, (1..18).map {
            PasskeyMoveIssue("测试账号 $it", "example.invalid", PasskeyMoveIssueReason.entries[(it - 1) % 6])
        })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MonicaTheme(darkTheme = dark) { PasskeyMoveResultDialog(report) {} }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithTag("passkey_move_issues").performScrollToIndex(0)
            compose.onNodeWithText("移动结果").assertIsDisplayed()
            compose.onNodeWithTag("passkey_move_close").assertIsDisplayed()
            val bitmap = compose.onNodeWithTag("passkey_move_result").captureToImage().asAndroidBitmap()
            File(context.filesDir, "passkey-move-${if (night) "dark" else "light"}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            compose.onNodeWithTag("passkey_move_issues").performScrollToIndex(17)
            compose.onNodeWithText("测试账号 18").assertIsDisplayed()
            compose.onNodeWithTag("passkey_move_close").assertIsDisplayed()
        }
    }
}
