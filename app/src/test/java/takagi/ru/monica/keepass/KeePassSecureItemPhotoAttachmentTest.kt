package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.utils.KeePassCodecSupport

class KeePassSecureItemPhotoAttachmentTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("photo-test"))

    @Test
    fun bankCardPhotosBecomeStandardKdbxBinariesAndSurviveRoundTrip() {
        val entryUuid = UUID.randomUUID()
        val front = "front-photo".toByteArray()
        val back = "back-photo".toByteArray()
        val database = databaseWith(entry(entryUuid))

        val synced = KeePassSecureItemPhotoAttachments.synchronize(
            database = database,
            entryUuid = entryUuid,
            itemType = ItemType.BANK_CARD,
            updates = mapOf(
                KeePassSecureItemPhotoAttachments.Slot.FRONT to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace(front),
                KeePassSecureItemPhotoAttachments.Slot.BACK to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace(back)
            )
        )
        val decoded = decode(encode(synced.database))
        val decodedEntry = decoded.content.group.entries.single { it.uuid == entryUuid }

        assertEquals(
            listOf("Monica_BankCard_Front.jpg", "Monica_BankCard_Back.jpg"),
            decodedEntry.binaries.map { it.name }
        )
        assertArrayEquals(front, attachmentBytes(decoded, decodedEntry.binaries[0]))
        assertArrayEquals(back, attachmentBytes(decoded, decodedEntry.binaries[1]))
        assertEquals(2, synced.changes.count { it.operation == KeePassChangeOperation.ADD_ATTACHMENT })
    }

    @Test
    fun resaveReplacesManagedPhotoWithoutTouchingOtherAttachments() {
        val entryUuid = UUID.randomUUID()
        val userAttachment = BinaryData.Uncompressed(false, "user-file".toByteArray())
        val baseEntry = entry(entryUuid).copy(
            binaries = listOf(BinaryReference(userAttachment.hash, "receipt.pdf"))
        )
        val database = databaseWith(baseEntry)
            .modifyBinaries { it + (userAttachment.hash to userAttachment) }
        val first = KeePassSecureItemPhotoAttachments.synchronize(
            database = database,
            entryUuid = entryUuid,
            itemType = ItemType.DOCUMENT,
            updates = mapOf(
                KeePassSecureItemPhotoAttachments.Slot.FRONT to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace("front-v1".toByteArray()),
                KeePassSecureItemPhotoAttachments.Slot.BACK to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace("back-v1".toByteArray())
            )
        )

        val second = KeePassSecureItemPhotoAttachments.synchronize(
            database = first.database,
            entryUuid = entryUuid,
            itemType = ItemType.DOCUMENT,
            updates = mapOf(
                KeePassSecureItemPhotoAttachments.Slot.FRONT to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace("front-v2".toByteArray()),
                KeePassSecureItemPhotoAttachments.Slot.BACK to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Preserve
            )
        )
        val updatedEntry = second.database.content.group.entries.single { it.uuid == entryUuid }

        assertEquals(
            listOf("receipt.pdf", "Monica_Document_Front.jpg", "Monica_Document_Back.jpg"),
            updatedEntry.binaries.map { it.name }
        )
        assertArrayEquals("user-file".toByteArray(), attachmentBytes(second.database, updatedEntry.binaries[0]))
        assertArrayEquals("front-v2".toByteArray(), attachmentBytes(second.database, updatedEntry.binaries[1]))
        assertArrayEquals("back-v1".toByteArray(), attachmentBytes(second.database, updatedEntry.binaries[2]))
        assertEquals(1, second.changes.count { it.operation == KeePassChangeOperation.REMOVE_ATTACHMENT })
        assertEquals(1, second.changes.count { it.operation == KeePassChangeOperation.ADD_ATTACHMENT })
    }

    @Test
    fun removingOnePhotoOnlyRemovesItsManagedBinary() {
        val entryUuid = UUID.randomUUID()
        val first = KeePassSecureItemPhotoAttachments.synchronize(
            database = databaseWith(entry(entryUuid)),
            entryUuid = entryUuid,
            itemType = ItemType.BANK_CARD,
            updates = mapOf(
                KeePassSecureItemPhotoAttachments.Slot.FRONT to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace("front".toByteArray()),
                KeePassSecureItemPhotoAttachments.Slot.BACK to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace("back".toByteArray())
            )
        )
        val second = KeePassSecureItemPhotoAttachments.synchronize(
            database = first.database,
            entryUuid = entryUuid,
            itemType = ItemType.BANK_CARD,
            updates = mapOf(
                KeePassSecureItemPhotoAttachments.Slot.FRONT to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Remove,
                KeePassSecureItemPhotoAttachments.Slot.BACK to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Preserve
            )
        )
        val updatedEntry = second.database.content.group.entries.single { it.uuid == entryUuid }

        assertEquals(listOf("Monica_BankCard_Back.jpg"), updatedEntry.binaries.map { it.name })
        assertEquals(1, second.changes.size)
        assertEquals(KeePassChangeOperation.REMOVE_ATTACHMENT, second.changes.single().operation)
        assertFalse(
            second.database.binaries.keys.any { hash ->
                first.database.content.group.entries.single { it.uuid == entryUuid }
                    .binaries.first { it.name == "Monica_BankCard_Front.jpg" }.hash == hash
            }
        )
    }

    @Test
    fun managedPhotosCanBeReadBackForMonicaLocalCache() {
        val entryUuid = UUID.randomUUID()
        val synced = KeePassSecureItemPhotoAttachments.synchronize(
            database = databaseWith(entry(entryUuid)),
            entryUuid = entryUuid,
            itemType = ItemType.DOCUMENT,
            updates = mapOf(
                KeePassSecureItemPhotoAttachments.Slot.FRONT to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace("front".toByteArray()),
                KeePassSecureItemPhotoAttachments.Slot.BACK to
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace("back".toByteArray())
            )
        )
        val entry = synced.database.content.group.entries.single { it.uuid == entryUuid }
        val photos = KeePassSecureItemPhotoAttachments.readManagedPhotos(
            database = synced.database,
            entry = entry,
            itemType = ItemType.DOCUMENT
        )

        assertArrayEquals("front".toByteArray(), photos.getValue(KeePassSecureItemPhotoAttachments.Slot.FRONT).bytes)
        assertArrayEquals("back".toByteArray(), photos.getValue(KeePassSecureItemPhotoAttachments.Slot.BACK).bytes)
        assertTrue(photos.values.all { it.hashHex.isNotBlank() })
    }

    private fun databaseWith(entry: Entry): KeePassDatabase {
        return KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(generator = "Monica test", name = "Secure item photo test"),
            credentials = credentials
        ).modifyParentGroup { copy(entries = entries + entry) }
    }

    private fun entry(uuid: UUID): Entry {
        return Entry(
            uuid = uuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Card"),
                "MonicaItemType" to EntryValue.Plain(ItemType.BANK_CARD.name)
            )
        )
    }

    private fun attachmentBytes(database: KeePassDatabase, reference: BinaryReference): ByteArray {
        return database.binaries.getValue(reference.hash).inputStream().use { it.readBytes() }
    }

    private fun encode(database: KeePassDatabase): ByteArray {
        return ByteArrayOutputStream().use { output ->
            database.encode(output, cipherProviders = KeePassCodecSupport.cipherProviders)
            output.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): KeePassDatabase {
        return KeePassDatabase.decode(
            ByteArrayInputStream(bytes),
            credentials,
            cipherProviders = KeePassCodecSupport.cipherProviders
        )
    }
}
