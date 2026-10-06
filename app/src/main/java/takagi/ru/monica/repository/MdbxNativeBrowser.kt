package takagi.ru.monica.repository

import uniffi.mdbx_ffi.*
import java.util.UUID

/** Navigation metadata only. Payloads are disclosed one at a time in the detail page. */
internal data class MdbxNativeBrowser(
    val nodes: List<MdbxStructureNode>,
    val objects: Map<String, MdbxNativeObjectSummary>,
)

internal data class MdbxNativeObjectSummary(
    val id: String,
    val collectionId: String,
    val type: String,
    val title: String,
    val version: UInt,
    val revision: String,
)

internal data class MdbxNativeObjectDetail(
    val summary: MdbxNativeObjectSummary,
    val payload: String,
    val attachments: List<Pair<String, Long>>,
    val attachmentRecords: List<MdbxNativeAttachment> = emptyList(),
)

internal data class MdbxNativeAttachment(val id: String, val fileName: String, val mimeType: String?, val size: Long, val sha256: String)

internal fun readMdbxNativeBrowser(vault: MdbxVault): MdbxNativeBrowser {
    val collections = mutableListOf<MdbxCollectionSummary>()
    var cursor: String? = null
    val seen = mutableSetOf<String>()
    do {
        val page = vault.listCollectionSummaries(200u, cursor)
        collections += page.items.filterNot { it.deleted }
        cursor = page.nextCursor
        check(cursor == null || (page.items.isNotEmpty() && seen.add(cursor)))
    } while (cursor != null)
    val rootId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
    val summaries = mutableListOf<MdbxObjectSummary>()
    collections.forEach { folder ->
        cursor = null
        seen.clear()
        do {
            val page = vault.listObjectSummaries(folder.collectionId, null, 200u, cursor)
            summaries += page.items.filterNot { it.deleted }
            cursor = page.nextCursor
            check(cursor == null || (page.items.isNotEmpty() && seen.add(cursor!!)))
        } while (cursor != null)
    }
    val counts = summaries.groupingBy { it.collectionId }.eachCount()
    val nodes = collections.filterNot { it.collectionId == rootId }.map { folder ->
        MdbxStructureNode(folder.collectionId, folder.groupId?.takeUnless { it == rootId }, folder.title,
            MdbxStructureNodeType.FOLDER, folder.title, MdbxStructureNodeStatus.UNCHANGED,
            counts[folder.collectionId] ?: 0, "")
    } + summaries.map { entry ->
        MdbxStructureNode(entry.objectId, entry.collectionId.takeUnless { it == rootId }, entry.title,
            MdbxStructureNodeType.ENTRY, entry.title, MdbxStructureNodeStatus.UNCHANGED, 0, entry.objectTypeId)
    }
    return MdbxNativeBrowser(nodes, summaries.associate { it.objectId to it.nativeBrowserSummary() })
}

internal fun MdbxObjectSummary.nativeBrowserSummary() = MdbxNativeObjectSummary(
    objectId, collectionId, objectTypeId, title, payloadSchemaVersion, headCommitId)

internal fun readMdbxNativeObject(vault: MdbxVault, id: String): MdbxNativeObjectDetail {
    val summary = requireNotNull(vault.getObjectSummary(id)).also { check(!it.deleted) }
    val record = vault.revealObjectWithLimits(id, MdbxObjectDisclosureLimits(4uL * 1024uL * 1024uL)).`object`
        ?: error("MDBX object disclosure was not authorized")
    check(!record.deleted && record.collectionId == summary.collectionId)
    val attachments = vault.listAttachments(summary.collectionId, id).filterNot { it.deleted }
    return MdbxNativeObjectDetail(summary.nativeBrowserSummary(), record.payloadJson,
        attachments.map { it.fileName to it.originalSize.toLong() },
        attachments.map { MdbxNativeAttachment(it.attachmentId, it.fileName, it.mediaType, it.originalSize.toLong(), it.contentHash) })
}

