package takagi.ru.monica.ui.vaultv2

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import takagi.ru.monica.data.VaultOverviewConfig
import takagi.ru.monica.data.VaultOverviewModule

/** Shares the vault's selection and action bar, while keeping each overview module's actions separate. */
@Stable
internal class VaultOverviewSelectionState(
    val keys: MutableList<String> = mutableStateListOf(),
) {
    private var activeModule by mutableStateOf<VaultOverviewModule?>(null)
    val module: VaultOverviewModule? get() = activeModule.takeIf { keys.isNotEmpty() }
    var searchResults by mutableStateOf<List<VaultV2Item>?>(null, referentialEqualityPolicy())
        private set

    fun updateSearchResults(items: List<VaultV2Item>) {
        searchResults = items
        activeModule = null
        if (keys.isNotEmpty()) retainVisible(items.mapTo(hashSetOf()) { it.key })
    }

    fun toggleSearch(key: String) {
        if (searchResults?.any { it.key == key } != true) return
        activeModule = null
        if (!keys.remove(key)) keys.add(key)
    }

    fun selectSearch(visibleKeys: Collection<String>) {
        val available = searchResults.orEmpty().mapTo(hashSetOf()) { it.key }
        activeModule = null
        keys.clear()
        keys.addAll(visibleKeys.distinct().filter(available::contains))
    }

    fun exitSearch() {
        searchResults = null
        clear()
    }

    fun isSelected(module: VaultOverviewModule, key: String): Boolean = this.module == module && key in keys

    fun toggle(module: VaultOverviewModule, key: String) {
        if (this.module != module) {
            selectAll(module, listOf(key))
        } else if (!keys.remove(key)) {
            keys.add(key)
        }
    }

    fun selectAll(module: VaultOverviewModule, visibleKeys: Collection<String>) {
        require(module == VaultOverviewModule.ITEMS || module == VaultOverviewModule.FAVORITES)
        activeModule = module
        keys.clear()
        keys.addAll(visibleKeys.distinct())
    }

    fun retainVisible(visibleKeys: Set<String>) {
        keys.retainAll(visibleKeys)
    }

    fun clear() {
        keys.clear()
        activeModule = null
    }
}

/** The action bar must never select hidden rows beyond a module's preview. */
internal fun VaultOverviewSnapshot.selectablePreview(
    module: VaultOverviewModule?,
    config: VaultOverviewConfig,
): List<VaultV2Item> {
    if (module == null || module.name in config.hidden || module.name in config.collapsed) return emptyList()
    return when (module) {
        VaultOverviewModule.ITEMS -> frequentItems.asSequence()
            .filterNot { it.overviewIdentity() in config.excludedFrequentItems }
            .take(OVERVIEW_PREVIEW_LIMIT).toList()
        VaultOverviewModule.FAVORITES -> favorites.take(3)
        else -> emptyList()
    }
}
