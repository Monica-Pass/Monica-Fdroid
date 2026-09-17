package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.dedup.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.DedupEngineUiState

@RunWith(AndroidJUnit4::class)
class DedupEngineScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var screenLocale = Locale.SIMPLIFIED_CHINESE
    private val sources = (1..45).map { DedupMergeSourceOption("keepass:$it", DedupMergeSourceKind.KEEPASS, "Source $it", 2) }
    private val target = DedupMergeTarget.MdbxDatabase(7, "Personal")
    private val targets = listOf(DedupMergeTargetOption(target, "mdbx:7", "Personal", 0)) +
        (8..50).map { DedupMergeTargetOption(DedupMergeTarget.MdbxDatabase(it.toLong(), "Archive $it"), "mdbx:$it", "Archive $it", 0) }
    private var state by mutableStateOf(DedupEngineUiState(isLoading = false, sourceOptions = sources, targetOptions = targets))
    private var selectedSearchKeys = emptySet<String>()
    private var created = 0
    private var executions = 0
    private var cancellations = 0
    private var back = 0
    private var restoreConfiguration: (() -> Unit)? = null
    private var keyboard: SoftwareKeyboardController? = null

    @After fun restoreLocale() { compose.runOnUiThread { restoreConfiguration?.invoke() } }

    @Test fun searchSelectionAndNewTargetRemainUsableWithManyDatabases() {
        show()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithTag("dedup_source_search").performTextInput("42")
        compose.onNodeWithText(text(R.string.dedup_merge_select_results)).performClick()
        assertEquals(setOf("keepass:42"), selectedSearchKeys)
        compose.runOnIdle { keyboard?.hide() }
        File(context.filesDir, "dedup-validation").apply { mkdirs() }
            .resolve("source-selection-tree.txt").writeText(compose.onNode(isDialog(), useUnmergedTree = true).printToString(20))
        compose.onNode(hasText("Source 42") and hasAnyAncestor(isSelected()), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_done)).performClick()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_create_target)).assertIsDisplayed().performClick()
        assertEquals(1, created)
        compose.onNodeWithText(text(R.string.dedup_merge_target_title)).performClick()
        compose.onNodeWithTag("dedup_target_search").performTextInput("Personal")
        compose.runOnIdle { keyboard?.hide() }
        compose.onNode(hasText("Personal") and !hasSetTextAction()).performClick()
        compose.runOnIdle { assertEquals(target, state.selectedMergeTarget) }
        screenshot("selection.png")
    }

    @Test fun previewFiltersExpandAndConfirmationDoesNotWriteUntilAccepted() {
        state = ready()
        show()
        screenshot("overview-zh.png")
        compose.onNodeWithText(text(R.string.dedup_merge_preview_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_filter_conflict, 1)).performClick()
        compose.onNodeWithText("Example login").performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_kept_from, "Source 1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_target_variant)).assertExists()
        compose.onAllNodesWithText("synthetic secret", substring = true).assertCountEquals(0)
        screenshot("preview-zh.png")
        compose.onNodeWithTag("dedup_preview_search").performTextInput("unmatched")
        compose.onNodeWithText(text(R.string.dedup_merge_filter_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_done)).performClick()
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(0, executions)
        compose.onNodeWithTag("dedup_primary_action").performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_start)).performClick()
        assertEquals(1, executions)
    }

    @Test fun passkeyPreviewShowsWriteAndSkipReasons() {
        val key = takagi.ru.monica.data.PasskeyEntry(credentialId = "synthetic", rpId = "example.invalid", rpName = "Example",
            userId = "AQID", userName = "alice", userDisplayName = "Ready key", publicKey = "public", privateKeyAlias = "synthetic")
        state = ready().let { ready -> ready.copy(mergePlan = ready.mergePlan.copy(totalSourcePasskeys = 2,
            unsupportedSourcePasskeys = 1, previewPasskeys = listOf(
                DedupResolvedPasskey("ready-passkey", key, listOf(5), listOf("Source 1"), "Source 1"),
                DedupResolvedPasskey("skipped-passkey", key.copy(userDisplayName = "Old counter"), listOf(6), listOf("Source 2"),
                    "Source 2", skipReason = DedupPasskeySkipReason.NONZERO_COUNTER)))) }
        show()
        compose.onNodeWithText(text(R.string.dedup_merge_preview_title)).performScrollTo().performClick()
        compose.onNodeWithTag("dedup_preview_search").performTextInput("Old counter")
        compose.runOnIdle { keyboard?.hide() }
        compose.onNodeWithText(text(R.string.dedup_passkey_nonzero_counter)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_filter_skip, state.mergePlan.skippedItems)).performClick()
        compose.onNode(hasText("Old counter") and !hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun runningMergeShowsProgressAndRequiresConfirmationToStop() {
        state = ready().copy(isExecutingMerge = true,
            executionProgress = DedupMergeExecutionProgress(1, 2, "Synthetic entry"))
        show()
        compose.onNodeWithText(text(R.string.dedup_merge_progress, 1, 2)).assertIsDisplayed()
        compose.onNodeWithTag("dedup_primary_action").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.dedup_merge_stop_action)).performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_continue_action)).performClick()
        assertEquals(0, cancellations)
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNode(hasText(text(R.string.dedup_merge_stop_action)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, cancellations)
        assertEquals(0, back)
    }

    @Test fun polishLargeTextAndDarkThemeKeepActionsReachable() {
        screenLocale = Locale.forLanguageTag("pl")
        state = ready()
        show(textScale = 1.5f, dark = true)
        compose.onNodeWithTag("dedup_primary_action").assertIsDisplayed().assertIsEnabled()
        screenshot("overview-pl-large-dark.png")
        compose.onNodeWithText(text(R.string.dedup_merge_target_title)).performScrollTo().performClick()
        compose.onNodeWithTag("dedup_target_search").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dedup_merge_create_target)).assertIsDisplayed()
        screenshot("targets-pl-large-dark.png")
        compose.onNodeWithText(text(R.string.dedup_merge_done)).performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_policy_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.dedup_merge_policy_newest)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(DedupConflictPolicy.NEWEST, state.conflictPolicy) }
    }

    private fun ready(): DedupEngineUiState {
        val password = PasswordEntry(id = 0, title = "Example login", username = "alice", website = "https://example.invalid", password = "synthetic secret")
        val plan = DedupMergePlan(selectedSources = sources.take(2), target = target,
            totalSourcePasswords = 4, uniquePasswords = 2, duplicateGroups = 2, passwordConflictGroups = 1,
            previewPasswords = listOf(
                DedupResolvedPassword("first", password, emptyList(), listOf(1, 2), listOf("Source 1", "Source 2"),
                    setOf("Password"), targetHasDifferentContent = true, preferredSourceLabel = "Source 1"),
                DedupResolvedPassword("second", password.copy(title = "Second login"), emptyList(), listOf(3, 4),
                    listOf("Source 1", "Source 2"), emptySet(), preferredSourceLabel = "Source 2")))
        return state.copy(selectedMergeSourceKeys = sources.take(2).map { it.key }.toSet(), selectedMergeTarget = target, mergePlan = plan)
    }

    private fun show(textScale: Float = 1f, dark: Boolean = false) {
        compose.setContent {
            val activityContext = LocalContext.current
            keyboard = LocalSoftwareKeyboardController.current
            val localized = remember(activityContext) {
                // Dialogs create their own context from the Activity. Exercise the real
                // locale there too, instead of localising only the screen composition.
                val resources = activityContext.resources
                val original = Configuration(resources.configuration)
                @Suppress("DEPRECATION")
                resources.updateConfiguration(Configuration(original).apply {
                    setLocale(screenLocale)
                    fontScale = textScale
                }, resources.displayMetrics)
                restoreConfiguration = {
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(original, resources.displayMetrics)
                }
                ContextThemeWrapper(activityContext, 0).apply {
                    applyOverrideConfiguration(Configuration(activityContext.resources.configuration).apply { setLocale(screenLocale) })
                }
            }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, textScale)) {
                MonicaTheme(darkTheme = dark) {
                    DedupEngineScreen(uiState = state, onNavigateBack = { back++ }, onRefresh = {},
                        onToggleSource = { key -> state = state.copy(selectedMergeSourceKeys =
                            if (key in state.selectedMergeSourceKeys) state.selectedMergeSourceKeys - key else state.selectedMergeSourceKeys + key) },
                        onSelectAllSources = { keys -> selectedSearchKeys = keys; state = state.copy(selectedMergeSourceKeys = state.selectedMergeSourceKeys + keys) },
                        onClearSources = { state = state.copy(selectedMergeSourceKeys = emptySet()) },
                        onSelectTarget = { state = state.copy(selectedMergeTarget = it) }, onCreateMdbxTarget = { created++ },
                        onConflictPolicyChange = { state = state.copy(conflictPolicy = it, mergePlan = state.mergePlan.copy(conflictPolicy = it)) },
                        onExecuteMerge = { executions++ }, onCancelMerge = { cancellations++ }, onConsumeMessage = {})
                }
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(screenLocale) }).getString(id, *args)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(context.filesDir, "dedup-validation").apply { mkdirs() }
        val dialogs = compose.onAllNodes(isDialog())
        val node = if (dialogs.fetchSemanticsNodes().isEmpty()) compose.onRoot() else dialogs.onFirst()
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
