package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.PasswordCredentialPickerSheet
import takagi.ru.monica.ui.components.PasswordCredentialEditorBar
import takagi.ru.monica.ui.theme.MonicaTheme

/** Real picker and action button, hosted with synthetic account names and callback recording. */
@RunWith(AndroidJUnit4::class)
class PasswordCredentialPickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var visible by mutableStateOf(false)
    private var selected by mutableIntStateOf(3)
    private var commonSelected by mutableStateOf(false)
    private var allowRemoval by mutableStateOf(true)
    private val usernames = mutableStateListOf("alice@example.com", "work@example.com", "", "")
    private val events = mutableListOf<String>()
    private var locale = Locale.SIMPLIFIED_CHINESE
    private var scale = 1f
    private var dark = false
    private var originalConfiguration: Configuration? = null

    @After fun restoreActivityConfiguration() {
        originalConfiguration?.let { original ->
            compose.activityRule.scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            }
        }
    }

    private fun show() {
        // Match BaseMonicaActivity: Android dialog windows inherit the Activity's
        // configuration, not just the composition-local overrides in the host content.
        compose.activityRule.scenario.onActivity { activity ->
            originalConfiguration = Configuration(activity.resources.configuration)
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocale(this@PasswordCredentialPickerUiTest.locale)
                fontScale = scale
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        }
        compose.setContent {
            val configuration = Configuration(compose.activity.resources.configuration).apply {
                setLocale(this@PasswordCredentialPickerUiTest.locale)
                fontScale = scale
            }
            val context = remember {
                ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(configuration) }
            }
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, scale),
            ) {
                MonicaTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(stringResource(R.string.add_password_title), style = MaterialTheme.typography.headlineSmall)
                                if (commonSelected) {
                                    Text(stringResource(R.string.common_info), style = MaterialTheme.typography.titleMedium)
                                    Text("Example · example.com")
                                } else {
                                    OutlinedTextField(usernames[selected], {}, label = { Text(stringResource(R.string.username)) },
                                        modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField("••••••••", {}, label = { Text(stringResource(R.string.password)) },
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                            PasswordCredentialEditorBar(commonSelected, selected, usernames.size,
                                canAdd = true, canSave = true, isSaving = false,
                                onOpenPicker = { visible = true }, onAdd = {}, onSave = {})
                        }
                    }
                    if (visible) {
                        PasswordCredentialPickerSheet(
                            usernames, selected, commonSelected, canAdd = true, canRemoveSelected = allowRemoval,
                            onSelect = { events += "select:$it"; selected = it; commonSelected = false; visible = false },
                            onSelectCommon = { events += "common"; commonSelected = true; visible = false },
                            onAdd = {
                                events += "add"; usernames.add(""); selected = usernames.lastIndex
                                commonSelected = false; visible = false
                            },
                            onRemoveSelected = {
                                events += "remove:$selected"
                                usernames.removeAt(selected)
                                selected = selected.coerceAtMost(usernames.lastIndex)
                                visible = false
                            },
                            onDismiss = { events += "dismiss"; visible = false },
                        )
                    }
                }
            }
        }
    }

    private fun label(id: Int, vararg args: Any): String =
        instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply {
                setLocale(this@PasswordCredentialPickerUiTest.locale)
            }
        ).getString(id, *args)

    private fun open() = compose.onNodeWithTag("password_credential_switcher").performClick()

    private fun assertTextFits(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size)
        val result = results.single()
        val layoutDetails = buildString {
            appendLine("Text: $text")
            appendLine("size=${result.size}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}")
            appendLine("constraints=${result.layoutInput.constraints}, lines=${result.lineCount}")
            appendLine("overflowWidth=${result.didOverflowWidth}, overflowHeight=${result.didOverflowHeight}")
            repeat(result.lineCount) { line ->
                appendLine("line $line: left=${result.getLineLeft(line)}, right=${result.getLineRight(line)}, " +
                    "top=${result.getLineTop(line)}, bottom=${result.getLineBottom(line)}, ellipsized=${result.isLineEllipsized(line)}")
            }
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "credential-picker-ui").apply { mkdirs() }
        File(directory, "text-layout.txt").appendText(layoutDetails)
        // Compose may retain a paragraph wider than a wrap-content Text node. Check the
        // rendered line edges, since that unused paragraph space is not clipped text.
        assertFalse("Text height must fit:\n$layoutDetails", result.didOverflowHeight)
        repeat(result.lineCount) { line ->
            assertFalse("Text must not be ellipsized:\n$layoutDetails", result.isLineEllipsized(line))
            assertTrue("Text width must fit:\n$layoutDetails",
                result.getLineLeft(line) >= 0f && result.getLineRight(line) <= result.size.width)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "credential-picker-ui").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun switchingAddingAndClosingDispatchTheCorrectAction() {
        show()
        capture("zh-editor-actions")
        open()
        compose.onNodeWithTag("password_credential_3").assertIsSelected()
        capture("zh-credential-sheet")
        compose.onNodeWithTag("password_credential_common").performClick()
        compose.waitUntil { events.size == 1 }
        compose.onNodeWithTag("password_credential_switcher").assertContentDescriptionEquals(label(R.string.common_info))
        assertFalse(SemanticsProperties.StateDescription in compose.onNodeWithTag("password_credential_switcher").fetchSemanticsNode().config)
        capture("zh-shared-editor-actions")
        open()
        compose.onNodeWithTag("password_credential_common").assertIsSelected()
        compose.onNodeWithTag("password_credential_3").assertIsNotSelected()
        compose.onNodeWithTag("password_credential_remove").assertDoesNotExist()
        compose.onNodeWithTag("password_credential_1").performClick()
        compose.waitUntil { events.size == 2 }
        assertEquals(listOf("common", "select:1"), events)
        compose.onNodeWithTag("password_credential_sheet").assertDoesNotExist()
        open()
        compose.onNodeWithTag("password_credential_add").performClick()
        compose.waitUntil { events.size == 3 }
        assertEquals(listOf("common", "select:1", "add"), events)
        assertEquals(5, usernames.size)
        open()
        compose.onNodeWithTag("password_credential_close").performClick()
        compose.waitUntil { events.size == 4 }
        assertEquals(listOf("common", "select:1", "add", "dismiss"), events)
    }

    @Test fun largeRussianTextScrollsAndRemovalIsExplicit() {
        locale = Locale("ru")
        scale = 1.8f
        dark = true
        repeat(8) { usernames.add("account-${it + 5}@example.com") }
        show()
        val actions = compose.onNodeWithTag("password_credential_editor_bar").getBoundsInRoot()
        val switcher = compose.onNodeWithTag("password_credential_switcher").getBoundsInRoot()
        assertTrue(switcher.left >= actions.left && switcher.right <= actions.right)
        capture("ru-large-editor-actions")
        open()
        compose.onNodeWithTag("password_credential_common").performClick()
        compose.waitUntil { commonSelected && !visible }
        compose.onNodeWithTag("password_credential_switcher").assertContentDescriptionEquals(label(R.string.common_info))
        capture("ru-large-shared-editor-actions")
        open()
        compose.onNodeWithTag("password_credential_common").assertIsSelected()
        compose.onNodeWithTag("password_credential_remove").assertDoesNotExist()
        capture("ru-large-shared-sheet")
        compose.onNodeWithTag("password_credential_list").performScrollToIndex(3)
        compose.onNodeWithTag("password_credential_3").performClick()
        compose.waitUntil { !commonSelected && !visible }
        assertEquals(listOf("common", "select:3"), events)
        compose.runOnIdle { events.clear() }
        open()
        compose.onNodeWithTag("password_credential_3").assertIsSelected()
        compose.onNodeWithTag("password_credential_add").assertIsDisplayed()
        capture("ru-large-credential-sheet")
        assertTextFits(label(R.string.section_credentials))
        assertTextFits(label(R.string.credential_number, 4))
        assertTextFits(label(R.string.add_credential))
        compose.onNodeWithTag("password_credential_remove").performClick()
        compose.onNodeWithText(label(R.string.delete_password_message, label(R.string.credential_number, 4)))
            .assertIsDisplayed()
        compose.onNodeWithTag("password_credential_confirm_remove")
            .assertTextContains(label(R.string.delete)).assertIsDisplayed()
        compose.onNodeWithTag("password_credential_cancel_remove")
            .assertTextContains(label(R.string.cancel)).assertIsDisplayed()
        capture("ru-removal-confirmation")
        compose.onNodeWithTag("password_credential_cancel_remove").performClick()
        assertEquals(emptyList<String>(), events)
        assertEquals(12, usernames.size)
        compose.onNodeWithTag("password_credential_remove").performClick()
        compose.onNodeWithTag("password_credential_confirm_remove").performClick()
        compose.waitUntil { events.isNotEmpty() }
        assertEquals(listOf("remove:3"), events)
        assertEquals(11, usernames.size)
        open()
        compose.onNodeWithTag("password_credential_list").performScrollToIndex(usernames.lastIndex)
        compose.onNodeWithTag("password_credential_10").assertIsDisplayed()
        compose.onNodeWithTag("password_credential_add").assertIsDisplayed()
        capture("ru-large-scrolled-sheet")
        compose.onNodeWithTag("password_credential_close").performClick()
        compose.waitUntil { !visible }
        compose.runOnIdle { selected = 0; allowRemoval = false }
        open()
        compose.onNodeWithTag("password_credential_remove").assertDoesNotExist()
        compose.onNodeWithTag("password_credential_close").performClick()
        compose.waitUntil { !visible }
        compose.runOnIdle {
            while (usernames.size > 1) usernames.removeAt(usernames.lastIndex)
            allowRemoval = true
        }
        open()
        compose.onNodeWithTag("password_credential_remove").assertDoesNotExist()
    }
}
