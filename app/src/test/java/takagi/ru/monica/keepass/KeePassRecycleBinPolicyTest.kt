package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class KeePassRecycleBinPolicyTest {
    @Test
    fun softDeleteCreatesNativeRecycleBinWhenDatabaseDoesNotHaveOne() {
        val accountsUuid = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val database = database(
            accountsUuid = accountsUuid,
            entryUuid = entryUuid,
            meta = Meta(
                generator = "Monica recycle policy test",
                name = "No recycle bin",
                recycleBinEnabled = false,
                recycleBinUuid = null
            )
        )

        val updated = KeePassChangeSetApplier().apply(
            database,
            recycleMoveChange(entryUuid, accountsUuid)
        ).updatedDatabase

        assertTrue(updated.content.meta.recycleBinEnabled)
        val recycleBinUuid = assertNotNull(updated.content.meta.recycleBinUuid).let {
            updated.content.meta.recycleBinUuid!!
        }
        val recycleBin = findGroup(updated.content.group, recycleBinUuid)
        assertNotNull(recycleBin)
        assertTrue(findGroup(updated.content.group, accountsUuid)!!.entries.none { it.uuid == entryUuid })
        val recycled = recycleBin!!.entries.single { it.uuid == entryUuid }
        assertEquals(accountsUuid, recycled.previousParentGroup)
        assertTrue(updated.content.deletedObjects.none { it.id == entryUuid })
    }

    @Test
    fun softDeleteRepairsBrokenRecycleBinMetadataInsteadOfPermanentlyDeleting() {
        val accountsUuid = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val missingRecycleBinUuid = UUID.randomUUID()
        val database = database(
            accountsUuid = accountsUuid,
            entryUuid = entryUuid,
            meta = Meta(
                generator = "Monica recycle policy test",
                name = "Broken recycle bin",
                recycleBinEnabled = true,
                recycleBinUuid = missingRecycleBinUuid
            )
        )

        val updated = KeePassChangeSetApplier().apply(
            database,
            recycleMoveChange(entryUuid, accountsUuid)
        ).updatedDatabase

        val repairedRecycleBinUuid = updated.content.meta.recycleBinUuid
        assertNotNull(repairedRecycleBinUuid)
        assertTrue(findGroup(updated.content.group, repairedRecycleBinUuid!!)!!.entries.any { it.uuid == entryUuid })
        assertFalse(updated.content.deletedObjects.any { it.id == entryUuid })
    }

    @Test
    fun requestedRecycleBinDeleteNeverAllowsPermanentDeleteFallback() {
        assertFalse(KeePassDeletePolicy.allowPermanentFallback(useRecycleBin = true))
        assertTrue(KeePassDeletePolicy.allowPermanentFallback(useRecycleBin = false))
    }

    private fun database(
        accountsUuid: UUID,
        entryUuid: UUID,
        meta: Meta
    ): KeePassDatabase {
        return KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = meta,
            credentials = Credentials.from(EncryptedValue.fromString("fixture-password"))
        ).modifyParentGroup {
            copy(
                groups = listOf(
                    Group(
                        uuid = accountsUuid,
                        name = "Accounts",
                        entries = listOf(
                            Entry(
                                uuid = entryUuid,
                                fields = EntryFields.of(
                                    "Title" to EntryValue.Plain("GitHub"),
                                    "UserName" to EntryValue.Plain("octocat")
                                )
                            )
                        )
                    )
                )
            )
        }
    }

    private fun recycleMoveChange(entryUuid: UUID, sourceGroupUuid: UUID): KeePassChangeSet {
        return KeePassChangeSet(
            changeId = "move-to-recycle",
            databaseId = 42L,
            target = KeePassChangeTarget.PASSWORD,
            operation = KeePassChangeOperation.MOVE_TO_RECYCLE_BIN,
            entryUuid = entryUuid.toString(),
            baseFingerprint = "base",
            structurePatch = KeePassStructureChangePatch(
                sourceGroupUuid = sourceGroupUuid.toString(),
                previousParentGroupUuid = sourceGroupUuid.toString()
            )
        )
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child ->
            findGroup(child, uuid)?.let { return it }
        }
        return null
    }
}
