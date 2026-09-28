package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.repository.*
import takagi.ru.monica.viewmodel.MdbxViewModel

class MdbxManagementDetailsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun conflictChoiceRequiresConfirmationAndKeepsFullIdsOnNarrowScreen() {
        val calls = mutableListOf<MdbxConflictResolution>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        ConflictDiffDetail(MdbxConflictSummary("conflict-full-id-123456789", "entry", "object-full-id-123456789",
                            "base-full-id-123456789", "local-full-id-123456789", "incoming-full-id-123456789",
                            "[\"title\",\"deleted\"]", "2026-09-28T10:32:17.123Z"), true) { _, choice -> calls += choice }
                    }
                }
            }
        }
        compose.onNodeWithText(label(R.string.mdbx_conflict_values_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.mdbx_conflict_local_wins)).performScrollTo().performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText(label(R.string.mdbx_conflict_incoming_wins)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.mdbx_conflict_confirm_choice)).performClick()
        assertEquals(listOf(MdbxConflictResolution.INCOMING_WINS), calls)
        compose.onNodeWithText(label(R.string.mdbx_conflict_mark_resolved)).assertDoesNotExist()
        compose.onNodeWithText("incoming-full-id-123456789").assertDoesNotExist()
        screenshot("mdbx-conflict-choice.png")
        compose.onNodeWithText(label(R.string.mdbx_ui_technical_details)).performScrollTo().performClick()
        compose.onNodeWithText("incoming-full-id-123456789").performScrollTo().assertIsDisplayed()
        screenshot("mdbx-conflict-parameters.png")
    }

    @Test fun snapshotParametersExpandWithoutChangingDefaultStructureNavigation() {
        var opened = ""
        val snapshot = MdbxSnapshotSummary("snapshot-complete-id-123456789", "base-complete-id-123456789", "Before upgrade",
            "manual", true, 254321, "2026-09-28T10:30:17.123Z", "origin-device-full-id-123456789", false, true)
        compose.setContent {
            MaterialTheme {
                MdbxSnapshotPage(MdbxViewModel.MdbxDeltaDialogState.Visible(1, "Synthetic vault", snapshots = listOf(snapshot)),
                    true, {}, { opened = it }, { _, _, _ -> }, {}, {}, {})
            }
        }
        compose.onNodeWithText("snapshot-complete-id-123456789").assertDoesNotExist()
        compose.onNodeWithText("Before upgrade").performScrollTo().performClick()
        assertEquals(snapshot.snapshotId, opened)
        compose.onNodeWithText(label(R.string.mdbx_ui_technical_details)).performScrollTo().performClick()
        compose.onNodeWithText(snapshot.snapshotId).performScrollTo().assertIsDisplayed()
        screenshot("mdbx-snapshot-parameters.png")
        compose.onNodeWithText(snapshot.createdByDeviceId).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(snapshot.payloadBytes.toString()).performScrollTo().assertIsDisplayed()
    }

    @Test fun historyKeepsSimpleSummaryAndShowsCompleteCommitParametersOnDemand() {
        val delta = MdbxDeltaSummary("commit-full-id-123456789", "device-full-id-123456789", 1234,
            "mutation", "object", "[\"object-full-id-123456789\"]", "Synthetic item", "username,notes", 2,
            "2026-09-28T10:32:17.123Z", "operation-full-id-123456789", "update", "main", "Updated account details")
        compose.setContent {
            MaterialTheme {
                MdbxCommitHistoryPage(MdbxViewModel.MdbxDeltaDialogState.Visible(1, "Synthetic vault",
                    deltas = listOf(delta), selectedDiffCommitId = delta.commitId), {}, {})
            }
        }
        compose.onNodeWithText(delta.deviceId).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.passkey_detail_technical)).performScrollTo().performClick()
        compose.onNodeWithText(delta.commitId).performScrollTo().assertIsDisplayed()
        screenshot("mdbx-history-parameters.png")
        compose.onNodeWithText(delta.createdAt).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(delta.changedObjectIds).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(delta.message!!).performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Window-manager dialog transitions run outside the Compose clock.
        android.os.SystemClock.sleep(400)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
