package takagi.ru.monica.ui.note

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager

/** Opt in with -e notesFreshInstall true on a fresh disposable emulator. */
@RunWith(AndroidJUnit4::class)
class NoteListEntryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = requireNotNull(context.getExternalFilesDir("note-entry-verification"))
    private val recordFrames get() = InstrumentationRegistry.getArguments().getString("recordNoteEntryFrames") == "true"

    private fun tab(label: Int) = compose.onNode(
        hasText(context.getString(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    )

    private fun capture(name: String) {
        if (!recordFrames) return
        File(evidence, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(evidence, "$name.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
    }

    private fun verifyReentry(name: String) {
        compose.waitForIdle()
        val reference = compose.onNodeWithText("Local note 1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        capture("$name-settled")
        tab(R.string.nav_authenticator_short).performClick()
        compose.onNodeWithText("Local note 1").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        val unexpectedFrames = mutableListOf<Int>()
        val movingFrames = mutableListOf<String>()
        var checkedFrames = 0
        try {
            tab(R.string.nav_notes_short).performClick()
            repeat(24) { index ->
                compose.mainClock.advanceTimeByFrame()
                if (compose.onAllNodes(hasText("External note", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
                    unexpectedFrames += index
                }
                val local = compose.onAllNodesWithText("Local note 1").fetchSemanticsNodes().singleOrNull()
                if (local != null) {
                    checkedFrames++
                    val top = local.boundsInRoot.top
                    if (abs(top - reference.top) > 1f) movingFrames += "$index:$top"
                }
                if (index in listOf(0, 1, 3, 7, 15, 23)) capture("$name-frame-${index.toString().padStart(2, '0')}")
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        capture("$name-final")
        File(evidence, "$name-result.txt").writeText(
            "referenceTop=${reference.top}\ncheckedFrames=$checkedFrames\nunexpectedFrames=$unexpectedFrames\nmovingFrames=$movingFrames\n"
        )
        assertEquals("Notes should appear from the first frame after returning", 24, checkedFrames)
        assertTrue("Saved local filter exposed external notes in frames $unexpectedFrames", unexpectedFrames.isEmpty())
        assertTrue("Notes slid into place instead of appearing at ${reference.top}: $movingFrames", movingFrames.isEmpty())
    }

    @Test fun returningToNotesUsesTheSavedScopeFromTheFirstVisibleFrame() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notesFreshInstall") == "true" &&
            (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")))
        val security = SecurityManager(context)
        assumeFalse("Use a fresh disposable emulator", security.isMasterPasswordSet())
        val settings = SettingsManager(context)
        val database = PasswordDatabase.getDatabase(context)
        security.setMasterPassword("notes-entry-verification")
        runBlocking {
            settings.updateQuickSetupCompleted(true)
            settings.updateLanguage(Language.ENGLISH)
            settings.updateBiometricEnabled(false)
            settings.updateScreenshotProtectionEnabled(false)
            settings.updateDynamicColorEnabled(false)
            settings.updateThemeMode(ThemeMode.DARK)
            settings.updateNoteGridLayout(true)
            settings.updateCategoryFilterState(SettingsManager.CategoryFilterScope.NOTE, SavedCategoryFilterState(type = "local"))
            settings.updateBottomNavVisibility(BottomNavContentTab.AUTHENTICATOR, true)
            settings.updateBottomNavVisibility(BottomNavContentTab.NOTES, true)
            BottomNavContentTab.entries.filter { it !in setOf(BottomNavContentTab.AUTHENTICATOR, BottomNavContentTab.NOTES) }
                .forEach { settings.updateBottomNavVisibility(it, false) }
            // External ownership is metadata only; the fixture has no accounts or sync credentials.
            repeat(12) { index ->
                val external = index < 2
                database.secureItemDao().insertItem(SecureItem(
                    itemType = ItemType.NOTE,
                    title = if (external) "External note ${index + 1}" else "Local note ${index - 1}",
                    itemData = Json.encodeToString(NoteData(content = "Synthetic note for entry verification.\nNo user data.")),
                    sortOrder = index,
                    createdAt = Date(1_700_000_000_000L), updatedAt = Date(1_700_000_000_000L),
                    bitwardenVaultId = if (external) 9001L else null,
                    bitwardenCipherId = if (external) "note-entry-fixture-$index" else null
                ))
            }
        }
        SecurityManager.clearRuntimeUnlockCache()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("notes-entry-verification")
            compose.onAllNodes(hasSetTextAction())[0].performImeAction()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasText(context.getString(R.string.nav_notes_short)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes().isNotEmpty()
            }
            tab(R.string.nav_notes_short).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Local note 1").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText("External note 1").assertDoesNotExist()
            verifyReentry("grid-dark")
            runBlocking { settings.updateThemeMode(ThemeMode.LIGHT) }
            compose.waitForIdle()
            verifyReentry("grid-light")
            runBlocking { settings.updateNoteGridLayout(false) }
            compose.waitForIdle()
            verifyReentry("list-light")
        }
    }
}
