package takagi.ru.monica.ui.screens

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.NativeApiTokenSummary
import takagi.ru.monica.viewmodel.CategoryFilter

class NativeTokenListFilterTest {
    private val entries = listOf(
        NativeApiTokenSummary(1, "id-a", "folder-a", "Development", "GitLab work"),
        NativeApiTokenSummary(1, "id-b", "folder-b", "Personal", "GitHub home"),
        NativeApiTokenSummary(2, "id-c", "folder-c", "Development", "GitLab mirror")
    )

    @Test fun allDatabaseAndFolderFiltersKeepNativeIdentityAndScope() {
        assertEquals(entries, filterNativeApiTokens(entries, CategoryFilter.All, ""))
        assertEquals(listOf("id-a", "id-b"), filterNativeApiTokens(entries, CategoryFilter.MdbxDatabase(1), "").map { it.entryId })
        assertEquals(listOf(entries[0]), filterNativeApiTokens(entries, CategoryFilter.MdbxFolderFilter(1, "folder-a"), ""))
        assertTrue(filterNativeApiTokens(entries, CategoryFilter.MdbxFolderFilter(2, "folder-a"), "").isEmpty())
    }

    @Test fun searchUsesOnlyVisibleMetadataAndRespectsDatabaseFilter() {
        assertEquals(listOf(entries[0], entries[2]), filterNativeApiTokens(entries, CategoryFilter.All, "gitLAB"))
        assertEquals(listOf(entries[0]), filterNativeApiTokens(entries, CategoryFilter.MdbxDatabase(1), "Development"))
        assertTrue(filterNativeApiTokens(entries, CategoryFilter.All, "absent").isEmpty())
    }

    @Test fun unrelatedStorageAndArchiveViewsExcludeNativeTokens() {
        listOf(CategoryFilter.Archived, CategoryFilter.Local, CategoryFilter.LocalOnly,
            CategoryFilter.LocalStarred, CategoryFilter.Custom(1)).forEach {
            assertTrue(filterNativeApiTokens(entries, it, "").isEmpty())
        }
    }

    @Test fun favoriteCategoryAndQuickFilterUseMetadataWithinTheSelectedDatabase() {
        val favorites = entries.mapIndexed { index, token -> token.copy(isFavorite = index != 1) }
        assertEquals(listOf(favorites[0], favorites[2]), filterNativeApiTokens(favorites, CategoryFilter.Starred, ""))
        assertEquals(listOf(favorites[0]), filterNativeApiTokens(favorites, CategoryFilter.MdbxDatabase(1), "", favoritesOnly = true))
        assertTrue(filterNativeApiTokens(favorites, CategoryFilter.MdbxFolderFilter(1, "folder-b"), "", favoritesOnly = true).isEmpty())
        assertEquals(listOf(favorites[2]), filterNativeApiTokens(favorites, CategoryFilter.Starred, "mirror"))
    }

    @Test fun parentCategoryIncludesNativeTokensInDescendants() {
        val nested = entries[0].copy(ancestorCollectionIds = listOf("parent", "grandparent"))
        assertEquals(listOf(nested), filterNativeApiTokens(listOf(nested), CategoryFilter.MdbxFolderFilter(1, "grandparent"), ""))
        assertTrue(filterNativeApiTokens(listOf(nested), CategoryFilter.MdbxFolderFilter(2, "grandparent"), "").isEmpty())
    }
}
