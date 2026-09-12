package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.ui.screens.bitwardenVaultIdForSync
import takagi.ru.monica.ui.screens.decodeNoteCategoryFilter
import takagi.ru.monica.ui.screens.encodeNoteCategoryFilter

class NoteBitwardenAutoSyncGuardTest {

    @Test
    fun allLocalKeePassAndMdbxNotesHaveNoBitwardenSyncTarget() {
        val filters = listOf(
            NoteCategoryFilter.All,
            NoteCategoryFilter.Local,
            NoteCategoryFilter.Starred,
            NoteCategoryFilter.Uncategorized,
            NoteCategoryFilter.LocalStarred,
            NoteCategoryFilter.LocalUncategorized,
            NoteCategoryFilter.Custom(10L),
            NoteCategoryFilter.KeePassDatabase(20L),
            NoteCategoryFilter.KeePassGroupFilter(20L, "notes"),
            NoteCategoryFilter.KeePassDatabaseStarred(20L),
            NoteCategoryFilter.KeePassDatabaseUncategorized(20L),
            NoteCategoryFilter.MdbxDatabase(30L)
        )

        filters.forEach { filter ->
            assertNull(filter.toString(), filter.bitwardenVaultIdForSync())
            assertNull(decodeNoteCategoryFilter(encodeNoteCategoryFilter(filter)).bitwardenVaultIdForSync())
        }
    }

    @Test
    fun explicitAndRestoredBitwardenFiltersKeepTheirOwningVault() {
        val filters = listOf(
            NoteCategoryFilter.BitwardenVault(7L),
            NoteCategoryFilter.BitwardenFolderFilter("notes", 7L),
            NoteCategoryFilter.BitwardenVaultStarred(7L),
            NoteCategoryFilter.BitwardenVaultUncategorized(7L)
        )

        filters.forEach { filter ->
            assertEquals(7L, filter.bitwardenVaultIdForSync())
            assertEquals(7L, decodeNoteCategoryFilter(encodeNoteCategoryFilter(filter)).bitwardenVaultIdForSync())
        }
    }

    @Test
    fun notesOnlyEnableBitwardenAutoSyncForAnExplicitVaultFilter() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt"
        ).readText().replace("\r\n", "\n")
        val effectCall = source
            .substringAfter("BitwardenAutoSyncEffect(")
            .substringBefore("\n    )")

        assertTrue(
            "The notes page must not start the all-vault Bitwarden sync for local/KeePass/MDBX or All views.",
            effectCall.contains("enabled = hasRestoredCategoryFilter && selectedBitwardenVaultId != null")
        )
    }

    @Test
    fun notesWaitForThePersistedFilterInsteadOfRestoringAPlaceholder() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt").readText()
        val restoreBody = source.substringAfter("LaunchedEffect(savedCategoryFilterState, hasRestoredCategoryFilter)")
            .substringBefore("LaunchedEffect(selectedCategoryFilter, hasRestoredCategoryFilter)")

        assertTrue(source.contains(".collectAsState(initial = null)"))
        assertTrue(restoreBody.contains("val persisted = savedCategoryFilterState ?: return@LaunchedEffect"))
        assertTrue(restoreBody.indexOf("val persisted") < restoreBody.indexOf("hasRestoredCategoryFilter = true"))
        assertFalse(source.contains(".collectAsState(initial = SavedCategoryFilterState())"))
    }

    @Test
    fun notesAndTheirBottomIndicatorShareTheSelectedVaultAndViewModel() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt").readText()
        val mainScreen = projectFile("app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt").readText()
        val notePane = projectFile("app/src/main/java/takagi/ru/monica/ui/note/NotePane.kt").readText()
        val compactContent = projectFile("app/src/main/java/takagi/ru/monica/ui/CompactDraggableTabContent.kt").readText()

        assertTrue(source.contains("bitwardenViewModel: BitwardenViewModel,"))
        assertFalse(source.contains("BitwardenViewModel = viewModel()"))
        assertTrue(source.contains("onBitwardenScopeChanged(selectedBitwardenVaultId)"))
        assertTrue(source.contains("onBitwardenScopeChanged(null)"))
        assertTrue(mainScreen.contains("BottomNavItem.Notes -> noteBitwardenVaultId != null"))
        assertTrue(mainScreen.contains("BottomNavItem.Notes -> noteBitwardenVaultId"))
        assertEquals(2, Regex("bitwardenViewModel = bitwardenViewModel").findAll(notePane).count())
        assertEquals(2, Regex("onBitwardenScopeChanged = onBitwardenScopeChanged").findAll(notePane).count())
        assertTrue(compactContent.contains("onBitwardenScopeChanged = onNoteBitwardenScopeChanged"))
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!
        }
        return File(dir, path)
    }
}
