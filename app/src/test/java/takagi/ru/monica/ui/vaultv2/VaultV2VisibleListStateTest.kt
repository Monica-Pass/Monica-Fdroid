package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection

class VaultV2VisibleListStateTest {

    @Test
    fun `visible list filters content and builds stable section indexes`() {
        val alpha = item("password:1", VaultV2ItemType.PASSWORD, "Alpha", "alpha user")
        val betaNote = item("note:2", VaultV2ItemType.NOTE, "Beta", "beta note")
        val numeric = item("password:3", VaultV2ItemType.PASSWORD, "123", "numeric")

        val result = buildVaultV2VisibleListState(
            allItems = listOf(alpha, betaNote, numeric),
            config = config(displayedTypes = setOf(PasswordPageContentType.PASSWORD)),
        )

        assertEquals(listOf(alpha, numeric), result.filteredItems)
        assertEquals(listOf("A", "#"), result.sectionedItems.map { it.first })
        assertEquals(listOf(0, 1), result.sectionLayouts.map { it.itemStartIndex })
        assertEquals(listOf(1, 3), result.sectionLayouts.map { it.firstItemLazyIndex })
    }

    @Test
    fun `visible list search keeps matching items only`() {
        val alpha = item("password:1", VaultV2ItemType.PASSWORD, "Alpha", "alpha user")
        val beta = item("password:2", VaultV2ItemType.PASSWORD, "Beta", "beta account")

        val result = buildVaultV2VisibleListState(
            allItems = listOf(alpha, beta),
            config = config(
                displayedTypes = setOf(PasswordPageContentType.PASSWORD),
                query = "USER",
            ),
        )

        assertEquals(listOf(alpha), result.filteredItems)
        assertEquals(listOf("A"), result.sectionedItems.map { it.first })
    }

    @Test
    fun `custom category can include descendant category ids`() {
        val parent = passwordItem("password:1", "Parent", categoryId = 10L)
        val child = passwordItem("password:2", "Child", categoryId = 11L)
        val unrelated = passwordItem("password:3", "Other", categoryId = 20L)

        val result = buildVaultV2VisibleListState(
            allItems = listOf(parent, child, unrelated),
            config = config(
                displayedTypes = setOf(PasswordPageContentType.PASSWORD),
                storageSelection = UnifiedCategoryFilterSelection.Custom(10L),
                localCategoryIdsInScope = setOf(10L, 11L)
            )
        )

        assertEquals(listOf(parent, child), result.filteredItems)
    }

    private fun config(
        displayedTypes: Set<PasswordPageContentType>,
        query: String = "",
        storageSelection: UnifiedCategoryFilterSelection = UnifiedCategoryFilterSelection.All,
        localCategoryIdsInScope: Set<Long> = emptySet(),
    ) = VaultV2VisibleListConfig(
        storageSelection = storageSelection,
        localCategoryIdsInScope = localCategoryIdsInScope,
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

    private fun passwordItem(key: String, title: String, categoryId: Long) = VaultV2Item(
        key = key,
        type = VaultV2ItemType.PASSWORD,
        title = title,
        subtitle = "-",
        isFavorite = false,
        sortKey = title,
        searchableValues = listOf(title),
        passwordEntry = PasswordEntry(
            id = key.substringAfter(':').toLong(),
            title = title,
            website = "",
            username = "",
            password = "",
            categoryId = categoryId
        )
    )

    private fun item(
        key: String,
        type: VaultV2ItemType,
        title: String,
        searchText: String,
    ) = VaultV2Item(
        key = key,
        type = type,
        title = title,
        subtitle = "-",
        isFavorite = false,
        sortKey = title,
        searchableValues = listOf(searchText),
    )
}
