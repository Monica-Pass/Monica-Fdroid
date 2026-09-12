package takagi.ru.monica.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.utils.BackupRetentionConfig
import takagi.ru.monica.utils.WebDavHelper

@RunWith(AndroidJUnit4::class)
class WebDavSyncSettingsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var showing by mutableStateOf(true)
    private val saved = mutableListOf<Pair<WebDavHelper.ChangeTriggeredBackupConfig, BackupRetentionConfig>>()
    private val field get() = compose.onNode(hasSetTextAction())
    private val retentionSwitch get() = compose.onAllNodes(isToggleable())[1]
    private val save get() = compose.onNodeWithText(compose.activity.getString(R.string.save))

    private fun show(
        retention: BackupRetentionConfig = BackupRetentionConfig(),
        changes: WebDavHelper.ChangeTriggeredBackupConfig = WebDavHelper.ChangeTriggeredBackupConfig(),
        autoBackupEnabled: Boolean = false
    ) {
        compose.setContent {
            MaterialTheme {
                if (showing) {
                    WebDavSyncSettingsDialog(
                        config = changes,
                        retentionConfig = retention,
                        autoBackupEnabled = autoBackupEnabled,
                        onDismiss = { showing = false },
                        onConfirm = { changed, count ->
                            saved += changed to count
                            showing = false
                        }
                    )
                } else {
                    TextButton(onClick = { showing = true }) { Text("Reopen settings") }
                }
            }
        }
    }

    @Test
    fun countLimitCanBeSavedWithoutEnablingAutomaticBackup() {
        show()
        retentionSwitch.performScrollTo().assertIsEnabled().performClick()
        field.performScrollTo().performTextReplacement("3")
        save.performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals(BackupRetentionConfig(enabled = true, maxBackups = 3), saved.single().second)
            assertEquals(WebDavHelper.ChangeTriggeredBackupConfig(), saved.single().first)
        }
    }

    @Test
    fun emptyZeroAndOutOfRangeCountsCannotBeSaved() {
        show(retention = BackupRetentionConfig(enabled = true))
        for (invalid in listOf("", "0", "1001")) {
            field.performScrollTo().performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }
        field.performTextReplacement("1000")
        save.assertIsEnabled()
        field.performTextReplacement("1")
        save.performClick()
        compose.runOnIdle {
            assertEquals(BackupRetentionConfig(enabled = true, maxBackups = 1), saved.single().second)
        }
    }

    @Test
    fun cancellingDiscardsTheDraft() {
        show()
        retentionSwitch.performScrollTo().performClick()
        field.performScrollTo().performTextReplacement("7")
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.onNodeWithText("Reopen settings").performClick()
        retentionSwitch.performScrollTo().assertIsOff()
        field.assertDoesNotExist()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
    }

    @Test
    fun longSettingsRemainScrollableAndSavePreservesSyncTiming() {
        val changes = WebDavHelper.ChangeTriggeredBackupConfig(enabled = true, quietMinutes = 5, minIntervalMinutes = 30)
        show(retention = BackupRetentionConfig(enabled = true), changes = changes, autoBackupEnabled = true)
        field.performScrollTo().performClick().performTextReplacement("9")
        save.assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(changes, saved.single().first)
            assertEquals(BackupRetentionConfig(enabled = true, maxBackups = 9), saved.single().second)
        }
    }

    @Test
    fun disablingLimitDiscardsInvalidCountAndKeepsTheLastSavedNumber() {
        show(retention = BackupRetentionConfig(enabled = true, maxBackups = 13))
        field.performScrollTo().performTextReplacement("0")
        retentionSwitch.performScrollTo().performClick()
        save.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(BackupRetentionConfig(enabled = false, maxBackups = 13), saved.single().second)
        }
    }
}
