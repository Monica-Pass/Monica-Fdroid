package takagi.ru.monica.data.dedup

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.LegacyImageAttachmentSupport
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import kotlin.coroutines.coroutineContext

/** Only hashes and record IDs enter a plan. Content is copied as a bounded stream by AttachmentFacade. */
internal class DedupAttachmentSupport(private val context: Context, private val db: PasswordDatabase) {
    private val facade get() = AttachmentContainer.facade(context)
    val images = LegacyImageAttachmentSupport(context)

    class Snapshot(
        private val byOwner: Map<AttachmentOwner, List<DedupAttachmentRef>>,
        private val imageHashes: Map<String, String>
    ) {
        fun password(id: Long) = byOwner[AttachmentOwner.password(id)].orEmpty()
        fun secureItem(id: Long) = byOwner[AttachmentOwner.secureItem(id)].orEmpty()
        fun imageKey(paths: String): String = runCatching {
            LegacyImageAttachmentSupport.paths(paths).joinToString("|") { imageHashes[it].orEmpty() }
        }.getOrDefault(paths)
    }

    suspend fun snapshot(passwords: List<PasswordEntry>, items: List<SecureItem>): Snapshot {
        val rows = buildList {
            passwords.map { it.id }.distinct().chunked(800).forEach { addAll(db.attachmentDao().getActiveByParents(it)) }
            items.map { it.id }.distinct().chunked(800).forEach { addAll(db.attachmentDao().getActiveBySecureItems(it)) }
        }
        val excludedPhotos = items.associate { AttachmentOwner.secureItem(it.id) to images.representedFileNames(it) }
        val byOwner = mutableMapOf<AttachmentOwner, MutableList<DedupAttachmentRef>>()
        for (row in rows) {
            coroutineContext.ensureActive()
            val owner = row.owner ?: continue
            if (row.fileName in excludedPhotos[owner].orEmpty()) continue
            val ref = try {
                var hash = row.sha256Hex?.takeIf { SHA256.matches(it) }
                if (hash == null) {
                    val (bw, kp) = contexts(row)
                    val digest = MessageDigest.getInstance("SHA-256")
                    DigestOutputStream(object : OutputStream() {
                        override fun write(value: Int) = Unit
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                    }, digest).use { facade.copyAttachmentTo(row.id, it, bw, kp) }
                    hash = digest.digest().toHex()
                }
                val readable = row.sourceEnum != AttachmentSource.LOCAL || hasLocalContent(row)
                DedupAttachmentRef(row.id, contentKey(row, hash), readable)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Keep the row in the plan. Writing it must fail explicitly, never create a partial success.
                DedupAttachmentRef(row.id, "unreadable:${row.id}", false)
            }
            byOwner.getOrPut(owner) { mutableListOf() }.add(ref)
        }
        val hashes = mutableMapOf<String, String>()
        for (item in items) {
            val paths = runCatching { LegacyImageAttachmentSupport.paths(item.imagePaths) }.getOrDefault(emptyList())
            for (path in paths.filter { it.isNotBlank() && it !in hashes }) {
                hashes[path] = try { images.contentHash(path) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    "unreadable:$path"
                }
            }
        }
        return Snapshot(byOwner, hashes)
    }

    suspend fun copy(refs: List<DedupAttachmentRef>, target: AttachmentOwner) {
        check(refs.all { it.readable }) { "Source attachment is unavailable" }
        val rows = refs.map { ref -> checkNotNull(db.attachmentDao().getById(ref.attachmentId)) }
        for ((owner, group) in rows.groupBy { checkNotNull(it.owner) }) {
            val needingDownload = group.firstOrNull { !hasLocalContent(it) }
            val (bw, kp) = needingDownload?.let { contexts(it) } ?: (null to null)
            facade.cloneAttachmentsToNewOwner(owner, target, bw, kp, attachmentIds = group.map { it.id }.toSet())
        }
        val copiedKeys = facade.list(target).map { contentKey(it, checkNotNull(it.sha256Hex)) }.toSet()
        check(copiedKeys == refs.map { it.contentKey }.toSet()) { "Attachment content changed while copying" }
    }

    suspend fun rollback(owner: AttachmentOwner) {
        // Also deletes MDBX blobs while the parent still exists. Never calls the source backend.
        var failure: Exception? = null
        for (attachment in facade.list(owner)) {
            try { facade.forgetLocalAttachment(attachment.id) } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    suspend fun persistImages(item: SecureItem) = images.persistInMdbx(item, facade)

    private fun hasLocalContent(row: Attachment): Boolean = !row.wrappedCek.isNullOrBlank() &&
        row.localPath?.let { path ->
            val base = File(context.filesDir, "secure_attachments").canonicalFile
            val file = File(base, path).canonicalFile
            file.parentFile == base && file.isFile && file.length() > 0L
        } == true

    private suspend fun contexts(row: Attachment): Pair<AttachmentFacade.BitwardenContext?, AttachmentFacade.KeePassContext?> {
        val password = row.parentPasswordId?.let { db.passwordEntryDao().getPasswordEntryById(it) }
        val item = row.parentSecureItemId?.let { db.secureItemDao().getItemById(it) }
        val keepassId = password?.keepassDatabaseId ?: item?.keepassDatabaseId
        val entryUuid = password?.keepassEntryUuid ?: item?.keepassEntryUuid
        val kp = if (keepassId != null && !entryUuid.isNullOrBlank()) AttachmentFacade.KeePassContext(keepassId, entryUuid) else null
        if (row.sourceEnum != AttachmentSource.BITWARDEN || hasLocalContent(row)) return null to kp
        val vaultId = password?.bitwardenVaultId ?: item?.bitwardenVaultId ?: throw AttachmentError.BitwardenLocked
        val cipherId = password?.bitwardenCipherId ?: item?.bitwardenCipherId
        val vault = db.bitwardenVaultDao().getVaultById(vaultId) ?: throw AttachmentError.BitwardenLocked
        val repository = BitwardenRepository.getInstance(context)
        val bw = cipherId?.takeIf { it.isNotBlank() }?.let { repository.fetchAttachmentCipherSnapshot(vault, it)?.context }
            ?: repository.getAttachmentBitwardenContext(vault, cipherId)
        return bw to kp
    }

    private fun contentKey(row: Attachment, hash: String): String = listOf(
        row.fileName, row.mimeType, hash.lowercase(Locale.ROOT)
    ).joinToString("") { "${it.length}:$it" }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object { val SHA256 = Regex("[0-9a-fA-F]{64}") }
}
