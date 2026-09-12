package takagi.ru.monica.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.ui.screens.NoteCategoryFilterSaver

class NoteCategoryFilterStateTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = value is String || value is Long
    }

    private fun restore(filter: NoteCategoryFilter): NoteCategoryFilter? {
        val saved = with(NoteCategoryFilterSaver) { scope.save(filter) }
        return NoteCategoryFilterSaver.restore(requireNotNull(saved))
    }

    @Test fun localAndDatabaseScopesSurviveTabStateRestoration() {
        listOf(
            NoteCategoryFilter.All,
            NoteCategoryFilter.Local,
            NoteCategoryFilter.LocalStarred,
            NoteCategoryFilter.Custom(17L),
            NoteCategoryFilter.MdbxDatabase(29L)
        ).forEach { assertEquals(it, restore(it)) }
    }

    @Test fun restoringFoldersKeepsTheirDatabaseAndStableGroupIdentity() {
        listOf(
            NoteCategoryFilter.BitwardenFolderFilter("folder-42", 9L),
            NoteCategoryFilter.KeePassGroupFilter(8L, "Work/Projects", "stable-group-uuid"),
            NoteCategoryFilter.KeePassDatabaseUncategorized(8L)
        ).forEach { assertEquals(it, restore(it)) }
    }
}
