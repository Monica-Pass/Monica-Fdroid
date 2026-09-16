package takagi.ru.monica.ui.screens

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.MdbxViewModel

/** Exercises production layouts with synthetic databases and isolated Room state. */
@RunWith(AndroidJUnit4::class)
class MdbxLayoutReachabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val width = mutableStateOf<Dp?>(null)
    private val fontScale = mutableFloatStateOf(1f)
    private val testLocale = mutableStateOf(Locale.SIMPLIFIED_CHINESE)
    private val dark = mutableStateOf(false)
    private val events = mutableListOf<String>()
    private val databases = (1L..40L).map { id ->
        LocalMdbxDatabase(
            id = id,
            name = listOf("个人密码", "工作账户", "家庭共享", "CLI 与服务").getOrNull(id.toInt() - 1)
                ?: "示例数据库 ${id.toString().padStart(2, '0')}",
            filePath = "/synthetic/vault-$id.mdbx",
            sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            storageLocation = MdbxStorageLocation.INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            isDefault = id == 1L,
            lastSyncStatus = if (id == 4L) MdbxSyncStatus.FAILED.name else MdbxSyncStatus.IN_SYNC.name
        )
    }

    private fun label(id: Int): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(testLocale.value) }
    ).getString(id)

    private fun show(shell: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            val scale = fontScale.floatValue
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(testLocale.value)
                this.fontScale = scale
                width.value?.let { screenWidthDp = it.value.toInt() }
            }
            // Keep the real Activity in the ContextWrapper chain so production file
            // pickers, back handling and OneDrive sign-in can find their owners.
            val localized = ContextThemeWrapper(compose.activity, 0).apply {
                applyOverrideConfiguration(configuration)
            }
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, scale)
            ) {
                MonicaTheme(darkTheme = dark.value) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.TopCenter) {
                        val frameWidth = width.value?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()
                        Box(frameWidth.fillMaxHeight().testTag("mdbx_test_frame")) {
                            if (shell) {
                                Scaffold(topBar = {
                                    MdbxTopAppBar(
                                        title = { Text(label(R.string.mdbx_ui_local_databases), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        navigationIcon = { IconButton(onClick = { events += "back" }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, label(R.string.back))
                                        } }
                                    )
                                }) { padding ->
                                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
                                }
                            } else content()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Source(items: List<LocalMdbxDatabase> = databases) {
        MdbxSourceManagementPage(
            MdbxManagerSource.LOCAL, items, mapOf(3L to 2), emptyMap(),
            onCreateClick = { events += "create" }, onOpenClick = { events += "open" },
            onOpenDatabase = { events += "database:${it.id}" }
        )
    }

    private fun assertActionsVisible() {
        listOf("mdbx_open_database", "mdbx_create_database").forEach { tag ->
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        }
        val grid = compose.onNodeWithTag("mdbx_database_grid").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("mdbx_source_actions").fetchSemanticsNode().boundsInRoot
        assertTrue("The fixed actions must not cover the last grid row", grid.bottom <= actions.top + 1f)
    }

    @Test
    fun fortyDatabasesKeepCreateOpenVisibleAndClickTheCorrectTile() {
        show { Source() }
        assertActionsVisible()
        val initial = compose.onNodeWithTag("mdbx_source_actions").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("mdbx_database_1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("mdbx_database_2").fetchSemanticsNode().boundsInRoot
        assertEquals("Normal size uses two columns", first.top, second.top, 1f)
        assertTrue(first.right < second.left)
        val frame = compose.onNodeWithTag("mdbx_test_frame").fetchSemanticsNode()
        val expectedInset = 12f * frame.layoutInfo.density.density
        assertEquals("Tiles use the authenticator's 12dp screen inset", expectedInset, first.left - frame.boundsInRoot.left, 1f)
        assertEquals(expectedInset, frame.boundsInRoot.right - second.right, 1f)
        assertEquals(first.left, compose.onNodeWithTag("mdbx_open_database").fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(second.right, compose.onNodeWithTag("mdbx_create_database").fetchSemanticsNode().boundsInRoot.right, 1f)
        capture("mdbx-tiles-light.png")
        compose.onNodeWithTag("mdbx_database_grid").performScrollToIndex(40)
        compose.onNodeWithTag("mdbx_database_40").assertIsDisplayed().performClick()
        assertActionsVisible()
        assertEquals(initial, compose.onNodeWithTag("mdbx_source_actions").fetchSemanticsNode().boundsInRoot)
        capture("mdbx-tiles-last-row.png")
        compose.onNodeWithTag("mdbx_open_database").performClick()
        compose.onNodeWithTag("mdbx_create_database").performClick()
        compose.runOnIdle { assertEquals(listOf("database:40", "open", "create"), events) }
    }

    @Test
    fun tilesAdaptToNarrowScreensAndLargeFontsWithoutTruncatingActions() {
        show { Source() }
        compose.runOnIdle { width.value = 320.dp }
        assertSingleColumn()
        compose.runOnIdle { width.value = 360.dp; fontScale.floatValue = 1.8f; dark.value = true }
        assertSingleColumn()
        assertActionsVisible()
        capture("mdbx-tiles-dark-large-text.png")
        listOf(Locale.GERMAN, Locale.forLanguageTag("pl"), Locale.forLanguageTag("lzh")).forEach { language ->
            compose.runOnIdle { testLocale.value = language; width.value = 320.dp }
            assertActionsVisible()
            capture("mdbx-tiles-${language.language}-large-text.png")
            listOf("mdbx_open_database" to R.string.attachment_open, "mdbx_create_database" to R.string.create_new)
                .forEach { (tag, id) ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    val text = compose.onNode(hasText(label(id)) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue(layouts.isNotEmpty())
                    val layout = layouts.single()
                    val widestLine = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
                    val measurement = "$language $tag: size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
                        "paragraphHeight=${layout.multiParagraph.height}, lineCount=${layout.lineCount}, " +
                        "widestLine=$widestLine, overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}"
                    File(context.getExternalFilesDir(null), "mdbx-action-text-metrics.log").appendText(measurement + "\n")
                    assertEquals(measurement, layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertFalse(measurement, (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                    // Simple Text semantics can reconstruct a centered paragraph at the parent's
                    // max width (618px here) for a wrap-content text node (200px). Its line span
                    // is the visible text width; the paragraph container is not a clipping bound.
                    assertTrue(measurement, widestLine <= layout.size.width + 1f)
                    assertTrue(measurement, layout.multiParagraph.height <= layout.size.height + 1f)
                    val textBounds = text.fetchSemanticsNode().boundsInRoot
                    val buttonBounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    assertTrue(textBounds.left >= buttonBounds.left && textBounds.right <= buttonBounds.right)
                }
        }
    }

    private fun assertSingleColumn() {
        val first = compose.onNodeWithTag("mdbx_database_1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("mdbx_database_2").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, second.left, 1f)
        assertTrue("Second tile must flow below the first", second.top >= first.bottom)
    }

    @Test
    fun returningToSourceRetainsItsScrolledPosition() {
        val onSource = mutableStateOf(true)
        var firstVisible = -1
        var restored = -1
        show {
            val state = rememberLazyGridState()
            if (onSource.value) {
                SideEffect { restored = state.firstVisibleItemIndex }
                MdbxSourceManagementPage(
                    MdbxManagerSource.LOCAL, databases, emptyMap(), emptyMap(), {}, {},
                    onOpenDatabase = { firstVisible = state.firstVisibleItemIndex; onSource.value = false }, gridState = state
                )
            } else Button(onClick = { onSource.value = true }, modifier = Modifier.testTag("return")) { Text("返") }
        }
        compose.onNodeWithTag("mdbx_database_grid").performScrollToIndex(40)
        compose.onNodeWithTag("mdbx_database_40").performClick()
        compose.onNodeWithTag("return").performClick()
        compose.onNodeWithTag("mdbx_database_40").assertIsDisplayed()
        compose.runOnIdle { assertTrue(firstVisible > 0); assertEquals(firstVisible, restored) }
    }

    @Test
    fun realManagerRoutesAllSixCreateOpenActionsToTheirStorageSource() {
        Fixture().use { fixture ->
            show(shell = false) {
                MdbxManagerScreen(
                    fixture.model, onNavigateBack = { events += "exit" },
                    onNavigateToLocalCreate = { events += "local:create" }, onNavigateToLocalOpen = { events += "local:open" },
                    onNavigateToWebDavCreate = { events += "webdav:create" }, onNavigateToWebDavOpen = { events += "webdav:open" },
                    onNavigateToOneDriveCreate = { events += "onedrive:create" }, onNavigateToOneDriveOpen = { events += "onedrive:open" }
                )
            }
            listOf(label(R.string.mdbx_ui_local_databases), "WebDAV", "OneDrive").forEach { title ->
                compose.onNodeWithText(title).performClick()
                compose.onAllNodesWithTag("mdbx_open_database").assertCountEquals(1)
                compose.onAllNodesWithTag("mdbx_create_database").assertCountEquals(1)
                compose.onNodeWithTag("mdbx_open_database").assertIsDisplayed().performClick()
                compose.onNodeWithTag("mdbx_create_database").assertIsDisplayed().performClick()
                compose.onNodeWithContentDescription(label(R.string.back)).performClick()
            }
            compose.runOnIdle { assertEquals(listOf("local:open", "local:create", "webdav:open", "webdav:create", "onedrive:open", "onedrive:create"), events) }
        }
    }

    @Test
    fun allSixProductionFormsKeepSubmissionVisibleWithLargeText() {
        val page = mutableIntStateOf(0)
        fontScale.floatValue = 1.5f
        dark.value = true
        Fixture().use { fixture ->
            show(shell = false) {
                key(page.intValue) {
                    when (page.intValue) {
                        0 -> MdbxLocalCreateScreen(fixture.model) {}
                        1 -> MdbxLocalOpenScreen(fixture.model) {}
                        2 -> MdbxWebDavCreateScreen(fixture.model) {}
                        3 -> MdbxWebDavOpenScreen(fixture.model) {}
                        4 -> MdbxOneDriveCreateScreen(fixture.model) {}
                        else -> MdbxOneDriveOpenScreen(fixture.model) {}
                    }
                }
            }
            repeat(6) { index ->
                compose.runOnIdle { page.intValue = index }
                val action = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("mdbx_form_actions")))
                action.assertIsDisplayed().assertIsNotEnabled()
                val before = action.fetchSemanticsNode().boundsInRoot
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                    .performTouchInput { swipeUp() }
                action.assertIsDisplayed().assertIsNotEnabled()
                assertEquals(before, action.fetchSemanticsNode().boundsInRoot)
                capture("mdbx-form-$index-dark-large-text.png")
            }
        }
    }

    @Test
    fun localCreateAndOpenKeepFocusedFieldsAndSubmitAboveTheKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val opening = mutableStateOf(false)
        Fixture().use { fixture ->
            show(shell = false) {
                if (opening.value) MdbxLocalOpenScreen(fixture.model) {}
                else MdbxLocalCreateScreen(fixture.model) {}
            }
            val submit = { compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("mdbx_form_actions"))) }
            fun fill(id: Int, value: String) {
                val field = compose.onNode(hasSetTextAction() and hasText(label(id)))
                field.performScrollTo().performClick().performTextInput(value)
                field.assertIsDisplayed()
                submit().assertIsDisplayed()
            }
            submit().assertIsNotEnabled()
            fill(R.string.mdbx_vault_name, "界面测试")
            fill(R.string.mdbx_master_password, "Synthetic password 123")
            fill(R.string.mdbx_confirm_password, "Synthetic password 123")
            submit().assertIsEnabled()
            assertAboveKeyboard()
            capture("mdbx-local-create-keyboard.png", includeSystemUi = true)
            compose.activityRule.scenario.onActivity {
                WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(5_000) { !imeVisible() }
            compose.runOnIdle { opening.value = true }
            fill(R.string.mdbx_master_password, "Synthetic password 123")
            fill(R.string.mdbx_confirm_password, "Synthetic password 123")
            submit().assertIsNotEnabled() // No file has been chosen.
            assertAboveKeyboard()
            capture("mdbx-local-open-keyboard.png", includeSystemUi = true)
            compose.activityRule.scenario.onActivity {
                WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun assertAboveKeyboard() {
        compose.waitUntil(5_000) { imeVisible() }
        compose.waitForIdle()
        val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
        val keyboardTop = compose.activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val button = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("mdbx_form_actions")))
            .fetchSemanticsNode().boundsInWindow
        assertTrue("Submit must be above the IME", button.bottom <= keyboardTop + 1f)
        val field = compose.onNode(isFocused() and hasSetTextAction()).fetchSemanticsNode().boundsInWindow
        assertTrue("The focused field must not be covered by the fixed submit bar", field.bottom <= button.top)
    }

    private fun capture(name: String, includeSystemUi: Boolean = false) {
        val bitmap = if (includeSystemUi) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            else compose.onNodeWithTag("mdbx_test_frame").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private inner class Fixture : AutoCloseable {
        private val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val model = MdbxViewModel(context.applicationContext as Application, room.localMdbxDatabaseDao(), room.mdbxRemoteSourceDao(),
            room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), SecurityManager(context))
        override fun close() {
            runBlocking { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            room.close()
        }
    }
}
