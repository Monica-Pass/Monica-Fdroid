package takagi.ru.monica.repository

import uniffi.mdbx_ffi.*
import java.util.UUID
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal data class MdbxManagerAsset(val name: String, val mime: String?, val content: ByteArray)
internal data class MdbxManagerObject(val summary: MdbxNativeObjectSummary, val payload: String,
    val assets: List<MdbxManagerAsset>, val labels: List<MdbxObjectLabelRecord>) {
    fun clear() = assets.forEach { it.content.fill(0) }
}

private fun managerPayloadInFolder(payload: String, folder: String?): String {
    val value = Json.parseToJsonElement(payload) as? JsonObject ?: return payload
    if ("monica_entry_id" !in value) return payload
    return JsonObject(value + ("mdbx_folder_id" to (folder?.let(::JsonPrimitive) ?: JsonNull))).toString()
}

internal fun captureMdbxManagerObject(vault: MdbxVault, id: String): MdbxManagerObject {
    val current = requireNotNull(vault.getObjectSummary(id)).also { check(!it.deleted) }
    // External relations cannot be guessed when crossing vaults. A same-vault move never needs disclosure.
    check(vault.listObjectRelationsFrom(id, null).none { !it.deleted } &&
        vault.listObjectRelationsTo(id, null).none { !it.deleted }) { "This entry has native object relations; move it within this database to retain them." }
    val record = vault.revealObjectWithLimits(id, MdbxObjectDisclosureLimits(4uL * 1024uL * 1024uL)).`object`
        ?: error("This entry is locked by the database disclosure policy.")
    val assets = mutableListOf<MdbxManagerAsset>()
    try {
        val attachments = vault.listAttachments(current.collectionId, id).filterNot { it.deleted }
        require(attachments.sumOf { it.originalSize.toLong() } <= 64L * 1024 * 1024) { "Attachments exceed the safe transfer limit (64 MiB per entry)." }
        attachments.forEach { attachment ->
            val bytes = vault.readAttachmentContent(attachment.attachmentId, 64uL * 1024uL * 1024uL)
            assets += MdbxManagerAsset(attachment.fileName, attachment.mediaType, bytes)
            check(bytes.size.toULong() == attachment.originalSize)
        }
        val labels = vault.listObjectLabelAssignments(id).filterNot { it.deleted }.map { assignment ->
            vault.revealObjectLabel(assignment.labelId).label ?: error("An entry label could not be read.")
        }
        return MdbxManagerObject(current.nativeBrowserSummary(), record.payloadJson, assets, labels)
    } catch (error: Throwable) { assets.forEach { it.content.fill(0) }; throw error }
}

