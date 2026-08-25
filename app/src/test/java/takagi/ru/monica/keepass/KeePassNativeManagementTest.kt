package takagi.ru.monica.keepass

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeePassNativeManagementTest {
    @Test
    fun `duplicate and batch move preserve native entry data without tombstones`() {
        val sourceGroupUuid = UUID.randomUUID()
        val targetGroupUuid = UUID.randomUUID()
        val sourceUuid = UUID.randomUUID()
        val binary = BinaryData.Uncompressed(true, "attachment".toByteArray())
        val original = Entry(
            uuid = sourceUuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Entry 10"),
                "Plugin" to EntryValue.Encrypted(EncryptedValue.fromString("opaque"))
            ),
            binaries = listOf(BinaryReference(binary.hash, "proof.bin")),
            tags = listOf("native")
        )
        val database = database()
            .modifyParentGroup {
                copy(
                    groups = listOf(
                        Group(uuid = sourceGroupUuid, name = "Source", entries = listOf(original)),
                        Group(uuid = targetGroupUuid, name = "Target")
                    )
                )
            }
            .modifyBinaries { mapOf(binary.hash to binary) }

        val duplicated = KeePassNativeManagement.duplicateEntry(database, sourceUuid, sourceGroupUuid)
        val copyUuid = allEntries(duplicated).single { it.uuid != sourceUuid }.uuid
        val moved = KeePassNativeManagement.moveEntries(duplicated, setOf(sourceUuid, copyUuid), targetGroupUuid)

        assertEquals(2, findGroup(moved, targetGroupUuid)!!.entries.size)
        assertEquals("opaque", findEntry(moved, copyUuid)!!.fields.getValue("Plugin").content)
        assertTrue(moved.content.deletedObjects.isEmpty())
        assertNotEquals(sourceUuid, copyUuid)
    }

    @Test
    fun `batch folder move writes one coherent tree and rejects parent child selection`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val nested = UUID.randomUUID()
        val leaf = UUID.randomUUID()
        val target = UUID.randomUUID()
        val database = database().modifyParentGroup {
            copy(
                groups = listOf(
                    Group(uuid = first, name = "First"),
                    Group(uuid = second, name = "Second"),
                    Group(
                        uuid = nested,
                        name = "Nested",
                        groups = listOf(Group(uuid = leaf, name = "Leaf"))
                    ),
                    Group(uuid = target, name = "Target")
                )
            )
        }

        val moved = KeePassNativeManagement.moveGroups(database, setOf(first, second), target)
        val targetGroup = findGroup(moved, target)!!
        assertEquals(setOf(first, second), targetGroup.groups.map { it.uuid }.toSet())
        assertEquals(2, moved.content.group.groups.size)

        try {
            KeePassNativeManagement.moveGroups(database, setOf(nested, leaf), target)
            fail("parent and child folders should not be moved together")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `attachment rename keeps binary payload and group properties remain editable`() {
        val groupUuid = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val binary = BinaryData.Uncompressed(false, "payload".toByteArray())
        val database = database()
            .modifyParentGroup {
                copy(
                    groups = listOf(
                        Group(
                            uuid = groupUuid,
                            name = "Accounts",
                            entries = listOf(
                                Entry(
                                    uuid = entryUuid,
                                    fields = EntryFields.of("Title" to EntryValue.Plain("Entry")),
                                    binaries = listOf(BinaryReference(binary.hash, "old.bin"))
                                )
                            )
                        )
                    )
                )
            }
            .modifyBinaries { mapOf(binary.hash to binary) }

        val renamed = KeePassNativeManagement.renameAttachment(database, entryUuid, binary.hash, "new.bin")
        val updated = KeePassNativeManagement.updateGroup(
            renamed,
            groupUuid,
            KeePassNativeGroupUpdate(
                notes = "Managed natively",
                enableSearching = GroupOverride.Disabled,
                enableAutoType = GroupOverride.Enabled,
                defaultAutoTypeSequence = "{USERNAME}{TAB}{PASSWORD}{ENTER}"
            )
        )

        assertEquals("new.bin", findEntry(updated, entryUuid)!!.binaries.single().name)
        assertTrue(updated.binaries.containsKey(binary.hash))
        val group = findGroup(updated, groupUuid)!!
        assertEquals("Managed natively", group.notes)
        assertEquals(GroupOverride.Disabled, group.enableSearching)
        assertEquals(GroupOverride.Enabled, group.enableAutoType)
    }

    @Test
    fun `group properties can upload and assign a custom icon atomically`() {
        val groupUuid = UUID.randomUUID()
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val database = database().modifyParentGroup {
            copy(groups = listOf(Group(uuid = groupUuid, name = "Custom icon")))
        }

        val updated = KeePassNativeManagement.updateGroup(
            database,
            groupUuid,
            KeePassNativeGroupUpdate(
                customIcon = KeePassNativeCustomIconPayload(png, "Folder icon"),
            ),
        )
        val group = findGroup(updated, groupUuid)!!
        val iconUuid = requireNotNull(group.customIconUuid)

        assertEquals("Folder icon", updated.content.meta.customIcons[iconUuid]?.name)
        assertTrue(updated.content.meta.customIcons[iconUuid]?.data?.contentEquals(png) == true)
    }

    @Test
    fun `attachment actions distinguish equal payload names and retain history binaries`() {
        val groupUuid = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val sharedBytes = "same payload".toByteArray()
        val sharedBinary = BinaryData.Uncompressed(false, sharedBytes)
        val database = database()
            .modifyParentGroup {
                copy(
                    groups = listOf(
                        Group(
                            uuid = groupUuid,
                            name = "Attachments",
                            entries = listOf(
                                Entry(
                                    uuid = entryUuid,
                                    fields = EntryFields.of("Title" to EntryValue.Plain("Entry")),
                                    binaries = listOf(
                                        BinaryReference(sharedBinary.hash, "first.bin"),
                                        BinaryReference(sharedBinary.hash, "second.bin")
                                    )
                                )
                            )
                        )
                    )
                )
            }
            .modifyBinaries { mapOf(sharedBinary.hash to sharedBinary) }

        val renamed = KeePassNativeManagement.renameAttachment(
            database = database,
            entryUuid = entryUuid,
            hash = sharedBinary.hash,
            currentName = "first.bin",
            newName = "renamed.bin"
        )
        val added = KeePassNativeManagement.addAttachment(
            database = renamed,
            entryUuid = entryUuid,
            fileName = "third.bin",
            bytes = "third payload".toByteArray()
        )
        val thirdHash = findEntry(added, entryUuid)!!.binaries.single { it.name == "third.bin" }.hash
        val removed = KeePassNativeManagement.deleteAttachment(
            database = added,
            entryUuid = entryUuid,
            hash = sharedBinary.hash,
            currentName = "renamed.bin"
        )

        val current = findEntry(removed, entryUuid)!!
        assertEquals(listOf("second.bin", "third.bin"), current.binaries.map { it.name })
        assertTrue(current.history.isNotEmpty())
        assertTrue(current.history.any { version ->
            version.binaries.any { it.hash == sharedBinary.hash && it.name == "renamed.bin" }
        })
        assertTrue(removed.binaries.containsKey(sharedBinary.hash))
        assertTrue(removed.binaries.containsKey(thirdHash))
    }

    @Test
    fun `natural sorting keeps folders first and recycle bin last`() {
        val recycleUuid = UUID.randomUUID()
        val database = database().modifyParentGroup {
            copy(
                groups = listOf(
                    Group(uuid = recycleUuid, name = "Recycle Bin"),
                    Group(uuid = UUID.randomUUID(), name = "Folder 10"),
                    Group(uuid = UUID.randomUUID(), name = "Folder 2")
                ),
                entries = listOf(
                    entry(UUID.randomUUID(), "Entry 10"),
                    entry(UUID.randomUUID(), "Entry 2")
                )
            )
        }.let { it.modifyContent { copy(meta = meta.copy(recycleBinEnabled = true, recycleBinUuid = recycleUuid)) } }
        val session = KeePassNativeSessionBuilder.build(
            databaseId = 1L,
            sourceRevision = KeePassSourceRevision("sort", 1),
            database = database,
            pathKeyBuilder = { parent, name -> if (parent == null) name else "$parent/$name" }
        )
        val browser = KeePassNativeBrowserBuilder.build(session)

        val sorted = KeePassNativeManagement.sortChildren(
            groups = browser.groups.filter { it.parentGroup == browser.rootGroup.identity },
            entries = browser.entries.filter { it.parentGroup == browser.rootGroup.identity },
            options = KeePassNativeSortOptions()
        )

        assertEquals(listOf("Folder 2", "Folder 10", "Recycle Bin"), sorted.groups.map { it.name })
        assertEquals(listOf("Entry 2", "Entry 10"), sorted.entries.map { it.title })
        assertFalse(sorted.entries.first().isInRecycleBin)
    }

    private fun database(): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica native management test", name = "Management"),
        credentials = Credentials.from(EncryptedValue.fromString("password"))
    )

    private fun entry(uuid: UUID, title: String): Entry = Entry(
        uuid = uuid,
        fields = EntryFields.of("Title" to EntryValue.Plain(title))
    )

    private fun allEntries(database: KeePassDatabase): List<Entry> = allEntries(database.content.group)

    private fun allEntries(group: Group): List<Entry> = group.entries + group.groups.flatMap(::allEntries)

    private fun findEntry(database: KeePassDatabase, uuid: UUID): Entry? = allEntries(database).firstOrNull { it.uuid == uuid }

    private fun findGroup(database: KeePassDatabase, uuid: UUID): Group? = findGroup(database.content.group, uuid)

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child -> findGroup(child, uuid)?.let { return it } }
        return null
    }
}
