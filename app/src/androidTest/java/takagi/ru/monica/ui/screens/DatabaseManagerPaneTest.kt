package takagi.ru.monica.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.repository.*

class DatabaseManagerPaneTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun wake() { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true) }
    private val database = ImportDestination(ImportDestinationKind.MDBX, 123)
    private val nodes = listOf(row("folder", null, true)) + (0..120).map { row("entry%03d".format(it), "folder") }
    private fun row(id: String, parent: String? = null, folder: Boolean = false) = MdbxStructureNode(id, parent, id,
        if (folder) MdbxStructureNodeType.FOLDER else MdbxStructureNodeType.ENTRY, id, MdbxStructureNodeStatus.UNCHANGED, 0, "login")
    private fun pane() = DatabaseManagerPane(database.key).apply { snapshot = DatabaseManagerSnapshot(DatabaseManagerLocation(database), "Synthetic vault", nodes) }
    @Test fun actualScreenCopiesSelectedEntryIntoTheOppositeDatabaseFolder() = runBlocking<Unit> {
        val fixture = TransferFixture()
        val app = fixture.context.applicationContext as android.app.Application
        val db = fixture.db
        val mdbxVm = takagi.ru.monica.viewmodel.MdbxViewModel(app, db.localMdbxDatabaseDao(), db.mdbxRemoteSourceDao(),
            db.passwordEntryDao(), db.secureItemDao(), db.passkeyDao(), db.attachmentDao(), db.customFieldDao(), fixture.security)
        val keepassVm = takagi.ru.monica.viewmodel.LocalKeePassViewModel(app, db.localKeePassDatabaseDao(), fixture.security)
        var show by mutableStateOf(true)
        try {
            val repo = DatabaseManagerRepository(fixture.context)
            val source = fixture.mdbx(); val target = fixture.keepass()
            fixture.passwords.insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(title = fixture.prefix,
                website = "", username = "synthetic", password = fixture.security.encryptData("never show this secret"), mdbxDatabaseId = source.databaseId))
            val id = repo.mdbx.nativeBrowser(source.databaseId).objects.keys.single()
            val folder = repo.createFolder(DatabaseManagerLocation(target), "Target folder")
            compose.setContent { if (show) MaterialTheme { DatabaseManagerScreen(source.key, mdbxVm, keepassVm, {}) } }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("manager-row-0-$id").fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithTag("manager-pane-1").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithContentDescription(fixture.context.getString(takagi.ru.monica.R.string.manager_dual)).performClick()
            }
            compose.onNodeWithTag("manager-row-0-$id").performTouchInput { down(Offset(30f, height / 2f)) }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(900)
            compose.waitUntil(3000) { compose.onAllNodesWithText(fixture.context.getString(takagi.ru.monica.R.string.copy)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("manager-row-0-$id").performTouchInput { up() }
            compose.onNodeWithTag("manager-list-1").performScrollToKey(target.key)
            compose.onNodeWithTag("manager-store-1-${target.key}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("manager-row-1-$folder").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("manager-row-1-$folder").performClick()
            compose.onNodeWithText("never show this secret").assertDoesNotExist()
            screenshot("screen-transfer")
            val copy = fixture.context.getString(takagi.ru.monica.R.string.copy)
            compose.onNodeWithText(copy).assertIsEnabled().performClick()
            compose.onNode(hasText(copy) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(fixture.context.getString(takagi.ru.monica.R.string.manager_result)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(fixture.prefix, repo.keepass.openNativeBrowser(target.databaseId).getOrThrow().entries.single().title)
            assertTrue(repo.mdbx.nativeBrowser(source.databaseId).objects.containsKey(id))
        } finally {
            compose.runOnIdle { show = false }
            mdbxVm.viewModelScope.cancel(); keepassVm.viewModelScope.cancel()
            fixture.close()
        }
    }
    @Test fun horizontalScrollingDoesNotMoveTheOppositePane() {
        val left = pane(); val right = pane()
        left.snapshot = DatabaseManagerSnapshot(DatabaseManagerLocation(database), "Long names", listOf(row("long").copy(name = "A long original entry title that needs independent horizontal scrolling to read")))
        compose.setContent { MaterialTheme { Surface { Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DatabaseManagerPaneContent(left, emptyList(), "left", true, true, true, {}, {}, {}, Modifier.weight(1f))
            DatabaseManagerPaneContent(right, emptyList(), "right", true, false, true, {}, {}, {}, Modifier.weight(1f))
        } } } }
        val initialLeft = compose.onNodeWithTag("manager-row-left-long").getUnclippedBoundsInRoot().left
        val initialRight = compose.onNodeWithTag("manager-row-right-folder").getUnclippedBoundsInRoot().left
        compose.onNodeWithTag("manager-list-left").performTouchInput { swipeLeft(durationMillis = 250) }
        assertTrue(compose.onNodeWithTag("manager-row-left-long").getUnclippedBoundsInRoot().left < initialLeft)
        assertEquals(initialRight, compose.onNodeWithTag("manager-row-right-folder").getUnclippedBoundsInRoot().left)
        screenshot("horizontal")
    }

    @Test fun landscapeDarkLargeTextKeepsBothPathsIndependent() {
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        val left = pane().apply { selected = setOf("folder") }; val right = pane()
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, 1.4f)) {
                MaterialTheme(colorScheme = darkColorScheme()) { Surface { Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DatabaseManagerPaneContent(left, emptyList(), "left", true, false, true, {}, {}, {}, Modifier.weight(1f))
                    DatabaseManagerPaneContent(right, emptyList(), "right", true, true, true, {}, {}, {}, Modifier.weight(1f))
                } } }
            }
        }
        compose.onNodeWithTag("manager-row-right-folder").performClick()
        compose.onNodeWithTag("manager-list-right").performScrollToKey("entry060")
        compose.onNodeWithTag("manager-row-right-entry060").assertIsDisplayed()
        compose.onNodeWithTag("manager-row-left-folder").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf("folder"), left.selected); assertNull(left.folderId) }
        screenshot("landscape-dark-large")
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.filesDir, "database-manager-test").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    @Test fun dualPanesNavigateAndScrollIndependently() {
        val left = pane(); val right = pane()
        compose.setContent { MaterialTheme { Surface { Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DatabaseManagerPaneContent(left, emptyList(), "left", true, true, true, {}, {}, {}, Modifier.weight(1f))
            DatabaseManagerPaneContent(right, emptyList(), "right", true, false, true, {}, {}, {}, Modifier.weight(1f))
        } } } }
        compose.onNodeWithTag("manager-row-left-folder").performClick()
        compose.onNodeWithTag("manager-list-left").performScrollToKey("entry100")
        compose.onNodeWithTag("manager-row-left-entry100").assertIsDisplayed()
        compose.onNodeWithTag("manager-row-right-folder").assertIsDisplayed()
        compose.runOnIdle { assertEquals("folder", left.folderId); assertNull(right.folderId) }
        screenshot("independent")
    }
    @Test fun longPressAndVerticalDragSelectRangeWithoutMovingOtherPane() {
        val left = pane().apply { folderId = "folder" }; val right = pane()
        compose.setContent { MaterialTheme { Surface { Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DatabaseManagerPaneContent(left, emptyList(), "left", true, true, true, {}, {}, {}, Modifier.weight(1f))
            DatabaseManagerPaneContent(right, emptyList(), "right", true, false, true, {}, {}, {}, Modifier.weight(1f))
        } } } }
        compose.onNodeWithTag("manager-row-left-entry000").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(900)
        compose.waitUntil(3000) { left.selected.isNotEmpty() }
        compose.onNodeWithTag("manager-row-left-entry000").performTouchInput {
            moveTo(center + Offset(0f, 280f), delayMillis = 400); up()
        }
        compose.runOnIdle {
            assertTrue("Long-press drag must select a range; count=${left.selected.size}", left.selected.size >= 2)
            assertTrue(right.selected.isEmpty()); assertNull(right.folderId)
        }
        screenshot("selection")
    }
    @Test fun paneRestorationRetainsItsOwnFolderSearchAndSelection() {
        lateinit var restored: DatabaseManagerPane
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            val state = rememberSaveable(saver = DatabaseManagerPane.Saver) { pane() }
            restored = state
            LaunchedEffect(state) { state.snapshot = DatabaseManagerSnapshot(DatabaseManagerLocation(database), "Synthetic vault", nodes) }
            MaterialTheme { Surface { DatabaseManagerPaneContent(state, emptyList(), "left", true, true, true, {}, {}, {}, Modifier.fillMaxSize()) } }
        }
        compose.runOnIdle { restored.folderId = "folder"; restored.query = "entry"; restored.search = true; restored.selected = setOf("entry001", "entry002") }
        restorer.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals("folder", restored.folderId); assertEquals("entry", restored.query); assertEquals(2, restored.selected.size) }
        compose.onNodeWithTag("manager-search-left").assertIsDisplayed()
    }
}
