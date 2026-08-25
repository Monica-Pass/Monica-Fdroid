package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import okio.ByteString
import okio.ByteString.Companion.toByteString
import takagi.ru.monica.data.ItemType
import java.util.UUID

/**
 * Keeps Monica's bank-card/document front and back photos in standard KDBX binaries.
 *
 * Only deterministic Monica-reserved file names are managed. Other attachments on the same entry
 * are never modified, so users can continue adding arbitrary files from another KeePass client.
 */
internal object KeePassSecureItemPhotoAttachments {
    enum class Slot {
        FRONT,
        BACK
    }

    sealed class SlotUpdate {
        object Preserve : SlotUpdate()
        object Remove : SlotUpdate()
        data class Replace(val bytes: ByteArray) : SlotUpdate()
    }

    data class ManagedPhoto(
        val slot: Slot,
        val fileName: String,
        val hashHex: String,
        val bytes: ByteArray
    )

    data class Change(
        val operation: KeePassChangeOperation,
        val slot: Slot,
        val fileName: String,
        val binaryHash: String,
        val bytes: ByteArray? = null,
        val protected: Boolean = false,
        val compressed: Boolean = true
    )

    data class SyncResult(
        val database: KeePassDatabase,
        val changes: List<Change>
    )

    private data class ManagedNames(
        val front: String,
        val back: String
    ) {
        fun forSlot(slot: Slot): String = when (slot) {
            Slot.FRONT -> front
            Slot.BACK -> back
        }

        val all: Set<String>
            get() = setOf(front, back)
    }

    private val bankCardNames = ManagedNames(
        front = "Monica_BankCard_Front.jpg",
        back = "Monica_BankCard_Back.jpg"
    )
    private val documentNames = ManagedNames(
        front = "Monica_Document_Front.jpg",
        back = "Monica_Document_Back.jpg"
    )
    private val allManagedNames = bankCardNames.all + documentNames.all

    fun synchronize(
        database: KeePassDatabase,
        entryUuid: UUID,
        itemType: ItemType,
        updates: Map<Slot, SlotUpdate>
    ): SyncResult {
        val names = namesFor(itemType) ?: return SyncResult(database, emptyList())
        val existingEntry = findEntryByUuid(database.content.group, entryUuid)
            ?: return SyncResult(database, emptyList())
        val unmanagedReferences = existingEntry.binaries.filterNot { it.name in allManagedNames }
        val finalManagedReferences = mutableListOf<BinaryReference>()
        val additions = mutableListOf<Pair<BinaryReference, BinaryData>>()
        val changes = mutableListOf<Change>()

        Slot.entries.forEach { slot ->
            val fileName = names.forSlot(slot)
            val existingForSlot = existingEntry.binaries.filter { it.name == fileName }
            val update = updates[slot] ?: SlotUpdate.Preserve
            val keptReference = when (update) {
                SlotUpdate.Preserve -> existingForSlot.firstOrNull()
                SlotUpdate.Remove -> null
                is SlotUpdate.Replace -> {
                    val binaryData = BinaryData.Uncompressed(
                        memoryProtection = false,
                        rawContent = update.bytes
                    ).toCompressed()
                    val unchangedReference = existingForSlot.firstOrNull { it.hash == binaryData.hash }
                    if (unchangedReference != null) {
                        unchangedReference
                    } else {
                        val newReference = BinaryReference(hash = binaryData.hash, name = fileName)
                        additions += newReference to binaryData
                        changes += Change(
                            operation = KeePassChangeOperation.ADD_ATTACHMENT,
                            slot = slot,
                            fileName = fileName,
                            binaryHash = binaryData.hash.hex(),
                            bytes = update.bytes
                        )
                        newReference
                    }
                }
            }

            var kept = false
            existingForSlot.forEach { reference ->
                if (!kept && keptReference != null && reference == keptReference) {
                    kept = true
                } else {
                    val existingData = database.binaries[reference.hash]
                    changes += Change(
                        operation = KeePassChangeOperation.REMOVE_ATTACHMENT,
                        slot = slot,
                        fileName = reference.name,
                        binaryHash = reference.hash.hex(),
                        protected = existingData?.memoryProtection ?: false,
                        compressed = true
                    )
                }
            }

            keptReference?.let(finalManagedReferences::add)
        }

        val updatedEntry = existingEntry.copy(
            binaries = unmanagedReferences + finalManagedReferences
        )
        val updatedRoot = updateEntryByUuid(
            group = database.content.group,
            entryUuid = entryUuid,
            replacement = updatedEntry
        ).first
        val databaseWithEntry = database.modifyParentGroup { updatedRoot }
        val databaseWithAdditions = databaseWithEntry.modifyBinaries { pool ->
            additions.fold(pool) { current, (_, binaryData) ->
                if (current.containsKey(binaryData.hash)) current else current + (binaryData.hash to binaryData)
            }
        }
        val removedHashes = changes
            .asSequence()
            .filter { it.operation == KeePassChangeOperation.REMOVE_ATTACHMENT }
            .mapNotNull { it.binaryHash.hexToByteStringOrNull() }
            .toSet()
        val compactedDatabase = databaseWithAdditions.modifyBinaries { pool ->
            removedHashes.fold(pool) { current, hash ->
                if (anyEntryReferencesHash(databaseWithAdditions.content.group, hash)) current else current - hash
            }
        }

        return SyncResult(
            database = compactedDatabase,
            changes = changes.sortedWith(compareBy<Change> { changeOrder(it.operation) }.thenBy { it.slot.ordinal })
        )
    }

