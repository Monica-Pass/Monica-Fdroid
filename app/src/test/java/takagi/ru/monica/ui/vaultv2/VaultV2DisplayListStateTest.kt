package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection

class VaultV2DisplayListStateTest {

    @Test
    fun `display state merges current passwords and builds visible sections together`() {
        val stalePassword = buildVaultV2PasswordItems(
            listOf(password(id = 1L, title = "Old title"))
        ).single()
        val note = item(
            key = "note:9",
            type = VaultV2ItemType.NOTE,
            title = "Reference"
        )

        val result = buildVaultV2DisplayListState(
            computedItems = listOf(stalePassword, note),
            currentPasswordEntries = listOf(password(id = 1L, title = "New title")),
            config = config(displayedTypes = PasswordPageContentType.entries.toSet())
        )

        assertEquals(listOf("password:1", "note:9"), result.allItemsRaw.map { it.key })
        assertEquals("New title", result.allItemsRaw.first().title)
        assertEquals(listOf("N", "R"), result.visibleListState.sectionedItems.map { it.first })
    }

    @Test
    fun `display state applies filters after immediate password replacement`() {
        val stalePassword = buildVaultV2PasswordItems(
            listOf(password(id = 1L, title = "Old title"))
        ).single()

        val result = buildVaultV2DisplayListState(
            computedItems = listOf(stalePassword),
            currentPasswordEntries = listOf(password(id = 1L, title = "Updated account")),
            config = config(
                displayedTypes = setOf(PasswordPageContentType.PASSWORD),
                query = "updated"
            )
        )

        assertEquals(listOf("password:1"), result.visibleListState.filteredItems.map { it.key })
        assertFalse(result.visibleListState.filteredItems.any { it.title == "Old title" })
    }

    private fun config(
        displayedTypes: Set<PasswordPageContentType>,
        query: String = "",
    ) = VaultV2VisibleListConfig(
        storageSelection = UnifiedCategoryFilterSelection.All,
        displayedContentTypes = displayedTypes,
        configuredQuickFilterItems = PasswordListQuickFilterItem.DEFAULT_ORDER,
        quickFilterFavorite = false,
        quickFilter2fa = false,
        quickFilterNotes = false,
        quickFilterPasskey = false,
        quickFilterBoundNote = false,
        quickFilterAttachments = false,
        activeAttachmentParentIds = emptySet(),
        quickFilterUncategorized = false,
        quickFilterLocalOnly = false,
        quickFilterManualStackOnly = false,
        quickFilterNeverStack = false,
        quickFilterUnstacked = false,
        manualStackGroupByEntryId = emptyMap(),
        noStackEntryIds = emptySet(),
        normalizedQuery = query,
        isArchiveView = false,
    )

    private fun password(id: Long, title: String) = PasswordEntry(
        id = id,
        title = title,
        website = "example.com",
        username = "user",
        password = "secret"
    )

    private fun item(
        key: String,
        type: VaultV2ItemType,
        title: String,
    ) = VaultV2Item(
        key = key,
        type = type,
        title = title,
        subtitle = "-",
        isFavorite = false,
        sortKey = title,
        searchableValues = listOf(title)
    )
}
