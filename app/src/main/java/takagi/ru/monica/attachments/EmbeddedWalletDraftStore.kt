package takagi.ru.monica.attachments

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.EmbeddedWalletContent
import java.io.Closeable

/** Drafts stay owned by the editor until every destination has its own complete attachment set. */
internal class EmbeddedWalletDraftStore(context: Context) : Closeable {
    private val context = context.applicationContext
    private val drafts = linkedMapOf<String, EmbeddedWalletCopyService.Prepared>()
    fun add(prepared: EmbeddedWalletCopyService.Prepared) { drafts.put(prepared.snapshot.id, prepared)?.close() }
    fun draft(id: String) = drafts[id]
    fun pendingIds(): Set<String> = drafts.keys.toSet()
    override fun close() { drafts.values.forEach { it.close() }; drafts.clear() }

    suspend fun persist(entry: PasswordEntry, snapshots: List<EmbeddedWalletContent.Snapshot>, plus: Boolean) = withContext(Dispatchers.IO) {
        val pending = snapshots.mapNotNull { drafts[it.id] }
        if (pending.isEmpty()) return@withContext
        val facade = AttachmentContainer.facade(context)
        val owner = AttachmentOwner.password(entry.id)
        val kp = entry.keepassDatabaseId?.let { AttachmentFacade.KeePassContext(it, requireNotNull(entry.keepassEntryUuid)) }
        val bw = entry.bitwardenVaultId?.let { vaultId ->
            val vault = requireNotNull(PasswordDatabase.getDatabase(context).bitwardenVaultDao().getVaultById(vaultId))
            requireNotNull(BitwardenRepository.getInstance(context).fetchAttachmentCipherSnapshot(vault, requireNotNull(entry.bitwardenCipherId))).context
        }
        try {
            for (prepared in pending) for (asset in prepared.assets.assets) {
                // UUID filenames and hashes make retry safe, including after a previous destination succeeded.
                val existing = facade.list(owner).filter { it.fileName == asset.name }
                require(existing.size <= 1) { "Duplicate copied attachment" }
                if (existing.isNotEmpty()) {
                    require(existing.single().sizeBytes == asset.size && existing.single().sha256Hex == asset.sha256)
                    // Metadata alone does not prove a prior upload is readable after interruption.
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val verifier = object : java.io.OutputStream() {
                        override fun write(value: Int) { digest.update(value.toByte()); count++ }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            digest.update(bytes, offset, length); count += length
                        }
                    }
                    facade.copyAttachmentTo(existing.single().id, verifier, bw, kp)
                    require(count == asset.size && digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256) {
                        "Copied attachment verification failed"
                    }
                    continue
                }
                facade.addStreamAttachment(AttachmentFacade.StreamUploadRequest(
                    owner = owner, source = when { bw != null -> AttachmentSource.BITWARDEN; kp != null -> AttachmentSource.KEEPASS; else -> AttachmentSource.LOCAL },
                    fileName = asset.name, mimeType = asset.mimeType, sizeBytes = asset.size,
                    openStream = { prepared.assets.open(asset.name) }, isPlusActivated = plus,
                    bitwardenContext = bw, keepassContext = kp,
                    bitwardenPremium = entry.bitwardenVaultId?.let { takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore.isPremium(context, it) } ?: true
                ))
            }
        } finally { bw?.wrappingKey?.clear() }
    }
}
