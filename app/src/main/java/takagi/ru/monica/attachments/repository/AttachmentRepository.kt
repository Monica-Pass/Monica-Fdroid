package takagi.ru.monica.attachments.repository

import kotlinx.coroutines.flow.Flow
import takagi.ru.monica.attachments.data.AttachmentDao
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource

/**
 * 附件元数据仓库，对 ViewModel / Facade 暴露 flow 优先、挂起风格的 API。
 *
 * 这里只负责 Room 层的读写事务，不处理加密、网络或 Bitwarden/KeePass 特化逻辑，
 * 那些职责在 storage / executor / facade 子包中。
 */
class AttachmentRepository(
    private val dao: AttachmentDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    // ---------------------------------------------------------------- 读

    fun observeByPassword(passwordId: Long): Flow<List<Attachment>> =
        observe(AttachmentOwner.password(passwordId))

    fun observeBySecureItem(secureItemId: Long): Flow<List<Attachment>> =
        observe(AttachmentOwner.secureItem(secureItemId))

    fun observe(owner: AttachmentOwner): Flow<List<Attachment>> = when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.observeActiveByParent(owner.id)
        AttachmentOwner.Kind.SECURE_ITEM -> dao.observeActiveBySecureItem(owner.id)
    }

    suspend fun listByPassword(passwordId: Long, includeDeleted: Boolean = false): List<Attachment> =
        list(AttachmentOwner.password(passwordId), includeDeleted)

    suspend fun listBySecureItem(
        secureItemId: Long,
        includeDeleted: Boolean = false
    ): List<Attachment> = list(AttachmentOwner.secureItem(secureItemId), includeDeleted)

    suspend fun list(
        owner: AttachmentOwner,
        includeDeleted: Boolean = false
    ): List<Attachment> = when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD ->
            if (includeDeleted) dao.getAllByParent(owner.id) else dao.getActiveByParent(owner.id)
        AttachmentOwner.Kind.SECURE_ITEM ->
            if (includeDeleted) dao.getAllBySecureItem(owner.id) else dao.getActiveBySecureItem(owner.id)
    }

    suspend fun getById(id: Long): Attachment? = dao.getById(id)

    suspend fun getByBitwardenAttachmentId(attachmentId: String): Attachment? =
        dao.findByBitwardenAttachmentId(attachmentId)

    suspend fun listByParentAndSource(passwordId: Long, source: AttachmentSource): List<Attachment> =
        listByOwnerAndSource(AttachmentOwner.password(passwordId), source)

    suspend fun listByOwnerAndSource(
        owner: AttachmentOwner,
        source: AttachmentSource
    ): List<Attachment> = when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.getByParentAndSource(owner.id, source.name)
        AttachmentOwner.Kind.SECURE_ITEM -> dao.getBySecureItemAndSource(owner.id, source.name)
    }

    suspend fun countActive(passwordId: Long): Int = countActive(AttachmentOwner.password(passwordId))

    suspend fun countActive(owner: AttachmentOwner): Int = when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.countActiveByParent(owner.id)
        AttachmentOwner.Kind.SECURE_ITEM -> dao.countActiveBySecureItem(owner.id)
    }

    /** 返回 [passwordIds] 中存在未软删附件的 id 集合。 */
    suspend fun idsWithActiveAttachments(passwordIds: List<Long>): Set<Long> {
        if (passwordIds.isEmpty()) return emptySet()
        return dao.parentsWithActiveAttachments(passwordIds).toSet()
    }

    suspend fun secureItemIdsWithActiveAttachments(secureItemIds: List<Long>): Set<Long> {
        if (secureItemIds.isEmpty()) return emptySet()
        return dao.secureItemsWithActiveAttachments(secureItemIds).toSet()
    }

    /** 返回当前数据库里所有仍被引用的本地密文文件相对路径。 */
    suspend fun allReferencedLocalPaths(): Set<String> =
        dao.selectAllLocalPaths().filterNotNull().toSet()

    /** 列出所有"本地已下载 + 未软删除"的附件（用于备份 / 迁移）。 */
    suspend fun listAllActiveLocalAttachments(): List<Attachment> =
        dao.selectAllActiveLocalAttachments()

    /**
     * 把某密码下 [fromSource] 的附件改写为 LOCAL（清 Bitwarden/KeePass 专属字段）。
     * 返回影响的行数。
     */
    suspend fun convertSourceToLocal(
        passwordId: Long,
        fromSource: AttachmentSource
    ): Int = convertSourceToLocal(AttachmentOwner.password(passwordId), fromSource)

    suspend fun convertSourceToLocal(
        owner: AttachmentOwner,
        fromSource: AttachmentSource
    ): Int {
        if (fromSource == AttachmentSource.LOCAL) return 0
        return when (owner.kind) {
            AttachmentOwner.Kind.PASSWORD -> dao.rewriteSourceToLocal(
                passwordId = owner.id,
                fromSource = fromSource.name,
                now = clock()
            )
            AttachmentOwner.Kind.SECURE_ITEM -> dao.rewriteSecureItemSourceToLocal(
                secureItemId = owner.id,
                fromSource = fromSource.name,
                now = clock()
            )
        }
    }

    // ---------------------------------------------------------------- 写

    suspend fun insert(attachment: Attachment): Long = dao.insert(
        attachment.copy(
            createdAt = if (attachment.createdAt == 0L) clock() else attachment.createdAt,
            updatedAt = if (attachment.updatedAt == 0L) clock() else attachment.updatedAt
        )
    )

    suspend fun update(attachment: Attachment): Int =
        dao.update(attachment.copy(updatedAt = clock()))

    suspend fun markDownloadState(id: Long, state: AttachmentDownloadState) =
        dao.updateDownloadState(id, state.name, clock())

    // ---------------------------------------------------------------- 删

    /** 永久删除单条附件的元数据，不处理底层密文文件的清理。 */
    suspend fun deleteById(id: Long): Int = dao.deleteById(id)

    /** 永久清空某个密码的所有附件元数据，用于密码永久删除级联。 */
    suspend fun purgeByPassword(passwordId: Long): Int = dao.purgeByParent(passwordId)

    suspend fun purge(owner: AttachmentOwner): Int = when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.purgeByParent(owner.id)
        AttachmentOwner.Kind.SECURE_ITEM -> dao.purgeBySecureItem(owner.id)
    }

    /** 随密码软删除一并软删附件（仅元数据标记，不释放密文）。 */
    suspend fun softDeleteByPassword(passwordId: Long): Int {
        return softDelete(AttachmentOwner.password(passwordId))
    }

    suspend fun softDelete(owner: AttachmentOwner): Int {
        val now = clock()
        return when (owner.kind) {
            AttachmentOwner.Kind.PASSWORD ->
                dao.softDeleteByParent(owner.id, deletedAt = now, updatedAt = now)
            AttachmentOwner.Kind.SECURE_ITEM ->
                dao.softDeleteBySecureItem(owner.id, deletedAt = now, updatedAt = now)
        }
    }

    /** 与密码从回收站恢复联动。 */
    suspend fun restoreByPassword(passwordId: Long): Int =
        restore(AttachmentOwner.password(passwordId))

    suspend fun restore(owner: AttachmentOwner): Int = when (owner.kind) {
        AttachmentOwner.Kind.PASSWORD -> dao.restoreByParent(owner.id, updatedAt = clock())
        AttachmentOwner.Kind.SECURE_ITEM -> dao.restoreBySecureItem(owner.id, updatedAt = clock())
    }
}
