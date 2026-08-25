package takagi.ru.monica.ui.password

import androidx.lifecycle.ViewModel
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.ui.PasswordGroupingConfig
import takagi.ru.monica.viewmodel.CategoryFilter

internal data class PasswordAggregateSnapshotKey(
    val displayedContentTypes: Set<PasswordPageContentType>,
    val searchQuery: String,
    val categoryFilter: CategoryFilter,
    val localCategoryIdsInScope: Set<Long>,
)

internal data class PasswordAggregateSnapshotSeed(
    val items: List<PasswordAggregateListItemUi>,
    val hasSnapshot: Boolean,
)

internal data class PasswordGroupingSnapshotKey(
    val sourceEntries: List<PasswordEntry>,
    val config: PasswordGroupingConfig,
)

internal data class PasswordGroupingSnapshotSeed(
    val groups: Map<String, List<PasswordEntry>>,
    val hasSnapshot: Boolean,
)

internal data class PasswordGroupingEntryRevision(
    val id: Long,
    val updatedAtMillis: Long,
)

internal data class PasswordManualStackMetadata(
    val revisions: List<PasswordGroupingEntryRevision>,
    val manualStackGroupByEntryId: Map<Long, String>,
    val noStackEntryIds: Set<Long>,
)

internal class PasswordAggregateRetainedState {
    private var snapshotKey: PasswordAggregateSnapshotKey? = null
    private var snapshotItems: List<PasswordAggregateListItemUi> = emptyList()
    private var groupingSnapshotKey: PasswordGroupingSnapshotKey? = null
    private var groupingSnapshotGroups: Map<String, List<PasswordEntry>> = emptyMap()
    private var manualStackMetadata: PasswordManualStackMetadata? = null
    private var generation: Long = 0L

    fun currentGeneration(): Long = generation

    fun seed(key: PasswordAggregateSnapshotKey): PasswordAggregateSnapshotSeed {
        val matches = snapshotKey == key
        return PasswordAggregateSnapshotSeed(
            items = if (matches) snapshotItems else emptyList(),
            hasSnapshot = matches,
        )
    }

    fun updateIfCurrent(
        expectedGeneration: Long,
        key: PasswordAggregateSnapshotKey,
        items: List<PasswordAggregateListItemUi>,
    ): Boolean {
        if (generation != expectedGeneration) return false
        snapshotKey = key
        snapshotItems = items
        return true
    }

    fun groupingSeed(key: PasswordGroupingSnapshotKey): PasswordGroupingSnapshotSeed {
        val matches = groupingSnapshotKey == key
        val compatible = groupingSnapshotKey?.isCompatibleWith(key) == true
        return PasswordGroupingSnapshotSeed(
            groups = if (matches || compatible) groupingSnapshotGroups else emptyMap(),
            hasSnapshot = matches || compatible,
        )
    }

    fun updateGroupingIfCurrent(
        expectedGeneration: Long,
        key: PasswordGroupingSnapshotKey,
        groups: Map<String, List<PasswordEntry>>,
    ): Boolean {
        if (generation != expectedGeneration) return false
        groupingSnapshotKey = key
        groupingSnapshotGroups = groups
        return true
    }

    fun seedManualStackMetadata(
        revisions: List<PasswordGroupingEntryRevision>,
    ): PasswordManualStackMetadata? = manualStackMetadata?.takeIf {
        it.revisions == revisions
    }

    fun updateManualStackMetadata(metadata: PasswordManualStackMetadata) {
        manualStackMetadata = metadata
    }

    fun clear() {
        generation += 1L
        snapshotKey = null
        snapshotItems = emptyList()
        groupingSnapshotKey = null
        groupingSnapshotGroups = emptyMap()
        manualStackMetadata = null
    }
}

private fun PasswordGroupingSnapshotKey.isCompatibleWith(
    other: PasswordGroupingSnapshotKey,
): Boolean {
    if (sourceEntries != other.sourceEntries) return false
    return config.isStructurallyEqualTo(other.config)
}

private fun PasswordGroupingConfig.isStructurallyEqualTo(
    other: PasswordGroupingConfig,
): Boolean =
    isLocalOnlyView == other.isLocalOnlyView &&
        effectiveStackCardMode == other.effectiveStackCardMode &&
        effectiveGroupMode == other.effectiveGroupMode &&
        websiteStackMatchMode == other.websiteStackMatchMode &&
        untitledLabel == other.untitledLabel

internal class PasswordAggregateRetainedStateViewModel : ViewModel() {
    val retainedState = PasswordAggregateRetainedState()

    override fun onCleared() {
        retainedState.clear()
    }
}
