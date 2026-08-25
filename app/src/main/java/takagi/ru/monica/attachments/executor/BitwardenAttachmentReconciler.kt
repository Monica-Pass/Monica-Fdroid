package takagi.ru.monica.attachments.executor

import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.repository.AttachmentRepository
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import java.net.URLConnection

internal data class BitwardenAttachmentMetadataUpdate(
    val attachment: Attachment,
    val invalidateCache: Boolean
)

/**
 * Bitwarden `/sync` reports the encrypted attachment size, while [Attachment.sizeBytes]
 * stores the plaintext size. Size alone therefore cannot be used as a content-change signal.
 */
internal fun planBitwardenAttachmentMetadataUpdate(
    local: Attachment,
    remote: CipherAttachmentApiData,
    now: Long
): BitwardenAttachmentMetadataUpdate? {
    val remoteFileName = remote.fileName?.takeIf { it.isNotBlank() }
    val remoteFileKey = remote.key?.takeIf { it.isNotBlank() }
    val remoteUrl = remote.url?.takeIf { it.isNotBlank() }
    val contentChanged =
        (remoteFileName != null && remoteFileName != local.fileName) ||
            (remoteFileKey != null && remoteFileKey != local.bitwardenFileKeyEnc)

    if (contentChanged) {
        val resolvedFileName = remoteFileName ?: local.fileName
        return BitwardenAttachmentMetadataUpdate(
            attachment = local.copy(
                fileName = resolvedFileName,
                mimeType = guessBitwardenAttachmentMimeType(resolvedFileName),
                sizeBytes = 0L,
                bitwardenUrl = remoteUrl ?: local.bitwardenUrl,
                bitwardenFileKeyEnc = remoteFileKey ?: local.bitwardenFileKeyEnc,
                localPath = null,
                wrappedCek = null,
                sha256Hex = null,
                downloadState = AttachmentDownloadState.PENDING.name,
                updatedAt = now
            ),
            invalidateCache = true
        )
    }

    if (remoteUrl != null && remoteUrl != local.bitwardenUrl) {
        return BitwardenAttachmentMetadataUpdate(
            attachment = local.copy(
                bitwardenUrl = remoteUrl,
                updatedAt = now
            ),
            invalidateCache = false
        )
    }
    return null
}

/**
 * Bitwarden 附件元数据对齐器。
 *
 * 每次拉完 cipher 同步后调用一次 [reconcile]：
 * - 服务端 `attachments == null` → 保持本地不变；
 * - 服务端 `attachments == []` → 视作远端清空，删除该密码下所有 `source = BITWARDEN` 的记录；
 * - 否则按 `bitwardenAttachmentId` 做差异合并：
 *   - 新增：`PENDING` 写入，`downloadState = PENDING`；
 *   - 移除：删除本地记录与缓存；
 *   - 更新：文件名或附件密钥变化时清理缓存；仅下载地址变化时保留缓存。
 *   - 服务端 `size` 是密文大小，本地 `sizeBytes` 是明文大小，两者不参与变更判断。
 *
 * 本类不做任何网络调用，也不下载字节。字节下载走 [BitwardenAttachmentExecutor.download]。
 * 对应 requirements.md Requirement 5.1 / 5.2 / 9.3 / 9.4。
 */
class BitwardenAttachmentReconciler(
    private val repository: AttachmentRepository,
    private val storage: AttachmentStorage
) {

    data class Report(
        val inserted: Int = 0,
        val removed: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0
    )

    suspend fun reconcile(
        passwordId: Long,
        remoteAttachments: List<CipherAttachmentApiData>?
    ): Report = reconcile(AttachmentOwner.password(passwordId), remoteAttachments)

    suspend fun reconcile(
        owner: AttachmentOwner,
        remoteAttachments: List<CipherAttachmentApiData>?
    ): Report {
        if (remoteAttachments == null) return Report(skipped = 1)
        val remoteById = remoteAttachments.associateBy { it.id }
        val local = repository.listByOwnerAndSource(owner, AttachmentSource.BITWARDEN)
        val localById = local.mapNotNull { attach ->
            val id = attach.bitwardenAttachmentId ?: return@mapNotNull null
            id to attach
        }.toMap()

        var inserted = 0
        var removed = 0
        var updated = 0

        // 1. 本地存在但远端缺失 → 删除
        for ((attachmentId, localAttach) in localById) {
            if (attachmentId !in remoteById) {
                localAttach.localPath?.let { storage.delete(it) }
                repository.deleteById(localAttach.id)
                removed++
            }
        }

        // 2. 远端存在
        val now = System.currentTimeMillis()
        for ((attachmentId, remote) in remoteById) {
            val localAttach = localById[attachmentId]
            if (localAttach == null) {
                repository.insert(
                    Attachment(
                        id = 0,
                        parentPasswordId = owner.passwordId,
                        parentSecureItemId = owner.secureItemId,
                        source = AttachmentSource.BITWARDEN.name,
                        fileName = remote.fileName ?: DEFAULT_FILE_NAME,
                        mimeType = guessMimeType(remote.fileName),
                        sizeBytes = 0L,
                        bitwardenAttachmentId = attachmentId,
                        bitwardenUrl = remote.url,
                        bitwardenFileKeyEnc = remote.key,
                        downloadState = AttachmentDownloadState.PENDING.name,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                inserted++
            } else {
                val update = planBitwardenAttachmentMetadataUpdate(localAttach, remote, now)
                if (update != null) {
                    if (update.invalidateCache) {
                        localAttach.localPath?.let { storage.delete(it) }
                    }
                    repository.update(update.attachment)
                    updated++
                }
            }
        }

        return Report(inserted = inserted, removed = removed, updated = updated)
    }

    private fun guessMimeType(fileName: String?): String {
        return guessBitwardenAttachmentMimeType(fileName)
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "attachment"
    }
}

private fun guessBitwardenAttachmentMimeType(fileName: String?): String {
    if (fileName.isNullOrBlank()) return "application/octet-stream"
    return URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
}