internal suspend fun saveMdbxNativeObject(vault: MdbxVault, original: MdbxNativeObjectDetail?, title: String,
    type: String, payload: String, uploads: List<takagi.ru.monica.data.NativeApiTokenUpload>, removedAttachmentIds: Set<String>, targetCollectionId: String? = null): String {
    require(title.isNotBlank() && title.length <= 512)
    require(type.isNotBlank() && type.length <= 128)
    require(payload.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Native entry exceeds 1 MiB." }
    require(kotlinx.serialization.json.Json.parseToJsonElement(payload) is kotlinx.serialization.json.JsonObject)
    val rootId = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
    val collectionId = original?.summary?.collectionId ?: targetCollectionId?.takeIf { it.isNotBlank() } ?: rootId
    if (original == null && collectionId != rootId) {
        check(vault.getCollectionSummary(collectionId)?.deleted == false) { "The destination folder is unavailable. Reopen the editor." }
    }
    val id = original?.summary?.id ?: UUID.randomUUID().toString()
    if (original != null) check(readMdbxNativeObject(vault, id) == original) { "The entry changed. Reload before saving." }
    val previous = original?.attachmentRecords.orEmpty()
    require(removedAttachmentIds.all { removed -> previous.any { it.id == removed } })
    require(previous.size - removedAttachmentIds.size + uploads.size <= 16) { "At most 16 attachments are supported." }
    val buffers = mutableListOf<ByteArray>()
    try {
        var remaining = 16L * 1024L * 1024L
        val attachments = uploads.map { upload ->
            require(upload.fileName.isNotBlank() && upload.fileName.length <= 512)
            val bytes = upload.open().use { takagi.ru.monica.data.NativeApiTokenAssets.readBounded(it, remaining) }
            buffers += bytes
            remaining -= bytes.size
            takagi.ru.monica.data.NativeApiTokenAssets.validate(bytes, upload.expectedSize, upload.expectedSha256)
            MdbxAttachmentBatchCommand.Create(UUID.randomUUID().toString(), collectionId, id, upload.fileName, upload.mimeType, bytes)
        } + removedAttachmentIds.map { MdbxAttachmentBatchCommand.Delete(it) }
        val commands = buildList {
            if (original == null && vault.getCollectionSummary(collectionId) == null) {
                add(MdbxWriteCommand.CreateProject(rootId, Mdbx2VaultSessionExecutor.ROOT_PROJECT_TITLE))
            }
            add(if (original == null) MdbxWriteCommand.CreateEntry(id, collectionId, type, title, payload)
                else MdbxWriteCommand.UpdateEntry(id, collectionId, type, title, payload))
        }
        if (attachments.isEmpty()) vault.executeWriteOperation(UUID.randomUUID().toString(), "monica-native-edit", commands)
        else vault.executeCompositeWriteOperation(UUID.randomUUID().toString(), "monica-native-edit", commands, attachments)
        return id
    } finally { buffers.forEach { it.fill(0) } }
}

internal fun deleteMdbxNativeObject(vault: MdbxVault, original: MdbxNativeObjectDetail) {
    check(readMdbxNativeObject(vault, original.summary.id) == original) { "The entry changed. Reload before deleting." }
    val commands = listOf(MdbxWriteCommand.DeleteEntry(original.summary.id, original.summary.collectionId))
    val attachments = original.attachmentRecords.map { MdbxAttachmentBatchCommand.Delete(it.id) }
    if (attachments.isEmpty()) vault.executeWriteOperation(UUID.randomUUID().toString(), "monica-native-delete", commands)
    else vault.executeCompositeWriteOperation(UUID.randomUUID().toString(), "monica-native-delete", commands, attachments)
}

internal fun readMdbxNativeAttachment(vault: MdbxVault, original: MdbxNativeObjectDetail, attachmentId: String): ByteArray {
    check(vault.getObjectSummary(original.summary.id)?.nativeBrowserSummary() == original.summary) { "The entry changed. Reload it." }
    val expected = original.attachmentRecords.single { it.id == attachmentId }
    require(expected.size <= 16L * 1024L * 1024L)
    val current = requireNotNull(vault.getAttachment(attachmentId))
    check(!current.deleted && current.entryId == original.summary.id && current.projectId == original.summary.collectionId &&
        current.contentHash == expected.sha256 && current.originalSize.toLong() == expected.size)
    val bytes = vault.readAttachmentContent(attachmentId, 16uL * 1024uL * 1024uL)
    try { takagi.ru.monica.data.NativeApiTokenAssets.validate(bytes, expected.size, expected.sha256); return bytes }
    catch (failure: Throwable) { bytes.fill(0); throw failure }
}

/** Rename metadata only. Keep type, schema version, full JSON, labels and attachments intact. */
internal fun renameMdbxNativeObject(vault: MdbxVault, expected: MdbxNativeObjectSummary, title: String) {
    require(title.isNotBlank() && title.length <= 512)
    val current = requireNotNull(vault.getObjectSummary(expected.id))
    check(!current.deleted && current.nativeBrowserSummary() == expected) { "MDBX object changed; reload before saving" }
    if (title == current.title) return
    val record = vault.revealObjectWithLimits(expected.id, MdbxObjectDisclosureLimits(4uL * 1024uL * 1024uL)).`object`
        ?: error("MDBX object disclosure was not authorized")
    vault.executeWriteOperation(UUID.randomUUID().toString(), "monica-native-rename", listOf(
        MdbxWriteCommand.UpdateEntry(expected.id, current.collectionId, current.objectTypeId, title, record.payloadJson)))
}

/** Invalid parent links stay reachable; this changes presentation only, never the vault. */
internal class MdbxBrowserIndex(nodes: List<MdbxStructureNode>) {
    val nodes = nodes.distinctBy { it.type to it.id }
    val folders = this.nodes.filter { it.type == MdbxStructureNodeType.FOLDER }.associateBy { it.id }
    private val parents = folders.mapValues { (id, node) ->
        var cursor = node.parentId
        val seen = mutableSetOf(id)
        var cyclic = false
        while (cursor != null && cursor in folders) {
            if (!seen.add(cursor)) { cyclic = true; break }
            cursor = folders[cursor]?.parentId
        }
        node.parentId.takeUnless { cyclic || it !in folders }
    }
    fun parent(node: MdbxStructureNode): String? = if (node.type == MdbxStructureNodeType.FOLDER)
        parents[node.id] else node.parentId?.takeIf { it in folders }
    private val children = this.nodes.groupBy(::parent)
    fun children(id: String?) = children[id].orEmpty()
    fun ancestors(id: String?): List<MdbxStructureNode> {
        val chain = mutableListOf<MdbxStructureNode>()
        var cursor = id
        val seen = mutableSetOf<String>()
        while (cursor != null && seen.add(cursor)) {
            val folder = folders[cursor] ?: break
            chain += folder
            cursor = parents[cursor]
        }
        return chain.asReversed()
    }
    fun canMoveFolder(source: String, target: String?): Boolean = source in folders &&
        (target == null || target in folders) && source != target &&
        ancestors(target).none { it.id == source }
}
