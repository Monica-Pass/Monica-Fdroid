package takagi.ru.monica.ui.screens

import java.util.UUID

internal fun toggleNativeManagerEntrySelection(
    selected: Set<UUID>,
    entryUuid: UUID,
): Set<UUID> {
    return if (entryUuid in selected) selected - entryUuid else selected + entryUuid
}

internal fun toggleNativeManagerSelectAll(
    selected: Set<UUID>,
    visible: Set<UUID>,
): Set<UUID> {
    if (visible.isEmpty()) return selected
    return if (visible.all { it in selected }) {
        selected - visible
    } else {
        selected + visible
    }
}

internal fun shouldShowNativeManagerSearch(
    searchExpanded: Boolean,
    query: String,
): Boolean = searchExpanded || query.isNotBlank()

internal data class NativeManagerListSummary(
    val folderCount: Int,
    val entryCount: Int,
) {
    val totalCount: Int get() = folderCount + entryCount

    fun compactLabel(folderLabel: String, entryLabel: String): String =
        "$folderCount $folderLabel · $entryCount $entryLabel"
}
