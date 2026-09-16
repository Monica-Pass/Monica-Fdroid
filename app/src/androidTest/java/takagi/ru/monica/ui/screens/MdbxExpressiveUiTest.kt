package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.repository.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.viewmodel.MdbxViewModel

@RunWith(AndroidJUnit4::class)
class MdbxExpressiveUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.SIMPLIFIED_CHINESE)
    })
    private val database = LocalMdbxDatabase(
        id = 42, name = "个人密库", filePath = "/sample/personal.mdbx",
        engineType = MdbxEngineType.RUST_MDBX2.name,
        lastSyncStatus = MdbxSyncStatus.IN_SYNC.name,
        lastSyncedAt = 1789358400000L
    )
    private val diagnostics = MdbxVaultDiagnostics(
        databaseId = 42, filePath = database.filePath, fileExists = true,
        fileSizeBytes = 2_097_152, isReadable = true, integrityOk = true,
        currentDeviceId = "sample-device-42", formatVersion = "2", defaultTigaMode = "MULTI",
        entryCount = 68, folderCount = 8, commitCount = 24, snapshotCount = 3,
        attachmentCount = 5, externalAttachmentCount = 2, storedAttachmentBytes = 524_288,
        originalAttachmentBytes = 1_048_576, lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
    )
    private val events = mutableListOf<String>()

    private fun label(id: Int): String = chinese.getString(id)

    private fun show(
        title: Int? = R.string.mdbx_ui_database_information,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit
    ) {
        val configuration = Configuration(chinese.resources.configuration).apply { this.fontScale = fontScale }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale)
            ) {
                MonicaTheme(darkTheme = dark) {
                    Scaffold(topBar = {
                        MdbxTopAppBar(
                            title = { Text(title?.let { stringResource(it) } ?: "MDBX", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            navigationIcon = { IconButton(onClick = { events += "back" }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, label(R.string.back))
                            } }
                        )
                    }) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) { content() }
                    }
                }
            }
        }
    }

    @Composable
    private fun Overview(currentDiagnostics: MdbxVaultDiagnostics = diagnostics) {
        MdbxVaultDetailPage(
            database = database, isDefault = false, conflictCount = 2, diagnostics = currentDiagnostics,
            onSync = { events += "sync" }, onShowConflicts = { events += "conflicts" },
            onShowHealth = { events += "health" }, onShowSnapshots = { events += "snapshots" },
            onShowCommitHistory = { events += "history" }, onShowAttachments = { events += "attachments" },
            onShowMaintenance = { events += "maintenance" }, onMigrate = null,
            onSetDefault = { events += "default" }, onDelete = { events += "delete" }
        )
    }

    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun clickLabel(id: Int) {
        scrollToText(label(id))
        compose.onNodeWithText(label(id)).performClick()
    }

    @Test
    fun overviewKeepsEachDestinationAndHidesTechnicalDetailsUntilExpanded() {
        show { Overview() }
        compose.onNodeWithText(label(R.string.keepass_remote_sync_status_in_sync)).assertIsDisplayed()
        compose.onNodeWithText("sample-device-42").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.entry_type_api_token)).assertDoesNotExist()
        capture("mdbx-overview-light.png")
        clickLabel(R.string.mdbx_sync_status_label)
        clickLabel(R.string.mdbx_ui_manager_conflicts_title)
        clickLabel(R.string.mdbx_ui_manager_health_title)
        clickLabel(R.string.mdbx_ui_object_snapshot)
        clickLabel(R.string.mdbx_ui_manager_history_title)
        clickLabel(R.string.mdbx_status_attachments)
        clickLabel(R.string.advanced_options)
        clickLabel(R.string.mdbx_ui_manager_maintenance_title)
        scrollToText("sample-device-42")
        compose.onNodeWithText("sample-device-42").assertIsDisplayed()
        clickLabel(R.string.mdbx_set_default)
        clickLabel(R.string.mdbx_delete)
        compose.runOnIdle {
            assertEquals(listOf("sync", "conflicts", "health", "snapshots", "history", "attachments", "maintenance", "default", "delete"), events)
        }
    }

    @Test
    fun syncProgressAndErrorsRemainReadableWithLargeText() {
        val current = mutableStateOf(diagnostics.copy(lastSyncStatus = MdbxSyncStatus.SYNCING.name))
        show(dark = true, fontScale = 1.5f) { Overview(current.value) }
        compose.onNodeWithText(label(R.string.mdbx_sync_status_label)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.keepass_remote_sync_status_syncing)).assertIsDisplayed()
        capture("mdbx-overview-dark-large-text.png")
        compose.runOnIdle {
            current.value = diagnostics.copy(lastSyncStatus = MdbxSyncStatus.FAILED.name, lastSyncError = "无法连接示例服务器")
        }
        scrollToText("无法连接示例服务器")
        compose.onNodeWithText("无法连接示例服务器").assertIsDisplayed()
        clickLabel(R.string.mdbx_sync_status_label)
        compose.runOnIdle { assertEquals(listOf("sync"), events) }
    }

    @Test
    fun hubOpensEveryStorageSource() {
        show(title = null) {
            MdbxManagerHubPage(2, 1, 1,
                onOpenLocal = { events += "local" }, onOpenWebDav = { events += "webdav" },
                onOpenOneDrive = { events += "onedrive" })
        }
        capture("mdbx-manager-light.png")
        clickLabel(R.string.mdbx_ui_local_databases)
        scrollToText("WebDAV")
        compose.onNodeWithText("WebDAV").performClick()
        scrollToText("OneDrive")
        compose.onNodeWithText("OneDrive").performClick()
        compose.runOnIdle { assertEquals(listOf("local", "webdav", "onedrive"), events) }
    }

    @Test
    fun conflictsPreserveBothResolutionDirectionsAndResolvedAction() {
        val conflict = MdbxConflictSummary(
            conflictId = "conflict-42", objectType = "entry", objectId = "entry-42",
            baseCommitId = "base-42", localCommitId = "local-42", incomingCommitId = "remote-42",
            conflictingFields = "title", createdAt = "2026-09-14T04:00:00Z",
            localTitle = "个人邮箱", incomingTitle = "工作邮箱"
        )
        val choices = mutableListOf<MdbxConflictResolution>()
        show(title = R.string.mdbx_ui_manager_conflicts_title) {
            MdbxConflictPage(MdbxViewModel.MdbxConflictDialogState.Visible(42, database.name, listOf(conflict)),
                databaseName = database.name,
                onResolve = { id, resolution -> assertEquals(conflict.conflictId, id); choices += resolution })
        }
        capture("mdbx-conflicts-light.png")
        compose.onNodeWithText("个人邮箱").performClick()
        compose.onNodeWithText("title").assertDoesNotExist()
        capture("mdbx-conflict-details-light.png")
        clickLabel(R.string.mdbx_conflict_local_wins)
        clickLabel(R.string.mdbx_conflict_incoming_wins)
        clickLabel(R.string.mdbx_conflict_mark_resolved)
        clickLabel(R.string.mdbx_ui_technical_details)
        scrollToText("title")
        compose.onNodeWithText("title").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(MdbxConflictResolution.LOCAL_WINS,
            MdbxConflictResolution.INCOMING_WINS, MdbxConflictResolution.MARK_RESOLVED), choices) }
    }

    private fun snapshot(integrityOk: Boolean = true) = MdbxSnapshotSummary(
        snapshotId = "snapshot-42", baseCommitId = "commit-42", name = "同步前的备份",
        snapshotType = "manual", isFull = true, payloadBytes = 262_144,
        createdAt = "2026-09-14T04:00:00Z", createdByDeviceId = "device-42", autoPrune = false,
        integrityOk = integrityOk
    )

    @Composable
    private fun Snapshots(sample: MdbxSnapshotSummary = snapshot()) {
        MdbxSnapshotPage(
            state = MdbxViewModel.MdbxDeltaDialogState.Visible(42, database.name, snapshots = listOf(sample)),
            engineAlwaysCreatesFullSnapshots = true,
            onShowDiff = { events += "diff:$it" }, onShowSnapshotStructure = { events += "structure:$it" },
            onCreateSnapshot = { _, _, _ -> events += "create" },
            onDeleteSnapshot = { events += "delete:$it" }, onRevertSnapshot = { events += "restore:$it" },
            onPruneAutomaticSnapshots = { events += "prune" }
        )
    }

    private fun openSnapshotMenu() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(label(R.string.mdbx_ui_snapshot_more_actions)))
        compose.onNodeWithContentDescription(label(R.string.mdbx_ui_snapshot_more_actions)).performClick()
    }

    @Test
    fun snapshotRestoreAndDeleteRequireConfirmation() {
        show(title = R.string.mdbx_ui_object_snapshot) { Snapshots() }
        capture("mdbx-snapshots-light.png")
        openSnapshotMenu()
        compose.onNodeWithText(label(R.string.mdbx_ui_snapshot_restore)).performClick()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        openSnapshotMenu()
        compose.onNodeWithText(label(R.string.mdbx_ui_snapshot_restore)).performClick()
        compose.onNodeWithText(label(R.string.mdbx_ui_snapshot_restore_confirm)).performClick()
        openSnapshotMenu()
        compose.onNodeWithText(label(R.string.mdbx_ui_snapshot_delete)).performClick()
        compose.runOnIdle { assertEquals(listOf("restore:snapshot-42"), events) }
        compose.onNodeWithText(label(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals(listOf("restore:snapshot-42", "delete:snapshot-42"), events) }
    }

    @Test
    fun invalidSnapshotCannotBeRestored() {
        show(title = R.string.mdbx_ui_object_snapshot) { Snapshots(snapshot(integrityOk = false)) }
        openSnapshotMenu()
        compose.onNodeWithText(label(R.string.mdbx_ui_snapshot_restore)).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    @Test
    fun compactSnapshotRowAndMenuKeepStructureAndChangesReachable() {
        show(title = R.string.mdbx_ui_object_snapshot) { Snapshots() }
        scrollToText(snapshot().name)
        compose.onNodeWithText(snapshot().name).performClick()
        openSnapshotMenu()
        compose.onNodeWithText(label(R.string.mdbx_ui_structure)).performClick()
        openSnapshotMenu()
        compose.onNodeWithText(label(R.string.mdbx_ui_changes)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("structure:snapshot-42", "structure:snapshot-42", "diff:commit-42"), events)
        }
    }

    @Test
    fun structureSupportsExpansionAndScrollingWithLargeText() {
        val folder = MdbxStructureNode("folder", null, "账户与服务", MdbxStructureNodeType.FOLDER,
            "/账户与服务", MdbxStructureNodeStatus.UNCHANGED, 25, "25")
        val nodes = listOf(folder) + (1..25).map { index ->
            MdbxStructureNode("entry-$index", "folder", "服务 ${index.toString().padStart(2, '0')}",
                MdbxStructureNodeType.ENTRY, "/账户与服务/服务 $index",
                if (index == 1) MdbxStructureNodeStatus.ADDED else MdbxStructureNodeStatus.UNCHANGED, 0, "login")
        }
        show(title = R.string.mdbx_ui_manager_snapshot_details, dark = true, fontScale = 1.5f) {
            SnapshotStructurePreviewPage(MdbxStructurePreview("snapshot-42", "备份", nodes, nodes, 25, 25), false)
        }
        compose.onNodeWithText(label(R.string.mdbx_ui_action_created)).assertIsDisplayed()
        capture("mdbx-structure-dark-large-text.png")
        compose.onNodeWithText("账户与服务").performClick()
        compose.onNodeWithText("服务 01").assertDoesNotExist()
        compose.onNodeWithText("账户与服务").performClick()
        val verticalScroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        // Exercise the user's vertical gesture through the horizontally scrollable tree.
        // performScrollTo() targets the nearest (horizontal) scroll container here.
        repeat(8) {
            if (runCatching { compose.onNodeWithText("服务 25").assertIsDisplayed() }.isFailure) {
                verticalScroll.performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
        }
        compose.onNodeWithText("服务 25").assertIsDisplayed()
        assertTrue(verticalScroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        capture("mdbx-structure-scrolled-dark-large-text.png")
        assertEquals(1.5f, compose.onNodeWithText("服务 25").fetchSemanticsNode().layoutInfo.density.fontScale, 0.001f)
    }

    @Test
    fun historyOpensTheSelectedCommitWithoutExposingIdsInTheList() {
        val delta = MdbxDeltaSummary(
            commitId = "private-commit-42", deviceId = "private-device-42", localSeq = 3,
            commitKind = "update", changeScope = "entry", changedObjectIds = "entry-42",
            changedObjectPreview = "个人邮箱", changedFieldSummary = "title", parentCount = 1,
            createdAt = "2026-09-14T04:00:00Z",
            changes = listOf(MdbxCommitChangeSummary("entry", "entry-42", "update", listOf("title")))
        )
        val title = delta.toHistoryPresentation(StringResolver { id, arguments -> chinese.getString(id, *arguments) }).title
        show(title = R.string.mdbx_ui_manager_history_title) {
            MdbxCommitHistoryPage(MdbxViewModel.MdbxDeltaDialogState.Visible(42, database.name, deltas = listOf(delta)),
                onShowDiff = { events += it }, onRevert = { events += "revert:$it" })
        }
        compose.onNodeWithText(delta.commitId).assertDoesNotExist()
        compose.onNodeWithText(delta.deviceId).assertDoesNotExist()
        capture("mdbx-history-light.png")
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle { assertEquals(listOf(delta.commitId), events) }
    }

    @Test
    fun maintenanceRetainsActionsAndCollapsesAdvancedMetrics() {
        show(title = R.string.mdbx_ui_manager_maintenance_title) {
            MdbxMaintenancePage(database, diagnostics, true, true,
                onRefreshDiagnostics = { events += "refresh" }, onSync = { events += "sync" },
                onFlushPendingUpload = { events += "upload" })
        }
        compose.onNodeWithText(label(R.string.mdbx_ui_indexed_objects)).assertDoesNotExist()
        capture("mdbx-maintenance-light.png")
        clickLabel(R.string.refresh)
        compose.onNode(hasClickAction() and hasText(label(R.string.mdbx_sync_status_label))).performClick()
        clickLabel(R.string.mdbx_ui_upload_pending)
        clickLabel(R.string.mdbx_ui_advanced_details)
        scrollToText(label(R.string.mdbx_ui_indexed_objects))
        compose.onNodeWithText(label(R.string.mdbx_ui_indexed_objects)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("refresh", "sync", "upload"), events) }
    }

    @Test
    fun healthPageKeepsRefreshAndMaintenanceNavigation() {
        show(title = R.string.mdbx_ui_manager_health_title, dark = true) {
            MdbxHealthDetailPage(database, diagnostics,
                onRefreshDiagnostics = { events += "health-refresh" },
                onOpenMaintenance = { events += "maintenance" }, onOpenSnapshots = {},
                onOpenCommitHistory = {}, onOpenAttachments = {})
        }
        capture("mdbx-health-dark.png")
        clickLabel(R.string.mdbx_ui_recheck)
        clickLabel(R.string.mdbx_ui_maintenance)
        compose.runOnIdle { assertEquals(listOf("health-refresh", "maintenance"), events) }
    }

    @Test
    fun attachmentsKeepIntegrityRefresh() {
        show(title = R.string.mdbx_ui_manager_attachments_title, dark = true) {
            MdbxAttachmentDetailPage(database, diagnostics, onRefreshDiagnostics = { events += "attachments-refresh" })
        }
        capture("mdbx-attachments-dark.png")
        clickLabel(R.string.mdbx_ui_attachments_recheck)
        compose.runOnIdle { assertEquals(listOf("attachments-refresh"), events) }
    }

    @Test
    fun unlockMethodSelectionRetainsAccessibleSelectedState() {
        val selected = mutableStateOf(MdbxUnlockMethod.MASTER_PASSWORD)
        show(title = R.string.mdbx_create_vault_title) {
            MdbxUnlockMethodSection(selected.value, onUnlockMethodChange = { selected.value = it }, includeDeviceKey = true)
        }
        compose.onNodeWithText(label(R.string.mdbx_ui_unlock_method)).performClick()
        compose.onNode(hasText(label(R.string.master_password)) and isSelectable()).assertIsSelected()
        compose.onNode(hasText(label(R.string.local_keepass_key_file)) and isSelectable()).performClick()
        compose.runOnIdle { assertEquals(MdbxUnlockMethod.KEY_FILE, selected.value) }
        compose.onNodeWithText(label(R.string.mdbx_ui_unlock_method)).performClick()
        compose.onNode(hasText(label(R.string.local_keepass_key_file)) and isSelectable()).assertIsSelected()
    }

    @Test
    fun heldFeedbackStaysSoftAndRoundedInLightTheme() = verifyHeldFeedback(dark = false)

    @Test
    fun heldFeedbackStaysSoftAndRoundedInDarkTheme() = verifyHeldFeedback(dark = true)

    private fun verifyHeldFeedback(dark: Boolean) {
        val page = mutableStateOf(0)
        val unlockMethod = mutableStateOf(MdbxUnlockMethod.MASTER_PASSWORD)
        val theme = if (dark) "dark" else "light"
        val diff = MdbxCommitDiff(
            commitId = "commit-42", objectType = "entry", objectId = "entry-42",
            displayTitle = "工作邮箱", storagePath = null, previousTitle = "个人邮箱", currentTitle = "工作邮箱",
            previousPayloadPreview = null, currentPayloadPreview = null,
            previousDeleted = false, currentDeleted = false, changedFields = listOf("title"),
            createdAt = "2026-09-14T04:00:00Z"
        )
        show(title = null, dark = dark) {
            when (page.value) {
                0 -> Snapshots()
                1 -> MdbxManagerHubPage(2, 1, 1,
                    onOpenLocal = { events += "local" }, onOpenWebDav = { events += "webdav" },
                    onOpenOneDrive = { events += "onedrive" })
                2 -> Overview()
                3 -> MdbxCommitHistoryPage(
                    MdbxViewModel.MdbxDeltaDialogState.Visible(42, database.name,
                        selectedDiffCommitId = diff.commitId, diffItems = listOf(diff)),
                    onShowDiff = { events += "diff" }, onRevert = { events += "revert" })
                4 -> Box(Modifier.padding(16.dp)) {
                    MdbxUnlockMethodSection(unlockMethod.value, onUnlockMethodChange = { unlockMethod.value = it })
                }
                5 -> Box(Modifier.padding(16.dp)) {
                    MdbxEngineTypeSection(MdbxEngineType.RUST_MDBX2, onEngineChange = {}, remote = false)
                }
                6 -> MdbxSourceManagementPage(
                    MdbxManagerSource.LOCAL, listOf(database), emptyMap(), emptyMap(),
                    onCreateClick = { events += "create" }, onOpenClick = { events += "open" },
                    onOpenDatabase = { events += "database" }
                )
            }
        }
        fun press(text: String, name: String, scroll: Boolean = true) {
            if (scroll) scrollToText(text)
            assertHeldFeedback(compose.onNode(hasClickAction() and hasText(text)), "mdbx-press-$name-$theme.png")
        }

        press(snapshot().name, "card")
        compose.runOnIdle { page.value = 1 }
        listOf(label(R.string.mdbx_ui_local_databases), "WebDAV", "OneDrive").forEachIndexed { index, title ->
            press(title, "group-$index")
        }
        compose.runOnIdle { page.value = 2 }
        press(label(R.string.advanced_options), "section-closed")
        clickLabel(R.string.advanced_options)
        press(label(R.string.advanced_options), "section-open")
        compose.runOnIdle { page.value = 3 }
        press("${label(R.string.mdbx_ui_action_modified)} 1", "history-group")
        press(label(R.string.passkey_detail_technical), "history-details")
        compose.runOnIdle { page.value = 4 }
        press(label(R.string.mdbx_ui_unlock_method), "unlock-header", scroll = false)
        compose.onNodeWithText(label(R.string.mdbx_ui_unlock_method)).performClick()
        assertHeldFeedback(compose.onNode(hasText(label(R.string.master_password)) and isSelectable()),
            "mdbx-press-unlock-option-$theme.png")
        compose.runOnIdle { page.value = 5 }
        press(label(R.string.mdbx_ui_database_options), "engine-header", scroll = false)
        compose.runOnIdle { page.value = 6 }
        press(label(R.string.attachment_open), "source-open", scroll = false)
        press(label(R.string.create_new), "source-create", scroll = false)
        compose.runOnIdle { assertTrue("Holding or cancelling must not trigger an action", events.isEmpty()) }
    }

    private fun assertHeldFeedback(node: SemanticsNodeInteraction, name: String) {
        node.assertIsDisplayed()
        val density = node.fetchSemanticsNode().layoutInfo.density.density
        // A preceding expand click can still be fading on Android's render clock.
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        SystemClock.sleep(500)
        val before = node.captureToImage().asAndroidBitmap()
        node.performTouchInput { down(center) }
        try {
            // Scroll containers defer the press until their tap timeout has elapsed.
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            // Android renders Material ripples on the real render clock, not the Compose test clock.
            SystemClock.sleep(600)
            val held = node.captureToImage().asAndroidBitmap()
            try {
                capture(name)
                assertEquals(before.width, held.width)
                assertEquals(before.height, held.height)
                val original = IntArray(before.width * before.height)
                val pressed = IntArray(original.size)
                before.getPixels(original, 0, before.width, 0, 0, before.width, before.height)
                held.getPixels(pressed, 0, held.width, 0, 0, held.width, held.height)
                // Adjacent Settings rows now have 4dp corners. Sample only the outermost
                // 0.5dp square, which is outside both those arcs and the 24dp outer arcs.
                val corner = (0.5f * density).roundToInt().coerceAtLeast(1)
                var changed = 0
                var cornerDifference = 0
                var brightening = 0
                original.indices.forEach { index ->
                    val x = index % before.width
                    val y = index / before.width
                    val red = ((pressed[index] shr 16) and 255) - ((original[index] shr 16) and 255)
                    val green = ((pressed[index] shr 8) and 255) - ((original[index] shr 8) and 255)
                    val blue = (pressed[index] and 255) - (original[index] and 255)
                    val difference = maxOf(abs(red), abs(green), abs(blue))
                    if (difference > 2) changed++
                    if ((x < corner || x >= before.width - corner) &&
                        (y < corner || y >= before.height - corner)) {
                        cornerDifference = maxOf(cornerDifference, difference)
                    }
                    brightening = maxOf(brightening, red, green, blue)
                }
                assertTrue("$name: feedback must leave rounded corners clear", cornerDifference <= 2)
                assertTrue("$name: feedback must not flash a bright overlay", brightening <= 40)
                assertTrue("$name: a held control must retain visible feedback ($changed/${original.size})",
                    changed > original.size / 100)
            } finally {
                held.recycle()
            }
        } finally {
            node.performTouchInput { cancel() }
            before.recycle()
            SystemClock.sleep(250)
        }
    }

    private fun capture(name: String) {
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
