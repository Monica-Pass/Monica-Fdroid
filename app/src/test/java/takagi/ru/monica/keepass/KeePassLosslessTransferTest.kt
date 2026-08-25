package takagi.ru.monica.keepass

import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import java.time.Instant
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassLosslessTransferTest {
    @Test
    fun `target first transfer verifies durable target before removing source`() = runBlocking {
        val operations = mutableListOf<String>()

        val result = KeePassTargetFirstTransfer.execute(
            persistTarget = {
                operations += "persist-target"
                "target-result"
            },
            verifyTarget = { targetResult ->
                assertEquals("target-result", targetResult)
                operations += "verify-target"
            },
            removeSource = {
                operations += "remove-source"
            }
        )

        assertEquals("target-result", result)
        assertEquals(
            listOf("persist-target", "verify-target", "remove-source"),
            operations
        )
    }

    @Test
    fun `target first transfer leaves source untouched when target persistence fails`() = runBlocking {
        val operations = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                KeePassTargetFirstTransfer.execute(
                    persistTarget = {
                        operations += "persist-target"
                        throw IllegalStateException("target write failed")
                    },
                    verifyTarget = {
                        operations += "verify-target"
                    },
                    removeSource = {
                        operations += "remove-source"
                    }
                )
            }
        }

        assertEquals(listOf("persist-target"), operations)
    }

    @Test
    fun `target first transfer leaves source untouched when target verification fails`() = runBlocking {
        val operations = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                KeePassTargetFirstTransfer.execute(
                    persistTarget = {
                        operations += "persist-target"
                        "target-result"
                    },
                    verifyTarget = {
                        operations += "verify-target"
                        throw IllegalStateException("target verification failed")
                    },
                    removeSource = {
                        operations += "remove-source"
                    }
                )
            }
        }

        assertEquals(listOf("persist-target", "verify-target"), operations)
    }

    @Test
    fun `copies complete native entry with binary and custom icon payloads`() {
        val sourceGroupUuid = UUID.randomUUID()
        val targetGroupUuid = UUID.randomUUID()
        val sourceEntryUuid = UUID.randomUUID()
        val targetEntryUuid = UUID.randomUUID()
        val customIconUuid = UUID.randomUUID()
        val attachment = BinaryData.Uncompressed(true, "attachment-body".toByteArray())
        val historyAttachment = BinaryData.Uncompressed(false, "history-body".toByteArray())
        val customIcon = CustomIcon(
            data = byteArrayOf(1, 3, 5, 7),
            name = "Bank icon",
            lastModified = Instant.parse("2026-08-16T09:00:00Z")
        )
        val history = entry(
            uuid = UUID.randomUUID(),
            title = "Previous title",
            customIconUuid = customIconUuid,
            binary = BinaryReference(historyAttachment.hash, "old.txt")
        )
        val sourceEntry = entry(
            uuid = sourceEntryUuid,
            title = "Current title",
            customIconUuid = customIconUuid,
            binary = BinaryReference(attachment.hash, "receipt.pdf")
        ).copy(
            foregroundColor = "#112233",
            backgroundColor = "#445566",
            overrideUrl = "cmd://open",
            autoType = AutoTypeData(
                enabled = true,
                obfuscation = AutoTypeObfuscation.UseClipboard,
                defaultSequence = "{USERNAME}{TAB}{PASSWORD}{ENTER}",
                items = listOf(AutoTypeItem("Example Window", "{PASSWORD}{ENTER}"))
            ),
            tags = listOf("finance", "shared"),
            history = listOf(history),
            customData = mapOf(
                "plugin.data" to CustomDataValue(
                    value = "preserve-me",
                    lastModified = Instant.parse("2026-08-16T10:00:00Z")
                )
            ),
            previousParentGroup = UUID.randomUUID(),
            qualityCheck = false
        )
        val source = database("Source")
            .modifyParentGroup {
                copy(groups = listOf(Group(uuid = sourceGroupUuid, name = "Source", entries = listOf(sourceEntry))))
            }
            .modifyBinaries { mapOf(attachment.hash to attachment, historyAttachment.hash to historyAttachment) }
            .modifyCustomIcons { mapOf(customIconUuid to customIcon) }
        val target = database("Target").modifyParentGroup {
            copy(groups = listOf(Group(uuid = targetGroupUuid, name = "Target")))
        }

        val payload = KeePassLosslessTransfer.captureEntry(
            sourceDatabase = source,
            sourceEntryUuid = sourceEntryUuid,
            targetEntryUuid = targetEntryUuid
        )
        val updatedTarget = KeePassLosslessTransfer.insertEntry(
            targetDatabase = target,
            targetGroupUuid = targetGroupUuid,
            payload = payload
        )

        val copied = findEntry(updatedTarget.content.group, targetEntryUuid)
        assertNotNull(copied)
        copied!!
        assertEquals(sourceEntry.copy(uuid = targetEntryUuid), copied)
        assertEquals(sourceGroupUuid, payload.sourceParentGroupUuid)
        assertEquals(setOf(attachment.hash, historyAttachment.hash), payload.binaryPool.keys)
        assertArrayEquals(attachment.rawContent, updatedTarget.binaries.getValue(attachment.hash).rawContent)
        assertArrayEquals(historyAttachment.rawContent, updatedTarget.binaries.getValue(historyAttachment.hash).rawContent)
        assertArrayEquals(
            customIcon.data,
            updatedTarget.content.meta.customIcons.getValue(customIconUuid).data
        )
        assertEquals(customIcon.name, updatedTarget.content.meta.customIcons.getValue(customIconUuid).name)
        assertNotNull(findEntry(source.content.group, sourceEntryUuid))
    }

    @Test
    fun `rejects target UUID collision with different native content`() {
        val sourceEntryUuid = UUID.randomUUID()
        val targetGroupUuid = UUID.randomUUID()
        val source = database("Source").modifyParentGroup {
            copy(entries = listOf(entry(sourceEntryUuid, "Source")))
        }
        val target = database("Target").modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = targetGroupUuid,
                        name = "Target",
                        entries = listOf(entry(sourceEntryUuid, "Existing different entry"))
                    )
                )
            )
        }
        val payload = KeePassLosslessTransfer.captureEntry(source, sourceEntryUuid)

        assertThrows(IllegalStateException::class.java) {
            KeePassLosslessTransfer.insertEntry(target, targetGroupUuid, payload)
        }
    }

    @Test
    fun `source remains intact until explicit removal after target insertion`() {
        val sourceEntryUuid = UUID.randomUUID()
        val sourceGroupUuid = UUID.randomUUID()
        val targetGroupUuid = UUID.randomUUID()
        val source = database("Source").modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = sourceGroupUuid,
                        name = "Source",
                        entries = listOf(entry(sourceEntryUuid, "Move me"))
                    )
                )
            )
        }
        val target = database("Target").modifyParentGroup {
            copy(groups = listOf(Group(uuid = targetGroupUuid, name = "Target")))
        }
        val payload = KeePassLosslessTransfer.captureEntry(source, sourceEntryUuid)

        val updatedTarget = KeePassLosslessTransfer.insertEntry(target, targetGroupUuid, payload)

        assertNotNull(findEntry(updatedTarget.content.group, sourceEntryUuid))
        assertNotNull(findEntry(source.content.group, sourceEntryUuid))
        assertFalse(source.content.deletedObjects.any { it.id == sourceEntryUuid })

        val updatedSource = KeePassLosslessTransfer.removeEntry(source, sourceEntryUuid)

        assertEquals(null, findEntry(updatedSource.content.group, sourceEntryUuid))
        assertTrue(updatedSource.content.deletedObjects.any { it.id == sourceEntryUuid })
    }

    @Test
    fun `same database relocation preserves native entry without deletion tombstone`() {
        val sourceEntryUuid = UUID.randomUUID()
        val sourceGroupUuid = UUID.randomUUID()
        val targetGroupUuid = UUID.randomUUID()
        val nativeEntry = entry(sourceEntryUuid, "Relocate me").copy(
            tags = listOf("native"),
            foregroundColor = "#ABCDEF"
        )
        val database = database("Same database").modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = sourceGroupUuid,
                        name = "Source",
                        entries = listOf(nativeEntry)
                    ),
                    Group(uuid = targetGroupUuid, name = "Target")
                )
            )
        }

        val relocated = KeePassLosslessTransfer.relocateEntry(
            database = database,
            sourceEntryUuid = sourceEntryUuid,
            targetGroupUuid = targetGroupUuid
        )

        assertEquals(null, findDirectEntry(relocated.content.group, sourceGroupUuid, sourceEntryUuid))
        assertEquals(nativeEntry, findDirectEntry(relocated.content.group, targetGroupUuid, sourceEntryUuid))
        assertFalse(relocated.content.deletedObjects.any { it.id == sourceEntryUuid })
    }

    @Test
    fun `inserted native payload remains equal after KDBX encode decode round trip`() {
        val sourceEntryUuid = UUID.randomUUID()
        val targetGroupUuid = UUID.randomUUID()
        val customIconUuid = UUID.randomUUID()
        val attachment = BinaryData.Uncompressed(false, "round-trip attachment".toByteArray())
        val customIcon = CustomIcon(
            data = byteArrayOf(9, 8, 7, 6),
            name = "Round trip icon",
            lastModified = Instant.parse("2026-08-17T12:00:00Z")
        )
        val sourceEntry = entry(
            uuid = sourceEntryUuid,
            title = "Round trip",
            customIconUuid = customIconUuid,
            binary = BinaryReference(attachment.hash, "round-trip.bin")
        )
        val source = database("Source")
            .modifyParentGroup { copy(entries = listOf(sourceEntry)) }
            .modifyBinaries { mapOf(attachment.hash to attachment) }
            .modifyCustomIcons { mapOf(customIconUuid to customIcon) }
        val target = database("Target").modifyParentGroup {
            copy(groups = listOf(Group(uuid = targetGroupUuid, name = "Target")))
        }
        val payload = KeePassLosslessTransfer.captureEntry(source, sourceEntryUuid)
        val inserted = KeePassLosslessTransfer.insertEntry(target, targetGroupUuid, payload)

        val encoded = ByteArrayOutputStream().use { output ->
            inserted.encode(output)
            output.toByteArray()
        }
        val decoded = KeePassDatabase.decode(
            ByteArrayInputStream(encoded),
            testCredentials()
        )

        assertTrue(
            KeePassLosslessTransfer.entriesEquivalent(
                payload.entry,
                requireNotNull(findEntry(decoded.content.group, sourceEntryUuid))
            )
        )
        assertArrayEquals(
            attachment.rawContent,
            decoded.binaries.getValue(attachment.hash).rawContent
        )
        assertArrayEquals(
            customIcon.data,
            decoded.content.meta.customIcons.getValue(customIconUuid).data
        )
    }

    private fun database(rootName: String): KeePassDatabase {
        return KeePassDatabase.Ver4x.create(
            rootName = rootName,
            meta = Meta(generator = "Monica lossless transfer test", name = rootName),
            credentials = testCredentials()
        )
    }

    private fun testCredentials(): Credentials {
        return Credentials.from(EncryptedValue.fromString("test-password"))
    }

    private fun entry(
        uuid: UUID,
        title: String,
        customIconUuid: UUID? = null,
        binary: BinaryReference? = null
    ): Entry {
        val instant = Instant.parse("2026-08-16T08:00:00Z")
        return Entry(
            uuid = uuid,
            icon = PredefinedIcon.Key,
            customIconUuid = customIconUuid,
            times = TimeData(
                creationTime = instant,
                lastAccessTime = instant,
                lastModificationTime = instant,
                locationChanged = instant,
                expiryTime = instant.plusSeconds(3600),
                expires = true,
                usageCount = 12
            ),
            fields = EntryFields.of(
                "Title" to EntryValue.Plain(title),
                "UserName" to EntryValue.Plain("alice"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("secret")),
                "Plugin field" to EntryValue.Plain("unknown-value")
            ),
            binaries = listOfNotNull(binary)
        )
    }

    private fun findEntry(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        group.groups.forEach { child ->
            findEntry(child, uuid)?.let { return it }
        }
        return null
    }

    private fun findDirectEntry(root: Group, groupUuid: UUID, entryUuid: UUID): Entry? {
        val group = findGroup(root, groupUuid) ?: return null
        return group.entries.firstOrNull { it.uuid == entryUuid }
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child ->
            findGroup(child, uuid)?.let { return it }
        }
        return null
    }
}
