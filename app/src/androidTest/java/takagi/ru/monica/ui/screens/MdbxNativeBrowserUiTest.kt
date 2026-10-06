package takagi.ru.monica.ui.screens

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import takagi.ru.monica.R
import takagi.ru.monica.repository.*

class MdbxNativeBrowserUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun ready() {
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }
    private fun folder(id: String, parent: String? = null) = MdbxStructureNode(id, parent, "Folder $id",
        MdbxStructureNodeType.FOLDER, id, MdbxStructureNodeStatus.UNCHANGED, 0, "")
    private fun entry(id: String, parent: String? = "a") = folder(id, parent).copy(name = "Entry $id",
        type = MdbxStructureNodeType.ENTRY, metadata = "login")
    private val nodes = listOf(folder("a"), folder("b", "a"), entry("nested", "b")) + (0..240).map { entry(it.toString()) }
    private fun scroll(pane: String, id: String) = compose.onNodeWithTag("mdbx-list-$pane").performScrollToNode(hasTestTag("mdbx-row-$pane-$id"))
    private fun screenshot(name: String) {
        compose.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(250)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.filesDir, "mdbx-native-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun nestedNavigationAndLongListRemainScrollableAndRestorePath() {
        var opened = ""
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { Surface { MdbxFolderBrowser(nodes, "Synthetic vault", onEntry = { opened = it.id }) } } }
        compose.onNodeWithTag("mdbx-row-native-a").performClick()
        scroll("native", "240")
        compose.onNodeWithTag("mdbx-row-native-240").assertIsDisplayed().performClick()
        assertEquals("240", opened)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("mdbx-row-native-240").assertIsDisplayed()
        scroll("native", "b")
        compose.onNodeWithTag("mdbx-row-native-b").performClick()
        compose.onNodeWithTag("mdbx-row-native-nested").assertIsDisplayed()
        screenshot("folder")
    }
    @Test fun searchFindsDescendantsAndUnknownTypesWithoutExposingPasswords() {
        val data = nodes + entry("unknown", "b").copy(metadata = "com.example.future")
        compose.setContent { MaterialTheme { MdbxFolderBrowser(data, "Synthetic vault", onEntry = {}) } }
        compose.onNodeWithContentDescription(context.getString(R.string.search)).performClick()
        compose.onNodeWithTag("mdbx-search-native").performTextReplacement("com.example.future")
        compose.onNodeWithTag("mdbx-row-native-unknown").assertIsDisplayed()
        compose.onNodeWithText("Synthetic vault / Folder a / Folder b").assertIsDisplayed()
        compose.onNodeWithTag("mdbx-row-native-a").assertDoesNotExist()
    }
    @Test fun snapshotComparisonHasIndependentFoldersAndReadOnlyDetails() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val preview = MdbxStructurePreview("snapshot", "Synthetic snapshot", nodes, nodes, 242, 242)
        compose.setContent { MaterialTheme { Surface { SnapshotStructurePreviewPage(preview, true, Modifier.fillMaxSize().padding(12.dp)) } } }
        compose.onNodeWithTag("mdbx-row-current-a").performClick()
        scroll("current", "200")
        compose.onNodeWithTag("mdbx-row-snapshot-a").assertIsDisplayed()
        compose.onNodeWithTag("mdbx-row-snapshot-a").performClick()
        compose.onNodeWithTag("mdbx-row-snapshot-b").performClick()
        compose.onNodeWithTag("mdbx-row-current-200").assertIsDisplayed()
        compose.onNodeWithTag("mdbx-row-snapshot-nested").assertIsDisplayed()
        screenshot("compare")
        compose.onNodeWithTag("mdbx-row-snapshot-nested").performClick()
        compose.onNodeWithText(context.getString(R.string.mdbx_native_snapshot_readonly)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.rename_category)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.keepass_native_create_group)).assertDoesNotExist()
    }
    @Test fun darkLargeTextKeepsGroupedRowsAndFolderCreationReachable() {
        var parent: String? = "not-called"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                MaterialTheme(colorScheme = darkColorScheme()) { Surface {
                    MdbxFolderBrowser(listOf(folder("a"), entry("Root entry with a very long display name", null)), "Synthetic vault",
                        Modifier.fillMaxSize().padding(12.dp), onEntry = {}, onRename = {}, onCreateFolder = { parent = it })
                } }
            }
        }
        compose.onNodeWithText(context.getString(R.string.keepass_native_create_group)).assertIsDisplayed().performClick()
        assertNull(parent)
        screenshot("dark-large")
    }
    @Test fun originalFieldsRemainHiddenUntilIndividuallyRevealed() {
        var payload by mutableStateOf<String?>("""{"password":"synthetic secret","unknown":{"enabled":false,"n":null}}""")
        compose.setContent { MaterialTheme { MdbxUnknownEntryContent("Original", "login", payload, false, {},
            notice = context.getString(R.string.mdbx_native_readonly)) } }
        compose.onNodeWithText("synthetic secret").assertDoesNotExist()
        compose.onNodeWithTag("mdbx-field-toggle-0").performClick()
        compose.onNodeWithText("synthetic secret").assertIsDisplayed()
        compose.runOnIdle { payload = null }
        compose.onNodeWithText("synthetic secret").assertDoesNotExist()
    }

    @Test fun actualManagerCreatesFolderRenamesEntryAndRefreshesRoomProjection() = runBlocking<Unit> {
        check(InstrumentationRegistry.getArguments().getString("autofillIsolatedUser") == "true")
        val room = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
        val security = takagi.ru.monica.security.SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
        val password = "Synthetic manager test password 316"
        val file = repository.createInitializedVaultFile(takagi.ru.monica.data.MdbxTigaMode.SKY, password)
        val id = room.localMdbxDatabaseDao().insertDatabase(takagi.ru.monica.data.LocalMdbxDatabase(
            name = "Synthetic manager", filePath = file.absolutePath, workingCopyPath = file.absolutePath,
            engineType = takagi.ru.monica.data.MdbxEngineType.RUST_MDBX2.name,
            encryptedPassword = security.encryptData(password)))
        val vm = takagi.ru.monica.viewmodel.MdbxViewModel(context.applicationContext as android.app.Application,
            room.localMdbxDatabaseDao(), room.mdbxRemoteSourceDao(), room.passwordEntryDao(), room.secureItemDao(),
            room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), security)
        val wasUnlocked = takagi.ru.monica.security.SessionManager.isUnlocked.value
        var show by mutableStateOf(true)
        takagi.ru.monica.security.SessionManager.markUnlocked()
        try {
            repository.upsertPassword(takagi.ru.monica.data.PasswordEntry(id = 9_200_000_000L + (System.nanoTime() % 100000),
                title = "Synthetic login", username = "synthetic account", password = security.encryptData("synthetic original secret"),
                website = "https://example.invalid", mdbxDatabaseId = id))
            val original = repository.nativeBrowser(id).objects.values.single()
            val before = repository.nativeObject(id, original.id)
            compose.setContent { if (show) MaterialTheme { MdbxNativeManagerScreen(id, "Synthetic manager", vm, onBack = {}) } }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("mdbx-row-native-${original.id}").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(context.getString(R.string.keepass_native_create_group)).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Synthetic folder")
            compose.onNodeWithText(context.getString(R.string.save)).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Synthetic folder").fetchSemanticsNodes().size == 1 }
            assertEquals("Synthetic folder", repository.listFolders(id).single().name)
            val folderId = repository.listFolders(id).single().folderId
            compose.onNodeWithTag("mdbx-row-native-$folderId").performClick()
            compose.onNodeWithTag("mdbx-row-native-${original.id}").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.onNodeWithTag("mdbx-row-native-${original.id}").assertIsDisplayed()
            compose.onNode(hasContentDescription(context.getString(R.string.more_options)) and hasAnyAncestor(hasTestTag("mdbx-row-native-${original.id}"))).performClick()
            compose.onNodeWithText(context.getString(R.string.rename_category)).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Renamed synthetic login")
            compose.onNodeWithText(context.getString(R.string.save)).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Renamed synthetic login").fetchSemanticsNodes().size == 1 }
            assertEquals("Renamed synthetic login", room.passwordEntryDao().getByMdbxDatabaseIdSync(id).single().title)
            val after = repository.nativeObject(id, original.id)
            assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(before.payload), kotlinx.serialization.json.Json.parseToJsonElement(after.payload))
            screenshot("manager")
            compose.onNodeWithTag("mdbx-row-native-${original.id}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("mdbx-unknown-detail").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("synthetic original secret").assertDoesNotExist()
        } finally {
            compose.runOnIdle { show = false }
            vm.viewModelScope.cancel()
            room.passwordEntryDao().deleteAllByMdbxDatabaseId(id)
            room.localMdbxDatabaseDao().deleteDatabaseById(id)
            repository.deleteOwnedVaultFile(file)
            if (!wasUnlocked) takagi.ru.monica.security.SessionManager.markLocked()
        }
    }
}
