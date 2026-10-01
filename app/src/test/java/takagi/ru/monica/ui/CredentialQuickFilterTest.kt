package takagi.ru.monica.ui

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.ui.password.*
import takagi.ru.monica.ui.vaultv2.*
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.viewmodel.CategoryFilter

class CredentialQuickFilterTest {
    private fun entry(id: Long, type: String = "PASSWORD") = PasswordEntry(
        id = id, title = "Test", website = "", username = "", password = "", loginType = type)
    private val key = entry(1, "API_KEY")
    private val gpg = entry(2, "GPG_KEY")
    private val embedded = entry(3)
    private val plain = entry(4)
    private val token = NativeApiTokenSummary(1, "token", "root", "Root", "Token").asPasswordCard("API token")
    private val types = setOf(PasswordPageContentType.API_KEY, PasswordPageContentType.API_TOKEN, PasswordPageContentType.GPG_KEY)
    private fun fields(): List<CustomField> {
        var drafts = emptyList<CustomFieldDraft>()
        for (kind in listOf(PasswordContentBlocks.Kind.API_KEY, PasswordContentBlocks.Kind.API_TOKEN, PasswordContentBlocks.Kind.GPG_KEY)) {
            drafts = PasswordContentBlocks.put(drafts, PasswordContentBlocks.create(kind))
        }
        return drafts.mapIndexed { i, field -> field.toCustomField(embedded.id, i) }
    }
    @Test fun indexRecognizesValidatedBlocksAndNeverMutatesOrGuesses() {
        val source = fields()
        val before = source.toList()
        assertEquals(types, buildPasswordContentTypeIndex(source)[embedded.id])
        assertEquals(before, source)
        assertTrue(buildPasswordContentTypeIndex(listOf(CustomField(entryId = 4, title = "note", value = "API_KEY API_TOKEN GPG_KEY"))).isEmpty())
        assertTrue(buildPasswordContentTypeIndex(source.filterNot { it.title.endsWith(".0000") }).isEmpty())
        assertTrue(buildPasswordContentTypeIndex(emptyList()).isEmpty())
    }
    @Test fun standaloneAndEmbeddedKindsStayDistinct() {
        assertTrue(matchesCredentialContentTypes(key, setOf(PasswordPageContentType.API_KEY)))
        assertFalse(matchesCredentialContentTypes(key, setOf(PasswordPageContentType.API_TOKEN)))
        assertTrue(matchesCredentialContentTypes(token, setOf(PasswordPageContentType.API_TOKEN)))
        assertFalse(matchesCredentialContentTypes(token, setOf(PasswordPageContentType.API_KEY)))
        assertTrue(matchesCredentialContentTypes(gpg, setOf(PasswordPageContentType.GPG_KEY)))
        for (type in types) assertTrue(matchesCredentialContentTypes(embedded, setOf(type), types))
        assertFalse(matchesCredentialContentTypes(plain, types))
    }
    @Test fun classicListFiltersBeforeStackingAndRespectsDeletedItems() {
        fun filtered(selected: Set<PasswordPageContentType>, deleted: Set<Long> = emptySet()) = filterPreStackPasswordEntries(
            passwordEntries = listOf(key, gpg, embedded, plain, token), deletedItemIds = deleted,
            quickFoldersEnabledForCurrentFilter = false, currentFilter = CategoryFilter.All,
            configuredQuickFilterItems = PasswordListQuickFilterItem.DEFAULT_ORDER,
            quickFilterFavorite = false, quickFilter2fa = false, quickFilterNotes = false,
            quickFilterPasskey = false, quickFilterBoundNote = false, quickFilterAttachments = false,
            activeAttachmentParentIds = emptySet(), quickFilterUncategorized = false, quickFilterLocalOnly = false,
            quickFilterNeverStack = false, quickFilterWifi = false, quickFilterSshKey = false, quickFilterBarcode = false,
            effectiveNoStackEntryIds = emptySet(), hasActiveContentTypeFilter = true,
            contentTypeFilterTypes = selected, contentBlockTypes = buildPasswordContentTypeIndex(fields()))
        assertEquals(setOf(key.id, embedded.id), filtered(setOf(PasswordPageContentType.API_KEY)).map { it.id }.toSet())
        assertEquals(setOf(token.id, embedded.id), filtered(setOf(PasswordPageContentType.API_TOKEN)).map { it.id }.toSet())
        assertEquals(setOf(key.id, gpg.id, token.id), filtered(types, setOf(embedded.id)).map { it.id }.toSet())
    }
    @Test fun vaultV2UsesTheSameEmbeddedIndex() {
        val config = VaultV2VisibleListConfig(
            storageSelection = UnifiedCategoryFilterSelection.All,
            displayedContentTypes = setOf(PasswordPageContentType.API_TOKEN), configuredQuickFilterItems = emptyList(),
            quickFilterFavorite = false, quickFilter2fa = false, quickFilterNotes = false, quickFilterPasskey = false,
            quickFilterBoundNote = false, quickFilterAttachments = false, activeAttachmentParentIds = emptySet(),
            quickFilterUncategorized = false, quickFilterLocalOnly = false, quickFilterManualStackOnly = false,
            quickFilterNeverStack = false, quickFilterUnstacked = false, manualStackGroupByEntryId = emptyMap(),
            noStackEntryIds = emptySet(), normalizedQuery = "", isArchiveView = false,
            contentBlockTypes = buildPasswordContentTypeIndex(fields()))
        val items = buildVaultV2PasswordItems(listOf(key, gpg, embedded, plain, token))
        assertEquals(setOf(embedded.id, token.id), buildVaultV2VisibleListState(items, config).filteredItems.map { it.passwordEntry!!.id }.toSet())
        assertEquals(setOf(token.id), buildVaultV2VisibleListState(items, config.copy(contentBlockTypes = emptyMap())).filteredItems.map { it.passwordEntry!!.id }.toSet())
    }
    @Test fun filtersRemainAvailableWithOldSettingsAndAggregationDisabled() {
        for (enabled in listOf(true, false)) {
            val visible = resolvePasswordPageVisibleTypes(enabled, listOf(PasswordPageContentType.PASSWORD))
            assertEquals(types, sanitizeSelectedPasswordPageTypes(visible, types))
            val items = appendAggregateContentQuickFilterItems(PasswordListQuickFilterItem.DEFAULT_ORDER, visible, enabled)
            assertTrue(items.containsAll(listOf(PasswordListQuickFilterItem.API_KEY, PasswordListQuickFilterItem.API_TOKEN, PasswordListQuickFilterItem.GPG_KEY)))
        }
    }
}
