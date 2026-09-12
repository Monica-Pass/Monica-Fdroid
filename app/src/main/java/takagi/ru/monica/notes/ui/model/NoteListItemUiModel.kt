package takagi.ru.monica.notes.ui.model

import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.notes.domain.DecodedNoteContent
import takagi.ru.monica.notes.domain.NoteContentCodec
import java.util.Date

data class NoteListItemUiModel(
    val id: Long,
    val title: String,
    val rawContent: String,
    val isMarkdown: Boolean,
    val inlineImageIds: List<String>,
    val previewText: String,
    val tags: List<String>,
    val updatedAt: Date,
    val hasImageAttachment: Boolean,
    val syncStatus: SyncStatus?
)

internal fun SecureItem.toNoteListItemUiModel(decoded: DecodedNoteContent): NoteListItemUiModel {
    val images = NoteContentCodec.decodeImagePaths(imagePaths)
    val resolvedSyncStatus = if (bitwardenVaultId != null) {
        when (syncStatus) {
            "PENDING" -> SyncStatus.PENDING
            "SYNCING" -> SyncStatus.SYNCING
            "SYNCED" -> SyncStatus.SYNCED
            "FAILED" -> SyncStatus.FAILED
            "CONFLICT" -> SyncStatus.CONFLICT
            else -> if (bitwardenLocalModified) SyncStatus.PENDING else SyncStatus.SYNCED
        }
    } else null
    return NoteListItemUiModel(
        id = id,
        title = title,
        rawContent = decoded.content,
        isMarkdown = decoded.isMarkdown,
        inlineImageIds = (NoteContentCodec.extractInlineImageIds(decoded.content) + images).distinct(),
        previewText = NoteContentCodec.toPlainPreview(decoded.content, decoded.isMarkdown),
        tags = decoded.tags,
        updatedAt = updatedAt,
        hasImageAttachment = images.isNotEmpty(),
        syncStatus = resolvedSyncStatus
    )
}
