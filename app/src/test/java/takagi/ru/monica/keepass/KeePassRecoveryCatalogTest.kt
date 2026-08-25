package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassRecoveryCatalogTest {
    @Test
    fun `recovery catalog lists verifies exports restores and deletes complete copies`() {
        val root = Files.createTempDirectory("keepass-recovery-test").toFile()
        val store = KeePassRecoveryStore(root) { Instant.parse("2026-08-18T01:02:03Z") }
        val bytes = "complete-kdbx-bytes".toByteArray()
        val created = store.create(42L, bytes)

        val record = store.list(42L).single()

        assertEquals(created.file, record.file)
        assertTrue(record.verified)
        assertEquals(Instant.parse("2026-08-18T01:02:03Z"), record.createdAt)
        val exported = Files.createTempFile("keepass-export", ".kdbx").toFile()
        val restored = Files.createTempFile("keepass-restore", ".kdbx").toFile()
        store.export(record, exported)
        store.restore(record, restored)
        assertArrayEquals(bytes, exported.readBytes())
        assertArrayEquals(bytes, restored.readBytes())
        assertTrue(store.delete(record))
        assertTrue(store.list(42L).isEmpty())
    }

    @Test
    fun `tampered recovery copy remains visible but cannot be restored`() {
        val root = Files.createTempDirectory("keepass-recovery-tamper").toFile()
        val store = KeePassRecoveryStore(root) { Instant.parse("2026-08-18T02:00:00Z") }
        val copy = store.create(7L, "original".toByteArray())
        copy.file.writeText("tampered")

        val record = store.list(7L).single()

        assertFalse(record.verified)
        val result = runCatching {
            store.restore(record, Files.createTempFile("keepass-invalid-restore", ".kdbx").toFile())
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `raw save copy preserves every byte`() {
        val bytes = ByteArray(4097) { index -> (index * 31).toByte() }
        val target = Files.createTempFile("keepass-save-copy", ".kdbx").toFile()

        val revision = KeePassRawFileOperations.saveCopy(bytes, target)

        assertArrayEquals(bytes, target.readBytes())
        assertEquals(KeePassSourceSafety.revisionOf(bytes), revision)
    }

    @Test
    fun `merge from keeps attachments icons and native fields`() {
        val targetGroup = UUID.randomUUID()
        val sourceGroup = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val iconUuid = UUID.randomUUID()
        val attachment = BinaryData.Uncompressed(true, "attachment".toByteArray())
        val target = database("Target").modifyParentGroup {
            copy(groups = listOf(Group(uuid = targetGroup, name = "Imported")))
        }
        val sourceEntry = Entry(
            uuid = entryUuid,
            customIconUuid = iconUuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("External entry"),
                "Plugin field" to EntryValue.Encrypted(EncryptedValue.fromString("opaque"))
            ),
            binaries = listOf(BinaryReference(attachment.hash, "document.bin"))
        )
        val source = database("Source")
            .modifyParentGroup {
                copy(groups = listOf(Group(uuid = sourceGroup, name = "External", entries = listOf(sourceEntry))))
            }
            .modifyBinaries { mapOf(attachment.hash to attachment) }
            .modifyCustomIcons {
                mapOf(iconUuid to CustomIcon(byteArrayOf(9, 8, 7), "External icon", null))
            }

        val merged = KeePassNativeDatabaseMerge.mergeFrom(target, source, targetGroup)

        val imported = findEntry(merged.content.group, entryUuid)
        assertNotNull(imported)
        assertEquals("opaque", imported!!.fields.getValue("Plugin field").content)
        assertArrayEquals(attachment.rawContent, merged.binaries.getValue(attachment.hash).rawContent)
        assertArrayEquals(byteArrayOf(9, 8, 7), merged.content.meta.customIcons.getValue(iconUuid).data)
    }

    private fun database(name: String): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica recovery test", name = name),
        credentials = Credentials.from(EncryptedValue.fromString("password"))
    )

    private fun findEntry(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        group.groups.forEach { child -> findEntry(child, uuid)?.let { return it } }
        return null
    }
}
