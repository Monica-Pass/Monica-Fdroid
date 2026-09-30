package takagi.ru.monica.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.MdbxSyncStatus

class MdbxSyncIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedChecksStayVisibleWithoutClaimingUnsyncedItemsAndRetryStillWorks() {
        val status = mutableStateOf(MdbxSyncStatus.IN_SYNC)
        val count = mutableStateOf(0)
        val locale = mutableStateOf(Locale.SIMPLIFIED_CHINESE)
        var retries = 0
        fun context() = compose.activity.createConfigurationContext(
            Configuration(compose.activity.resources.configuration).apply { setLocale(locale.value) })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context()) {
                MaterialTheme {
                    Row(Modifier.width(320.dp)) {
                        MdbxPathSyncActions(MdbxPathSyncState(count.value, false, status.value.name) { retries++ })
                    }
                }
            }
        }
        for (language in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
            compose.runOnIdle { locale.value = language; count.value = 3; status.value = MdbxSyncStatus.PENDING_UPLOAD }
            compose.onNodeWithText(context().getString(R.string.legacy_ui_unsynced_count, 3)).assertIsDisplayed()
            for ((state, textId) in listOf(
                MdbxSyncStatus.FAILED to R.string.keepass_remote_sync_status_failed,
                MdbxSyncStatus.REMOTE_CHANGED to R.string.keepass_remote_sync_status_remote_changed,
                MdbxSyncStatus.CONFLICT to R.string.keepass_remote_sync_status_conflict,
            )) {
                compose.runOnIdle { count.value = 0; status.value = state }
                compose.onNodeWithText(context().getString(textId)).assertIsDisplayed()
                compose.onNodeWithText(context().getString(R.string.legacy_ui_unsynced_count, 0)).assertDoesNotExist()
                if (state == MdbxSyncStatus.FAILED) {
                    val image = compose.onRoot().captureToImage().asAndroidBitmap()
                    File(compose.activity.cacheDir, "mdbx-sync-status-${language.language}.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            compose.onNodeWithContentDescription(context().getString(R.string.legacy_ui_sync_mdbx)).performClick()
            compose.runOnIdle { status.value = MdbxSyncStatus.IN_SYNC }
            compose.onNodeWithText(context().getString(R.string.keepass_remote_sync_status_conflict)).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(2, retries) }
    }
}
