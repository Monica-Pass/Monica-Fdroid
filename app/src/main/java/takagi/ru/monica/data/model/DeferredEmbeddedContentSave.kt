package takagi.ru.monica.data.model

import takagi.ru.monica.data.CustomFieldDraft
import java.util.concurrent.ConcurrentHashMap

/** Publish a copied item's metadata only after all its independently owned bytes exist. */
class DeferredEmbeddedContentSave(private val pendingSnapshotIds: Set<String>) {
    data class Commit(val fields: List<CustomFieldDraft>, val boundNoteId: Long?)
    private val commits = ConcurrentHashMap<Long, Commit>()
    private fun pending(field: CustomFieldDraft): Boolean =
        EmbeddedWalletContent.isMetadata(field.title) &&
            (EmbeddedWalletContent.read(field.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot?.id in pendingSnapshotIds

    fun hasPending(fields: List<CustomFieldDraft>) = fields.any(::pending)
    fun initialFields(requested: List<CustomFieldDraft>, previous: List<CustomFieldDraft>): List<CustomFieldDraft> {
        val replacing = requested.filter(::pending).map { it.title }.toSet()
        return requested.filterNot(::pending) + previous.filter { it.title in replacing }
    }
    fun replacesNote(fields: List<CustomFieldDraft>) = fields.any { pending(it) && it.title == EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.NOTE) }
    fun record(id: Long, fields: List<CustomFieldDraft>, boundNoteId: Long?) {
        if (hasPending(fields)) commits[id] = Commit(fields.toList(), boundNoteId)
    }
    fun commit(id: Long): Commit? = commits[id]
}
