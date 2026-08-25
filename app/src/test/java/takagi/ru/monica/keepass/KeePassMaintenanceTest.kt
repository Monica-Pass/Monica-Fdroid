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
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassMaintenanceTest {
    @Test
    fun `integrity report detects duplicate identities missing resources and unreferenced resources`() {
        val duplicateGroupUuid = UUID.randomUUID()
        val duplicateEntryUuid = UUID.randomUUID()
        val referencedIconUuid = UUID.randomUUID()
        val orphanIconUuid = UUID.randomUUID()
        val referencedBinary = BinaryData.Uncompressed(false, "used".toByteArray())
        val orphanBinary = BinaryData.Uncompressed(false, "unused".toByteArray())
        val missingBinary = BinaryData.Uncompressed(false, "missing".toByteArray())
        val database = database()
            .modifyParentGroup {
                copy(
                    groups = listOf(
                        Group(
                            uuid = duplicateGroupUuid,
                            name = "One",
                            entries = listOf(
                                entry(
                                    duplicateEntryUuid,
                                    "One",
                                    referencedIconUuid,
                                    listOf(
                                        BinaryReference(referencedBinary.hash, "used.bin"),
                                        BinaryReference(missingBinary.hash, "missing.bin")
                                    )
                                )
                            )
                        ),
                        Group(
                            uuid = duplicateGroupUuid,
                            name = "Two",
                            entries = listOf(entry(duplicateEntryUuid, "Two"))
                        )
                    )
                )
            }
            .modifyBinaries { mapOf(referencedBinary.hash to referencedBinary, orphanBinary.hash to orphanBinary) }
            .modifyCustomIcons {
                mapOf(
                    referencedIconUuid to CustomIcon(byteArrayOf(1), "Used", null),
                    orphanIconUuid to CustomIcon(byteArrayOf(2), "Unused", null)
                )
            }

        val report = KeePassDatabaseMaintenance.inspect(database)

        assertEquals(mapOf(duplicateGroupUuid to 2), report.duplicateGroupUuids)
        assertEquals(mapOf(duplicateEntryUuid to 2), report.duplicateEntryUuids)
        assertEquals(setOf(missingBinary.hash), report.missingBinaryHashes)
        assertEquals(setOf(orphanBinary.hash), report.unreferencedBinaryHashes)
        assertEquals(setOf(orphanIconUuid), report.unreferencedCustomIconUuids)
        assertTrue(report.hasProblems)
    }

    @Test
    fun `repair reassigns duplicate UUIDs and removes only unreferenced resources`() {
        val duplicateGroupUuid = UUID.randomUUID()
        val duplicateEntryUuid = UUID.randomUUID()
        val usedBinary = BinaryData.Uncompressed(false, "used".toByteArray())
        val unusedBinary = BinaryData.Uncompressed(false, "unused".toByteArray())
        val usedIconUuid = UUID.randomUUID()
        val unusedIconUuid = UUID.randomUUID()
        val database = database()
            .modifyParentGroup {
                copy(
                    groups = listOf(
                        Group(uuid = duplicateGroupUuid, name = "One", entries = listOf(entry(duplicateEntryUuid, "One", usedIconUuid, listOf(BinaryReference(usedBinary.hash, "used"))))),
                        Group(uuid = duplicateGroupUuid, name = "Two", entries = listOf(entry(duplicateEntryUuid, "Two")))
                    )
                )
            }
            .modifyBinaries { mapOf(usedBinary.hash to usedBinary, unusedBinary.hash to unusedBinary) }
            .modifyCustomIcons {
                mapOf(
                    usedIconUuid to CustomIcon(byteArrayOf(3), "Used", null),
                    unusedIconUuid to CustomIcon(byteArrayOf(4), "Unused", null)
                )
            }

        val repaired = KeePassDatabaseMaintenance.repair(database)

        assertFalse(repaired.after.hasProblems)
        assertEquals(2, allGroups(repaired.database.content.group).map { it.uuid }.toSet().size - 1)
        assertEquals(2, allEntries(repaired.database.content.group).map { it.uuid }.toSet().size)
        assertTrue(repaired.database.binaries.containsKey(usedBinary.hash))
        assertFalse(repaired.database.binaries.containsKey(unusedBinary.hash))
        assertTrue(repaired.database.content.meta.customIcons.containsKey(usedIconUuid))
        assertFalse(repaired.database.content.meta.customIcons.containsKey(unusedIconUuid))
        assertTrue(repaired.actions.any { it.type == KeePassMaintenanceActionType.REASSIGN_DUPLICATE_UUID })
    }

    private fun database(): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica maintenance test", name = "Maintenance"),
        credentials = Credentials.from(EncryptedValue.fromString("password"))
    )

    private fun entry(
        uuid: UUID,
        title: String,
        iconUuid: UUID? = null,
        binaries: List<BinaryReference> = emptyList()
    ): Entry = Entry(
        uuid = uuid,
        customIconUuid = iconUuid,
        fields = EntryFields.of("Title" to EntryValue.Plain(title)),
        binaries = binaries
    )

    private fun allGroups(group: Group): List<Group> = listOf(group) + group.groups.flatMap(::allGroups)

    private fun allEntries(group: Group): List<Entry> = group.entries + group.groups.flatMap(::allEntries)
}
