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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.*
import takagi.ru.monica.sync.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.MdbxSyncStatus

class MdbxSyncIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun silentBackgroundTaskAnimatesOnlyItsVaultAndStopsOnEveryOutcome() {
        val databaseId = -System.nanoTime()
        val selected = mutableStateOf(databaseId)
        val visible = mutableStateOf(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val syncLabel = compose.activity.getString(R.string.legacy_ui_sync_mdbx)
        val runningLabel = compose.activity.getString(R.string.keepass_remote_sync_status_syncing)
        // Compose's auto-advance policy cancels infinite animations. Control the
        // clock before composition so bitmap checks exercise real animation frames.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                if (visible.value) MdbxPathSyncActions(MdbxPathSyncState(
                    databaseId = selected.value, pendingCount = 0, isSyncing = false, onSync = {}
                ))
            }
        }
        fun waitForState(description: String) {
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodes(hasContentDescription(syncLabel) and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        try {
            for (outcome in listOf("success", "failure", "cancellation")) {
                val release = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val job = scope.launch {
                    SyncTaskRunner.requestAndAwait(SyncRequest(
                        requestId = "indicator-$databaseId-$outcome", target = SyncTarget.MdbxVault(databaseId),
                        trigger = SyncTrigger.WORKER_RECOVERY, createdAtMillis = System.currentTimeMillis(),
                        priority = SyncPriority.BACKGROUND, mode = SyncMode.SILENT
                    )) {
                        started.complete(Unit)
                        release.await()
                        when (outcome) {
                            "failure" -> throw java.io.IOException("synthetic sync failure")
                            "cancellation" -> throw CancellationException("synthetic cancellation")
                            else -> Unit
                        }
                    }
                }
                try {
                    runBlocking { withTimeout(10_000) { started.await() } }
                    waitForState(runningLabel)
                    compose.mainClock.advanceTimeBy(32)
                    val first = compose.onNodeWithContentDescription(syncLabel).captureToImage().asAndroidBitmap()
                    compose.mainClock.advanceTimeBy(144)
                    val second = compose.onNodeWithContentDescription(syncLabel).captureToImage().asAndroidBitmap()
                    assertFalse("Background execution must visibly rotate the icon", first.sameAs(second))
                    compose.runOnUiThread { selected.value = databaseId - 1 }
                    waitForState("")
                    compose.runOnUiThread { selected.value = databaseId }
                    waitForState(runningLabel)
                    // Re-enter the breadcrumb while the independent worker is still running.
                    compose.runOnUiThread { visible.value = false }
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnUiThread { visible.value = true }
                    waitForState(runningLabel)
                } finally {
                    release.complete(Unit)
                    runBlocking { withTimeout(10_000) { job.join() } }
                }
                waitForState("")
                val first = compose.onNodeWithContentDescription(syncLabel).captureToImage().asAndroidBitmap()
                compose.mainClock.advanceTimeBy(144)
                val second = compose.onNodeWithContentDescription(syncLabel).captureToImage().asAndroidBitmap()
                assertTrue("Icon must stop after $outcome", first.sameAs(second))
            }
        } finally {
            compose.mainClock.autoAdvance = true
            scope.cancel()
        }
    }

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
                        MdbxPathSyncActions(MdbxPathSyncState(-1L, count.value, false, status.value.name) { retries++ })
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