internal fun copyMdbxManagerObject(vault: MdbxVault, source: MdbxManagerObject, folderId: String?, groups: MutableMap<String, String> = mutableMapOf()): String {
    val target = folderId ?: Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
    if (folderId == null && vault.getCollectionSummary(target) == null) {
        vault.executeWriteOperation(UUID.randomUUID().toString(), "manager-create-root", listOf(
            MdbxWriteCommand.CreateProject(target, Mdbx2VaultSessionExecutor.ROOT_PROJECT_TITLE)))
    }
    check(vault.getCollectionSummary(target)?.deleted == false) { "The destination folder no longer exists." }
    val parsed = Json.parseToJsonElement(source.payload) as? JsonObject
    val logical = (parsed?.get("monica_entry_id") as? JsonPrimitive)?.contentOrNull
    var expected = source
    val createdId = if (!logical.isNullOrBlank()) {
        check(source.summary.version == 1u) { "This Monica schema cannot be safely reidentified. The source was retained." }
        check(listOf("bound_note_entry_id", "bound_password_entry_id", "bound_note_room_id", "passkey_bindings").all {
            val value = parsed?.get(it)
            value == null || value == JsonNull || (value as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        }) { "This entry references other records. Move it within its database to retain references." }
        val newLogical = if (source.summary.type == "passkey") logical else logical.substringBefore(':') + ":" + UUID.randomUUID()
        val physical = mdbx2PhysicalEntryId(vault.info().vaultId, newLogical)
        check(vault.getObjectSummary(physical) == null) { "This credential already exists in the destination." }
        val originalGroup = (parsed?.get("password_group_id") as? JsonPrimitive)?.contentOrNull
        val groupPatch = originalGroup?.takeIf { it.isNotBlank() && it != "null" }?.let {
            mapOf("password_group_id" to JsonPrimitive(groups.getOrPut(it) { UUID.randomUUID().toString() }))
        }.orEmpty()
        val payload = managerPayloadInFolder(JsonObject(requireNotNull(parsed) + groupPatch + mapOf(
            "monica_entry_id" to JsonPrimitive(newLogical), "room_id" to JsonPrimitive(0), "category_id" to JsonNull
        )).toString(), folderId)
        vault.executeWriteOperation(UUID.randomUUID().toString(), "manager-copy-entry", listOf(
            MdbxWriteCommand.CreateEntry(physical, target, source.summary.type, source.summary.title, payload)))
        // The native engine canonicalizes object-key order; compare its source representation.
        val canonical = vault.revealObjectWithLimits(physical, MdbxObjectDisclosureLimits(4uL * 1024uL * 1024uL)).`object`!!.payloadJson
        check(Json.parseToJsonElement(canonical) == Json.parseToJsonElement(payload)) { "Copied payload verification failed." }
        expected = source.copy(payload = canonical)
        physical
    } else vault.createObject(target, source.summary.type, source.summary.title, source.payload, source.summary.version).objectId
    // Keep the source intact on failure, including interrupted/partial writes to the destination.
    for (asset in source.assets) {
        vault.executeAttachmentBatch(UUID.randomUUID().toString(), listOf(
            MdbxAttachmentBatchCommand.Create(UUID.randomUUID().toString(), target, createdId, asset.name, asset.mime, asset.content)))
    }
    for (label in source.labels) {
        val copy = vault.createObjectLabel(target, label.name, label.payloadJson, label.payloadSchemaVersion)
        vault.assignObjectLabel(createdId, copy.labelId)
    }
    verifyMdbxManagerObject(vault, createdId, expected, target)
    return createdId
}

internal fun verifyMdbxManagerObject(vault: MdbxVault, id: String, expected: MdbxManagerObject, folderId: String) {
    val actual = captureMdbxManagerObject(vault, id)
    try {
        check(actual.summary.collectionId == folderId && actual.summary.type == expected.summary.type &&
            actual.summary.version == expected.summary.version && actual.summary.title == expected.summary.title &&
            actual.payload == expected.payload) { "The copied entry did not pass verification. The source was retained." }
        fun assets(value: MdbxManagerObject) = value.assets.map {
            listOf(it.name, it.mime.orEmpty(), MessageDigest.getInstance("SHA-256").digest(it.content).joinToString("") { byte -> "%02x".format(byte) })
        }.sortedBy { it.joinToString("\u0000") }
        check(assets(actual) == assets(expected)) { "Attachment verification failed. The source was retained." }
        fun labels(value: MdbxManagerObject) = value.labels.map { listOf(it.name, it.payloadJson, it.payloadSchemaVersion.toString()) }.sortedBy { it.joinToString("\u0000") }
        check(labels(actual) == labels(expected)) { "Label verification failed. The source was retained." }
    } finally { actual.clear() }
}

internal fun moveMdbxManagerObject(vault: MdbxVault, expected: MdbxNativeObjectSummary, folderId: String?) {
    val current = requireNotNull(vault.getObjectSummary(expected.id))
    check(current.nativeBrowserSummary() == expected && !current.deleted) { "The source changed. Refresh and try again." }
    val target = folderId ?: Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
    if (current.collectionId == target) return
    val createRoot = folderId == null && vault.getCollectionSummary(target) == null
    check(createRoot || vault.getCollectionSummary(target)?.deleted == false) { "The destination folder no longer exists." }
    val attachments = vault.listAttachments(current.collectionId, expected.id).filterNot { it.deleted }
    val content = mutableListOf<ByteArray>()
    try {
        require(attachments.sumOf { it.originalSize.toLong() } <= 64L * 1024 * 1024) { "Attachments exceed the safe transfer limit." }
        val copies = attachments.flatMap { attachment ->
            val bytes = vault.readAttachmentContent(attachment.attachmentId, 64uL * 1024uL * 1024uL).also(content::add)
            listOf(MdbxAttachmentBatchCommand.Create(UUID.randomUUID().toString(), target, expected.id, attachment.fileName, attachment.mediaType, bytes),
                MdbxAttachmentBatchCommand.Delete(attachment.attachmentId))
        }
        val commands = buildList {
            if (createRoot) add(MdbxWriteCommand.CreateProject(target, Mdbx2VaultSessionExecutor.ROOT_PROJECT_TITLE))
            add(MdbxWriteCommand.MoveEntry(expected.id, current.collectionId, target))
            if (expected.version == 1u) {
                val payload = vault.revealObjectWithLimits(expected.id, MdbxObjectDisclosureLimits(4uL * 1024uL * 1024uL)).`object`!!.payloadJson
                val updated = managerPayloadInFolder(payload, folderId)
                if (updated != payload) add(MdbxWriteCommand.UpdateEntry(expected.id, target, expected.type, expected.title, updated))
            }
        }
        if (copies.isEmpty()) vault.executeWriteOperation(UUID.randomUUID().toString(), "manager-move-entry", commands)
        else vault.executeCompositeWriteOperation(UUID.randomUUID().toString(), "manager-move-entry", commands, copies)
    } finally { content.forEach { it.fill(0) } }
}
