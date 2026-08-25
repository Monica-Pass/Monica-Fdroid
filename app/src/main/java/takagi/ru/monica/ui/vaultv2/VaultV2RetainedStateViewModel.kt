package takagi.ru.monica.ui.vaultv2

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

internal data class VaultV2PasswordRevision(
    val id: Long,
    val updatedAtMillis: Long,
)

internal data class VaultV2ManualStackMetadata(
    val revisions: List<VaultV2PasswordRevision>,
    val manualStackGroupByEntryId: Map<Long, String>,
    val noStackEntryIds: Set<Long>,
)

internal class VaultV2RetainedState {
    val folderNavigationHistory = mutableStateListOf<VaultV2FolderNavigationEntry>()
    val computedListSnapshots: VaultV2RetainedSourceSnapshotStore<
        VaultV2ComputedSnapshotKey,
        VaultV2ComputedSources,
        VaultV2ComputedListState
    > =
        VaultV2RetainedSourceSnapshotStore<
            VaultV2ComputedSnapshotKey,
            VaultV2ComputedSources,
            VaultV2ComputedListState
        >()
    val visibleListSnapshots =
        VaultV2RetainedSnapshotStore<VaultV2VisibleSnapshotKey, VaultV2VisibleListState>(
            maxEntries = 8
        )
    private var manualStackMetadata: VaultV2ManualStackMetadata? = null

    fun seedManualStackMetadata(
        revisions: List<VaultV2PasswordRevision>,
    ): VaultV2ManualStackMetadata? = manualStackMetadata?.takeIf { it.revisions == revisions }

    fun updateManualStackMetadata(metadata: VaultV2ManualStackMetadata) {
        manualStackMetadata = metadata
    }

    fun clear() {
        folderNavigationHistory.clear()
        computedListSnapshots.clear()
        visibleListSnapshots.clear()
        manualStackMetadata = null
    }
}

internal class VaultV2RetainedStateViewModel : ViewModel() {
    val retainedState = VaultV2RetainedState()

    override fun onCleared() {
        retainedState.clear()
    }
}