    fun readManagedPhotos(
        database: KeePassDatabase,
        entry: Entry,
        itemType: ItemType
    ): Map<Slot, ManagedPhoto> {
        val names = namesFor(itemType) ?: return emptyMap()
        return buildMap {
            Slot.entries.forEach { slot ->
                val fileName = names.forSlot(slot)
                val reference = entry.binaries.firstOrNull { it.name == fileName } ?: return@forEach
                val binaryData = database.binaries[reference.hash] ?: return@forEach
                val bytes = runCatching { binaryData.inputStream().use { it.readBytes() } }.getOrNull()
                    ?: return@forEach
                put(
                    slot,
                    ManagedPhoto(
                        slot = slot,
                        fileName = fileName,
                        hashHex = reference.hash.hex(),
                        bytes = bytes
                    )
                )
            }
        }
    }

    fun managedFileNames(itemType: ItemType): Set<String> =
        namesFor(itemType)?.all.orEmpty()

    private fun namesFor(itemType: ItemType): ManagedNames? = when (itemType) {
        ItemType.BANK_CARD -> bankCardNames
        ItemType.DOCUMENT -> documentNames
        else -> null
    }

    private fun changeOrder(operation: KeePassChangeOperation): Int = when (operation) {
        KeePassChangeOperation.REMOVE_ATTACHMENT -> 0
        KeePassChangeOperation.ADD_ATTACHMENT -> 1
        else -> 2
    }

    private fun findEntryByUuid(group: Group, entryUuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == entryUuid }?.let { return it }
        group.groups.forEach { child ->
            findEntryByUuid(child, entryUuid)?.let { return it }
        }
        return null
    }

    private fun updateEntryByUuid(
        group: Group,
        entryUuid: UUID,
        replacement: Entry
    ): Pair<Group, Boolean> {
        var updated = false
        val entries = group.entries.map { entry ->
            if (!updated && entry.uuid == entryUuid) {
                updated = true
                replacement
            } else {
                entry
            }
        }
        if (updated) return group.copy(entries = entries) to true

        val groups = group.groups.map { child ->
            if (updated) {
                child
            } else {
                val childResult = updateEntryByUuid(child, entryUuid, replacement)
                if (childResult.second) updated = true
                childResult.first
            }
        }
        return group.copy(entries = entries, groups = groups) to updated
    }

    private fun anyEntryReferencesHash(group: Group, hash: ByteString): Boolean {
        if (group.entries.any { entry -> entry.binaries.any { it.hash == hash } }) return true
        return group.groups.any { child -> anyEntryReferencesHash(child, hash) }
    }

    private fun String.hexToByteStringOrNull(): ByteString? {
        val normalized = trim()
        if (normalized.isEmpty() || normalized.length % 2 != 0) return null
        return runCatching {
            ByteArray(normalized.length / 2) { index ->
                normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }.toByteString()
        }.getOrNull()
    }
}
