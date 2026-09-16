package takagi.ru.monica.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.repository.Mdbx2VaultSessionExecutor
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.ui.vaultv2.VaultV2ItemCard
import takagi.ru.monica.ui.vaultv2.buildVaultV2NativeTokenItems
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NativeApiTokenUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun detailShowsExistingMetadataWhileStorageIsBusyAndClearsOnLock() = runBlocking<Unit> {
        val fixture = Fixture()
        val visible = mutableStateOf(true)
        val wasUnlocked = SessionManager.isUnlocked.value
        val wasForeground = Mdbx2NativeReadSessions.isForeground
        val releaseStorage = CompletableDeferred<Unit>()
        var storageJob: Job? = null
        SessionManager.markUnlocked()
        Mdbx2NativeReadSessions.updateForeground(true)
        try {
            val databaseId = fixture.createDatabase("Metadata first database")
            val folder = fixture.repository.createFolder(databaseId, "Automation metadata", null)
            val secret = "synthetic-deferred-disclosure-token"
            val summary = fixture.repository.saveNativeApiToken(databaseId, null, "CLI immediate detail",
                """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.test/","token":"$secret"}""",
                folder.folderId)
            compose.setContent { if (visible.value) MonicaTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        NativeApiTokensScreen(fixture.model, databaseId, onNavigateBack = {},
                            onOpen = { _, _ -> nav.navigate("detail") }, onCreate = {}, onManageDatabases = {})
                    }
                    composable("detail") {
                        ApiTokenDetailScreen(fixture.model, databaseId, summary.entryId,
                            onNavigateBack = { nav.popBackStack() }, onEdit = {})
                    }
                }
            } }
            awaitText("CLI immediate detail")
            // Hold the real per-vault executor lock. The detail read cannot finish or disclose a
            // payload; this verifies actual navigation uses metadata already available to the list.
            val storageEntered = CompletableDeferred<Unit>()
            storageJob = launch {
                fixture.executor.withVault(databaseId) { _, _ ->
                    storageEntered.complete(Unit)
                    releaseStorage.await()
                }
            }
            storageEntered.await()
            compose.onNodeWithText("CLI immediate detail").performClick()
            awaitTag("api_token_summary")
            compose.onNodeWithTag("api_token_summary").assertIsDisplayed()
            compose.onNodeWithText("Metadata first database").assertIsDisplayed()
            compose.onNodeWithText("Automation metadata").assertIsDisplayed()
            compose.onNodeWithTag("api_token_loading").assertIsDisplayed()
            compose.onNodeWithTag("api_token_edit").assertDoesNotExist()
            compose.onNodeWithText(secret).assertDoesNotExist()

            releaseStorage.complete(Unit)
            storageJob.join()
            awaitTag("api_token_edit")
            compose.onNodeWithTag("api_token_loading").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.show)).performClick()
            compose.onNodeWithText(secret).assertIsDisplayed()

            SessionManager.markLocked()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("api_token_summary").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText(secret).assertDoesNotExist()
            compose.onNodeWithTag("api_token_edit").assertDoesNotExist()
        } finally {
            releaseStorage.complete(Unit)
            storageJob?.join()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            Mdbx2NativeReadSessions.clear()
            fixture.close()
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    @Test fun detailNavigationAfterFolderBrowsingPerformance() = runBlocking<Unit> {
        assumeTrue("Manual UI benchmark; pass -e apiTokenUiPerf true",
            InstrumentationRegistry.getArguments().getString("apiTokenUiPerf") == "true")
        val fixture = Fixture()
        val visible = mutableStateOf(true)
        val wasUnlocked = SessionManager.isUnlocked.value
        val wasForeground = Mdbx2NativeReadSessions.isForeground
        SessionManager.markUnlocked()
        Mdbx2NativeReadSessions.updateForeground(true)
        try {
            val databaseId = fixture.createDatabase("CLI navigation performance", MdbxTigaMode.MULTI)
            val summary = fixture.repository.saveNativeApiToken(databaseId, null, "CLI navigation token",
                """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.test/","token":"synthetic-ui-performance-token","extension":{"keep":true}}""")
            compose.setContent { if (visible.value) MonicaTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        NativeApiTokensScreen(fixture.model, databaseId, onNavigateBack = {},
                            onOpen = { _, _ -> nav.navigate("detail") }, onCreate = {}, onManageDatabases = {})
                    }
                    composable("detail") {
                        ApiTokenDetailScreen(fixture.model, databaseId, summary.entryId,
                            onNavigateBack = { nav.popBackStack() }, onEdit = {})
                    }
                }
            } }
            val samples = mutableListOf<Long>()
            repeat(3) { index ->
                awaitText("CLI navigation token")
                fixture.repository.listFolders(databaseId)
                val start = SystemClock.elapsedRealtime()
                compose.onNodeWithText("CLI navigation token").performClick()
                awaitTag("api_token_edit")
                samples += SystemClock.elapsedRealtime() - start
                compose.onNodeWithTag("api_token_edit").assertIsDisplayed()
                if (index < 2) compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            }
            Log.i("ApiTokenPerformance", "CLI_UI_PERF tap_to_detail_ms=$samples")
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            Mdbx2NativeReadSessions.clear()
            fixture.close()
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (!wasUnlocked) SessionManager.markLocked()
        }
    }

    @Test fun mainPagePreloadKeepsTheReaderUsedByTokenDetail() = runBlocking<Unit> {
        val fixture = Fixture()
        val visible = mutableStateOf(true)
        val wasUnlocked = SessionManager.isUnlocked.value
        val wasForeground = Mdbx2NativeReadSessions.isForeground
        val activePrefs = context.getSharedPreferences("mdbx_active_vault", 0)
        val activeKey = "last_active_mdbx_database_id"
        val previousActive = if (activePrefs.contains(activeKey)) activePrefs.getLong(activeKey, -1L) else null
        SessionManager.markUnlocked()
        Mdbx2NativeReadSessions.updateForeground(true)
        try {
            val databaseId = fixture.createDatabase("Main page preload regression", MdbxTigaMode.MULTI)
            val summary = fixture.repository.saveNativeApiToken(databaseId, null, "CLI preloaded token",
                """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://example.test/","token":"synthetic-preload-token"}""")
            compose.setContent { if (visible.value) MonicaTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        NativeApiTokensScreen(fixture.model, databaseId, onNavigateBack = {},
                            onOpen = { _, _ -> nav.navigate("detail") }, onCreate = {}, onManageDatabases = {})
                    }
                    composable("detail") {
                        ApiTokenDetailScreen(fixture.model, databaseId, summary.entryId,
                            onNavigateBack = { nav.popBackStack() }, onEdit = {})
                    }
                }
            } }
            awaitText("CLI preloaded token")
            withTimeout(30_000) { fixture.model.nativeApiTokenList.state.first { it.loading.isEmpty() } }
            val sessionBefore = fixture.executor.withNativeReadVault(databaseId) { _, vault ->
                checkNotNull(vault.activeSessionInfo()).sessionId
            }
            // These are the actual background operations started when the main password/vault
            // page selects an MDBX database, which the standalone token-list benchmark omitted.
            val preloadStart = SystemClock.elapsedRealtime()
            compose.runOnIdle { fixture.model.activateMdbxDatabase(databaseId) }
            withTimeout(30_000) { fixture.model.vaultDiagnostics.first { databaseId in it } }
            val preloadMillis = SystemClock.elapsedRealtime() - preloadStart
            val tapStart = SystemClock.elapsedRealtime()
            compose.onNodeWithText("CLI preloaded token").performClick()
            awaitTag("api_token_edit")
            val detailMillis = SystemClock.elapsedRealtime() - tapStart
            val sessionAfter = fixture.executor.withNativeReadVault(databaseId) { _, vault ->
                checkNotNull(vault.activeSessionInfo()).sessionId
            }
            Log.i("ApiTokenPerformance", "CLI_MAIN_PRELOAD preload_ms=$preloadMillis " +
                "tap_to_detail_ms=$detailMillis reused=${sessionBefore == sessionAfter}")
            assertEquals("Read-only main-page preload must not force token detail to derive the vault key again",
                sessionBefore, sessionAfter)
            compose.onNodeWithContentDescription(context.getString(R.string.show)).performClick()
            compose.onNodeWithText("synthetic-preload-token").assertIsDisplayed()
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            fixture.close()
            activePrefs.edit().apply {
                if (previousActive == null) remove(activeKey) else putLong(activeKey, previousActive)
            }.commit()
            Mdbx2NativeReadSessions.clear()
            Mdbx2NativeReadSessions.updateForeground(wasForeground)
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    @Test fun quickFilterAndNativeRowOpenTheCorrectObject() {
        val summary = NativeApiTokenSummary(91, "native-id", "folder-id", "CLI category", "gitlab-work")
        var opened: Pair<Long?, String?>? = null
        compose.setContent {
            var only by remember { mutableStateOf(false) }
            val state = NativeTokenListUi(listOf(summary), true, only, false, false,
                { only = !only }, { db, id -> opened = db to id })
            MonicaTheme { LazyColumn {
                item { NativeTokenFilterChip(state) }
                item {
                    val item = remember(summary) { buildVaultV2NativeTokenItems(listOf(summary), "API token").single() }
                    VaultV2ItemCard(item, null, AppSettings(), remember { SecurityManager(context) }, false,
                        GroupedItemDefaults.shape(0, 1),
                        onClick = { state.openDisplayEntry(checkNotNull(item.passwordEntry)) }, onLongClick = {})
                }
            } }
        }
        compose.onAllNodesWithText(context.getString(R.string.entry_type_api_token))[0].performClick().assertIsSelected()
        compose.onNodeWithText("gitlab-work").performClick()
        compose.runOnIdle { assertEquals(91L to "native-id", opened) }
    }

    @Test fun editAndReturnKeepNativeIdentityExtensionsAndTheVisibleList() = runBlocking {
        val fixture = Fixture()
        val visible = mutableStateOf(true)
        try {
            val databaseId = fixture.createDatabase("CLI integration demo")
            val folder = fixture.repository.createFolder(databaseId, "Development", null)
            val payload = """{"schema":"monica.gateway.credential.v1","provider":"gitlab","api_base":"https://gitlab.example.test/api/v4/","note":"Original CLI context","token":"synthetic-ui-token-123456","extension":{"purpose":"demo"}}"""
            val summary = fixture.repository.saveNativeApiToken(databaseId, null, "gitlab-work", payload, folder.folderId, isFavorite = true)
            val returnCounts = mutableListOf<Int>()
            var recordingReturn = false
            compose.setContent { if (visible.value) MonicaTheme(darkTheme = true) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        val list = rememberNativeTokenList(fixture.model, CategoryFilter.MdbxDatabase(databaseId), "",
                            onlyTokens = true, onToggle = {}, onOpen = { _, _ -> nav.navigate("detail") })
                        SideEffect { if (recordingReturn) returnCounts += list.entries.size }
                        NativeApiTokensScreen(fixture.model, databaseId, onNavigateBack = {},
                            onOpen = { _, _ -> nav.navigate("detail") }, onCreate = {}, onManageDatabases = {})
                    }
                    composable("detail") {
                        ApiTokenDetailScreen(fixture.model, databaseId, summary.entryId,
                            onNavigateBack = { recordingReturn = true; nav.popBackStack() },
                            onEdit = { nav.navigate("edit") })
                    }
                    composable("edit") {
                        AddEditApiTokenScreen(fixture.model, databaseId, summary.entryId,
                            onNavigateBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() },
                            onSwitchType = { _, _, _ -> }, onManageDatabases = {})
                    }
                }
            } }
            awaitText("gitlab-work")
            compose.onNodeWithText("gitlab-work").performClick()
            awaitTag("api_token_edit")
            screenshot("native-api-token-detail.png")
            compose.onNodeWithTag("api_token_edit").performClick()
            awaitTag("api_token_note")
            compose.onNodeWithTag("api_token_favorite").assertIsDisplayed().assertIsOn().performClick().assertIsOff()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.onNodeWithText(context.getString(R.string.api_token_discard_message)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            compose.onNodeWithTag("api_token_note").performScrollTo().performClick()
                .performTextReplacement("Edited through Android")
            compose.onNodeWithTag("api_token_save").assertIsEnabled().assertIsDisplayed().performClick()
            awaitTag("api_token_edit")
            val updated = fixture.repository.readNativeApiToken(databaseId, summary.entryId)
            assertEquals(summary.entryId, updated.summary.entryId)
            assertEquals(folder.folderId, updated.summary.collectionId)
            assertFalse(updated.summary.isFavorite)
            val fields = ApiTokenPayload.decode(updated.payload)
            assertEquals("Original CLI context", ApiTokenPayload.text(fields, "note"))
            assertEquals("Edited through Android", ApiTokenMetadata.notes(checkNotNull(updated.extras).payload, ""))
            assertNotNull(fields?.get("extension"))
            assertTrue(fixture.room.passwordEntryDao().getByMdbxDatabaseIdSync(databaseId).isEmpty())
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            awaitTag("vault_item_password:${summary.displayId}")
            compose.runOnIdle {
                assertTrue(returnCounts.isNotEmpty())
                assertTrue("Returning must not replace the token row with an empty list", returnCounts.all { it == 1 })
            }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }

    @Test fun createChoosesTheDatabaseAndCategoryWithoutSavingSecretDrafts() = runBlocking {
        val fixture = Fixture()
        val visible = mutableStateOf(true)
        try {
            val firstId = fixture.createDatabase("CLI Personal")
            val secondId = fixture.createDatabase("CLI Work")
            val folder = fixture.repository.createFolder(secondId, "Automation", null)
            val saved = AtomicReference<NativeApiTokenSummary?>()
            val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
            val secret = "synthetic-new-ui-token-123456"
            compose.setContent { if (visible.value) MonicaTheme(darkTheme = true) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    AddEditApiTokenScreen(fixture.model, initialDatabaseId = firstId,
                        onNavigateBack = {}, onSaved = saved::set,
                        onSwitchType = { _, _, _ -> }, onManageDatabases = {})
                }
            } }
            awaitText("CLI Personal", substring = true)
            compose.onNodeWithTag("api_token_save").assertIsNotEnabled()
            compose.onNodeWithTag("api_token_favorite").assertIsDisplayed().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText("CLI Personal", substring = true).performClick()
            awaitText("CLI Work")
            compose.onNodeWithText("CLI Work").performClick()
            awaitText("Automation")
            compose.onNodeWithText("Automation").performClick()
            compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
            compose.onNodeWithText("CLI Work", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("api_token_name").performTextReplacement("github-ci")
            compose.onNodeWithTag("api_token_provider").performClick()
            compose.onNodeWithText("GitHub").performClick()
            compose.onNodeWithTag("api_token_api_base").assertTextContains("https://api.github.com/")
            compose.onNodeWithTag("api_token_name").performTextReplacement("工作 API 令牌")
            compose.onNodeWithTag("api_token_provider").performTextReplacement("Example Forge")
            compose.onNodeWithTag("api_token_api_base").performTextReplacement("https://example.test/custom/api/")
            compose.onNodeWithTag("api_token_secret").performScrollTo().performClick().performTextReplacement(secret)
            compose.onNodeWithTag("api_token_note").performScrollTo().performTextReplacement("Deployment notes\nSecond line")
            compose.onNodeWithText(context.getString(R.string.custom_field_add)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.custom_field_name_placeholder)))
                .performScrollTo().performTextReplacement("Scope")
            compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.custom_field_value)))
                .performScrollTo().performTextReplacement("synthetic-protected-scope")
            compose.onNodeWithText(context.getString(R.string.custom_field_sensitive)).performScrollTo().performClick()
            compose.onNodeWithTag("api_token_save").assertIsEnabled().assertIsDisplayed()
            compose.onNodeWithTag("api_token_favorite").assertIsOn()
            compose.runOnIdle {
                assertFalse("Secret draft must not enter an Android saved-state Bundle",
                    registry.performSave().toString().contains(secret))
                assertFalse(registry.performSave().toString().contains("synthetic-protected-scope"))
            }
            screenshot("native-api-token-editor.png")
            compose.onNodeWithTag("api_token_save").performClick()
            compose.waitUntil(30_000) { saved.get() != null }
            val created = saved.get()!!
            assertEquals(secondId, created.databaseId)
            assertEquals(folder.folderId, created.collectionId)
            assertTrue(created.isFavorite)
            assertTrue(fixture.repository.listNativeApiTokens(firstId).isEmpty())
            val stored = fixture.repository.readNativeApiToken(secondId, created.entryId)
            assertTrue(stored.summary.isFavorite)
            assertTrue(fixture.repository.listNativeApiTokens(secondId).single().isFavorite)
            assertEquals("Example Forge", ApiTokenPayload.text(ApiTokenPayload.decode(stored.payload), "provider"))
            assertEquals(ApiTokenPayload.APP_SCHEMA, ApiTokenPayload.text(ApiTokenPayload.decode(stored.payload), "schema"))
            assertEquals("工作 API 令牌", stored.summary.title)
            assertEquals("Deployment notes\nSecond line", ApiTokenMetadata.notes(checkNotNull(stored.extras).payload, ""))
            val field = ApiTokenMetadata.customFields(checkNotNull(stored.extras).payload).single()
            assertEquals("Scope", field.title)
            assertEquals("synthetic-protected-scope", field.value)
            assertTrue(field.isProtected)
            assertEquals(secret, ApiTokenPayload.text(ApiTokenPayload.decode(stored.payload), "token"))
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }

    @Test fun passwordCardSwipesAndLongPressCreateAndOpenATokenStack() = runBlocking {
        val fixture = Fixture()
        val visible = mutableStateOf(true)
        try {
            val databaseId = fixture.createDatabase("Native interaction test")
            val payload = """{"schema":"monica.api-token.v1","provider":"Custom service","api_base":"","token":"synthetic-interaction-token"}"""
            val alpha = fixture.repository.saveNativeApiToken(databaseId, null, "Alpha", payload)
            val beta = fixture.repository.saveNativeApiToken(databaseId, null, "Beta", payload)
            compose.setContent { if (visible.value) MonicaTheme {
                NativeApiTokensScreen(fixture.model, databaseId, onNavigateBack = {}, onOpen = { _, _ -> },
                    onCreate = {}, onManageDatabases = {}, appSettings = AppSettings(passwordGroupMode = "none"))
            } }
            awaitTag("vault_item_password:${alpha.displayId}")
            compose.onNodeWithTag("vault_item_password:${alpha.displayId}").performTouchInput { swipeRight() }
            compose.onNodeWithContentDescription(context.getString(R.string.select_all)).assertIsDisplayed()
            compose.onNodeWithTag("vault_item_password:${beta.displayId}").performTouchInput { longClick() }
            compose.onNodeWithContentDescription(context.getString(R.string.batch_stack)).performClick()
            compose.onNodeWithText(context.getString(R.string.batch_stack_confirm_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
            compose.waitUntil(30_000) { runBlocking {
                fixture.room.passwordPageAggregateStackDao().getAll().count {
                    it.itemKey in setOf("password:${alpha.displayId}", "password:${beta.displayId}")
                } == 2
            } }
            compose.waitForIdle()
            compose.onNodeWithText("Alpha").performClick()
            awaitText("Beta")
            compose.onNodeWithText("Beta").performTouchInput { swipeLeft() }
            compose.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed().performClick()
            assertEquals(2, fixture.repository.listNativeApiTokens(databaseId).size)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitTag(tag: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun screenshot(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private inner class Fixture {
        val room = PasswordDatabase.getDatabase(context)
        private val dao = room.localMdbxDatabaseDao()
        private val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, dao, security)
        val executor = Mdbx2VaultSessionExecutor(context, dao, security)
        val model = MdbxViewModel(context.applicationContext as Application, dao, room.mdbxRemoteSourceDao(),
            room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), security)
        private val owned = mutableListOf<Pair<Long, File>>()

        suspend fun createDatabase(name: String, mode: MdbxTigaMode = MdbxTigaMode.SKY): Long {
            val password = "Synthetic UI test vault password 123"
            val file = repository.createInitializedVaultFile(mode, password)
            val id = dao.insertDatabase(LocalMdbxDatabase(name = name, filePath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptData(password),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
            owned += id to file
            return id
        }

        suspend fun close() {
            model.viewModelScope.cancel()
            owned.forEach { (id, file) ->
                takagi.ru.monica.repository.PasswordPageAggregateStackRepository(room.passwordPageAggregateStackDao())
                    .clearManualStack(repository.listNativeApiTokens(id).map { "password:${it.displayId}" })
                dao.deleteDatabaseById(id)
                repository.deleteOwnedVaultFile(file)
            }
        }
    }
}
