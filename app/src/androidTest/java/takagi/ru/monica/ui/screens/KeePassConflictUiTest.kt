package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.keepass.*
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class KeePassConflictUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.SIMPLIFIED_CHINESE)
    })
    private var originalConfiguration: Configuration? = null

    @After fun restoreActivityConfiguration() {
        originalConfiguration?.let { original ->
            compose.runOnUiThread {
                @Suppress("DEPRECATION")
                compose.activity.resources.updateConfiguration(original, compose.activity.resources.displayMetrics)
            }
        }
    }

    @Test fun conflictBannerOpensTheReviewWithOneTap() {
        val opened = mutableStateOf(false)
        show {
            KeePassConflictBanner(onReview = { opened.value = true })
            if (opened.value) KeePassConflictResolutionSheet(state(), {}, {}, { _, _ -> })
        }
        compose.onNodeWithTag("keepass-conflict-review").performClick()
        compose.onNodeWithTag("keepass-conflict-merge").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun disputedFieldsRequireSelectionAndProtectedValuesStayMasked() {
        val review = state(disputed = true)
        var choice: Map<String, KeePassConflictResolutionSide>? = null
        show { KeePassConflictResolutionSheet(review, {}, {}, { decision, selected ->
            assertEquals(KeePassConflictDecision.MERGE, decision)
            choice = selected
        }) }
        compose.onNodeWithTag("keepass-conflict-merge").assertIsNotEnabled()
        compose.onNodeWithText("synthetic-local-secret").assertDoesNotExist()
        compose.onNodeWithText("synthetic-remote-secret").assertDoesNotExist()
        val option = compose.onNodeWithTag("keepass-conflict-choice:password:LOCAL")
        option.performScrollTo().performClick().assertIsSelected()
        compose.mainClock.autoAdvance = false
        option.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(110)
        screenshot("light-pressed")
        option.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("keepass-conflict-merge").assertIsEnabled().performClick()
        assertEquals(mapOf("password" to KeePassConflictResolutionSide.LOCAL), choice)
    }

    @Test fun errorsAndRetryStayInTheSheetAndNewRevisionsResetChoices() {
        val current = mutableStateOf(state(disputed = true))
        var refreshes = 0
        show { KeePassConflictResolutionSheet(current.value, {}, {
            refreshes++
            current.value = current.value.copy(loading = true, error = null)
        }, { _, _ -> }) }
        compose.onNodeWithTag("keepass-conflict-choice:password:REMOTE").performScrollTo().performClick()
        compose.onNodeWithTag("keepass-conflict-merge").assertIsEnabled()
        compose.runOnIdle { current.value = current.value.copy(preview = null, error = "Synthetic upload failed") }
        compose.onNodeWithTag("keepass-conflict-error").assertIsDisplayed()
        compose.onNodeWithTag("keepass-conflict-merge").assertDoesNotExist()
        compose.onNodeWithTag("keepass-conflict-refresh").performClick()
        compose.onNodeWithText(chinese.getString(R.string.keepass_conflict_comparing)).assertIsDisplayed()
        compose.runOnIdle { current.value = state(disputed = true, revision = "new-local") }
        compose.onNodeWithTag("keepass-conflict-merge").assertIsNotEnabled()
        assertEquals(1, refreshes)
    }

    @Test fun largeTextDarkReviewShowsAllChangesAndKeepsMergeReachable() {
        show(dark = true, fontScale = 1.6f) {
            KeePassConflictResolutionSheet(state(count = 75), {}, {}, { _, _ -> })
        }
        compose.onNodeWithTag("keepass-conflict-list").performScrollToNode(hasText("Account 074"))
        compose.onNodeWithText("Account 074").assertIsDisplayed().performClick()
        compose.onNodeWithTag("keepass-conflict-merge").assertIsDisplayed().assertIsEnabled()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(chinese.getString(R.string.keepass_conflict_merge_sync))
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1.6f, layouts.single().layoutInput.density.fontScale, 0.001f)
        screenshot("dark-large-text")
    }

    @Test fun aWriteCannotBeDismissedOrSubmittedTwice() {
        val visible = mutableStateOf(true)
        var submissions = 0
        show {
            if (visible.value) KeePassConflictResolutionSheet(
                state().copy(resolving = true), onDismiss = { visible.value = false },
                onRefresh = {}, onDecision = { _, _ -> submissions++ }
            )
        }
        compose.onNodeWithContentDescription(chinese.getString(R.string.close)).assertIsNotEnabled()
        compose.onNodeWithTag("keepass-conflict-merge").assertIsNotEnabled()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("keepass-conflict-merge").assertExists()
        assertTrue(visible.value)
        assertEquals(0, submissions)
    }

    private fun show(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val resources = compose.activity.resources
            originalConfiguration = Configuration(resources.configuration)
            val configuration = Configuration(resources.configuration).apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
                this.fontScale = fontScale
            }
            // Dialogs read their Activity's resources, not an outer LocalContext override.
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        compose.setContent {
            MonicaTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) { content() } }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "keepass-conflict-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun state(count: Int = 1, disputed: Boolean = false, revision: String = "local") = KeePassConflictResolutionState(
        databaseId = 1, databaseName = "个人密码库", loading = false,
        preview = KeePassRemoteConflictPreview(
            snapshot = KeePassConflictSnapshot(
                items = (0 until count).map { i -> KeePassConflictItem(
                    id = i.toString(), objectType = KeePassConflictObjectType.ENTRY,
                    label = "Account %03d".format(Locale.US, i),
                    localChange = KeePassConflictChangeType.MODIFIED, remoteChange = KeePassConflictChangeType.MODIFIED,
                    ambiguous = disputed, localSummary = null, remoteSummary = null,
                    details = if (disputed) listOf(KeePassConflictDetail("password", KeePassConflictDetailKind.FIELD, "Password",
                        "synthetic-local-secret", "synthetic-remote-secret", protectedValue = true)) else emptyList()
                ) },
                localChangeCount = count, remoteChangeCount = count,
                ambiguousCount = if (disputed) count else 0, mergeRecommended = !disputed
            ),
            localRevision = KeePassSourceRevision(revision, 10), remoteRevision = KeePassSourceRevision("remote", 20),
            baseRevision = KeePassSourceRevision("base", 5), remoteVersionToken = "etag", remoteSizeBytes = 20
        )
    )
}
