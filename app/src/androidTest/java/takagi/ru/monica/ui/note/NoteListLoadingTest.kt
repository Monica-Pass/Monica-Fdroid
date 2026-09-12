package takagi.ru.monica.ui.note

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.notes.ui.model.NoteListItemUiModel
import takagi.ru.monica.ui.screens.NoteListContent

@RunWith(AndroidJUnit4::class)
class NoteListLoadingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cachedNotesStayHiddenUntilTheScopeIsReadyInBothLayouts() {
        val cachedNote = NoteListItemUiModel(
            id = 1L,
            title = "Cached note outside selected scope",
            rawContent = "Synthetic note",
            isMarkdown = false,
            inlineImageIds = emptyList(),
            previewText = "Synthetic note",
            tags = emptyList(),
            updatedAt = Date(1_700_000_000_000L),
            hasImageAttachment = false,
            syncStatus = null
        )
        val selectedNote = cachedNote.copy(id = 2L, title = "Note in restored scope")
        var notes by mutableStateOf(listOf(cachedNote))
        var loading by mutableStateOf(true)
        var grid by mutableStateOf(true)
        val repository = BitwardenRepository.getInstance(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        compose.setContent {
            MaterialTheme {
                NoteListContent(
                    notes = notes,
                    isInitialLoading = loading,
                    isGridLayout = grid,
                    isSearchExpanded = false,
                    onRequestExpandSearch = {},
                    isBitwardenDatabaseView = false,
                    bitwardenRepository = repository,
                    selectedNoteIds = emptySet(),
                    onNoteClick = {},
                    onNoteLongClick = {}
                )
            }
        }

        for (isGrid in listOf(true, false)) {
            compose.runOnIdle {
                grid = isGrid
                notes = listOf(cachedNote)
                loading = true
            }
            compose.onNodeWithText(cachedNote.title).assertDoesNotExist()
            compose.onNodeWithText(selectedNote.title).assertDoesNotExist()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .assertIsDisplayed()
            compose.runOnIdle {
                notes = listOf(selectedNote)
                loading = false
            }
            compose.onNodeWithText(selectedNote.title).assertIsDisplayed()
            compose.onNodeWithText(cachedNote.title).assertDoesNotExist()
        }
    }
}
