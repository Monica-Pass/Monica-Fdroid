package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassConflictCenterTest {
    @Test
    fun `snapshot reports entry group metadata tombstone binary and icon changes`() {
        val groupUuid = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val iconUuid = UUID.randomUUID()
        val binary = BinaryData.Uncompressed(false, "base attachment".toByteArray())
        val base = database("Base")
            .modifyParentGroup {
                copy(groups = listOf(Group(uuid = groupUuid, name = "Accounts", entries = listOf(entry(entryUuid, "Base")))))
            }
        val local = base
            .modifyParentGroup { copy(groups = groups.map { it.copy(name = "Local accounts", entries = listOf(entry(entryUuid, "Local"))) }) }
            .modifyContent {
                copy(
                    meta = meta.copy(description = "Local description"),
                    deletedObjects = listOf(DeletedObject(UUID.randomUUID(), Instant.parse("2026-08-18T00:00:00Z")))
                )
            }
            .modifyBinaries { mapOf(binary.hash to binary) }
            .modifyCustomIcons {
                mapOf(iconUuid to CustomIcon(byteArrayOf(1, 2, 3), "Local icon", null))
            }
        val remoteBinary = BinaryData.Uncompressed(false, "remote attachment".toByteArray())
        val remote = base
            .modifyParentGroup { copy(groups = groups.map { it.copy(entries = listOf(entry(entryUuid, "Remote"))) }) }
            .modifyBinaries { mapOf(remoteBinary.hash to remoteBinary) }

        val snapshot = KeePassConflictCenter.inspect(base, local, remote)

        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.ENTRY && it.ambiguous })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.GROUP })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.DATABASE_METADATA })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.DELETED_OBJECT })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.BINARY })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.CUSTOM_ICON })
        assertFalse(snapshot.mergeRecommended)
    }

    @Test
    fun `whole database decisions preserve the selected side and cancellation writes nothing`() {
        val base = database("Base")
        val local = base.modifyContent { copy(meta = meta.copy(name = "Local")) }
        val remote = base.modifyContent { copy(meta = meta.copy(name = "Remote")) }

        val keepLocal = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.KEEP_LOCAL)
        val useRemote = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.USE_REMOTE)
        val cancel = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.CANCEL)

        assertEquals(local, keepLocal.database)
        assertEquals(remote, useRemote.database)
        assertNull(cancel.database)
        assertTrue(cancel.cancelled)
    }

    @Test
    fun `merge keeps independent changes and creates a conflict copy for divergent entry edits`() {
        val sharedUuid = UUID.randomUUID()
        val localOnlyUuid = UUID.randomUUID()
        val remoteOnlyUuid = UUID.randomUUID()
        val base = database("Base").modifyParentGroup {
            copy(entries = listOf(entry(sharedUuid, "Shared")))
        }
        val local = base.modifyParentGroup {
            copy(entries = listOf(entry(sharedUuid, "Local shared"), entry(localOnlyUuid, "Local only")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(entry(sharedUuid, "Remote shared"), entry(remoteOnlyUuid, "Remote only")))
        }

        val result = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.MERGE)

        assertNotNull(result.database)
        val merged = result.database!!
        assertNotNull(findEntry(merged.content.group, sharedUuid))
        assertNotNull(findEntry(merged.content.group, localOnlyUuid))
        assertNotNull(findEntry(merged.content.group, remoteOnlyUuid))
        assertEquals("Local shared", title(findEntry(merged.content.group, sharedUuid)!!))
        assertTrue(allEntries(merged.content.group).any { title(it).contains("conflict", ignoreCase = true) })
        assertEquals(1, result.conflictCopyCount)
    }

    @Test
    fun `selected merge combines independent field edits without a conflict copy`() {
        val entryUuid = UUID.randomUUID()
        val baseEntry = credentialEntry(entryUuid, "Account", "base-user", "base-password")
        val base = database("Base").modifyParentGroup { copy(entries = listOf(baseEntry)) }
        val local = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "local-user", "base-password")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "base-user", "remote-password")))
        }

        val snapshot = KeePassConflictCenter.inspect(base, local, remote)
        val result = KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap())

        assertEquals(0, snapshot.ambiguousCount)
        assertEquals(0, result.conflictCopyCount)
        val mergedEntry = findEntry(result.database!!.content.group, entryUuid)!!
        assertEquals("local-user", field(mergedEntry, "UserName"))
        assertEquals("remote-password", field(mergedEntry, "Password"))
    }

    @Test
    fun `selected merge requires and applies a choice for a divergent field`() {
        val entryUuid = UUID.randomUUID()
        val base = database("Base").modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "base-user", "password")))
        }
        val local = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "local-user", "password")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "remote-user", "password")))
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote)
            .items.single { it.objectType == KeePassConflictObjectType.ENTRY }
            .details.single { it.kind == KeePassConflictDetailKind.FIELD && it.label == "UserName" }
        val missingChoice = runCatching {
            KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap())
        }.exceptionOrNull()
        assertTrue(missingChoice is IllegalArgumentException)

        val localResult = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.LOCAL)
        )
        val remoteResult = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.REMOTE)
        )

        assertEquals("local-user", field(findEntry(localResult.database!!.content.group, entryUuid)!!, "UserName"))
        assertEquals("remote-user", field(findEntry(remoteResult.database!!.content.group, entryUuid)!!, "UserName"))
    }

    @Test
    fun `protected field conflict hides values while preserving selected protection`() {
        val entryUuid = UUID.randomUUID()
        val base = database("Base").modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "user", "base-secret")))
        }
        val local = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "user", "local-secret")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "user", "remote-secret")))
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote)
            .items.single { it.objectType == KeePassConflictObjectType.ENTRY }
            .details.single { it.kind == KeePassConflictDetailKind.FIELD && it.label == "Password" }

        assertTrue(detail.protectedValue)
        assertFalse(detail.localSummary.orEmpty().contains("local-secret"))
        assertFalse(detail.remoteSummary.orEmpty().contains("remote-secret"))

        val result = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.REMOTE)
        )
        val password = findEntry(result.database!!.content.group, entryUuid)!!.fields.getValue("Password")
        assertTrue(password is EntryValue.Encrypted)
        assertEquals("remote-secret", password.content)
    }

    @Test
    fun `selected merge resolves deletion against an edited entry`() {
        val entryUuid = UUID.randomUUID()
        val baseEntry = credentialEntry(entryUuid, "Account", "base-user", "password")
        val base = database("Base").modifyParentGroup { copy(entries = listOf(baseEntry)) }
        val local = base.modifyParentGroup { copy(entries = emptyList()) }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "remote-user", "password")))
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote)
            .items.single { it.objectType == KeePassConflictObjectType.ENTRY }
            .details.single { it.kind == KeePassConflictDetailKind.EXISTENCE }
        val deleteResult = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.LOCAL)
        )
        val keepResult = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.REMOTE)
        )

        assertNull(findEntry(deleteResult.database!!.content.group, entryUuid))
        assertEquals("remote-user", field(findEntry(keepResult.database!!.content.group, entryUuid)!!, "UserName"))
    }

    @Test
    fun `selected merge resolves divergent entry locations`() {
        val entryUuid = UUID.randomUUID()
        val sourceUuid = UUID.randomUUID()
        val localTargetUuid = UUID.randomUUID()
        val remoteTargetUuid = UUID.randomUUID()
        val sharedEntry = credentialEntry(entryUuid, "Account", "user", "password")
        val base = database("Base").modifyParentGroup {
            copy(
                groups = listOf(
                    Group(uuid = sourceUuid, name = "Source", entries = listOf(sharedEntry)),
                    Group(uuid = localTargetUuid, name = "Local target"),
                    Group(uuid = remoteTargetUuid, name = "Remote target")
                )
            )
        }
        val local = base.modifyParentGroup {
            copy(
                groups = groups.map { group ->
                    when (group.uuid) {
                        sourceUuid -> group.copy(entries = emptyList())
                        localTargetUuid -> group.copy(entries = listOf(sharedEntry))
                        else -> group
                    }
                }
            )
        }
        val remote = base.modifyParentGroup {
            copy(
                groups = groups.map { group ->
                    when (group.uuid) {
                        sourceUuid -> group.copy(entries = emptyList())
                        remoteTargetUuid -> group.copy(entries = listOf(sharedEntry))
                        else -> group
                    }
                }
            )
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote)
            .items.single { it.objectType == KeePassConflictObjectType.ENTRY }
            .details.single { it.kind == KeePassConflictDetailKind.LOCATION }
        val result = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.LOCAL)
        )

        assertEquals(localTargetUuid, findEntryParent(result.database!!.content.group, entryUuid))
    }

    private fun database(name: String): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica conflict test", name = name),
        credentials = Credentials.from(EncryptedValue.fromString("password"))
    )

    private fun entry(uuid: UUID, title: String): Entry = Entry(
        uuid = uuid,
        fields = EntryFields.of("Title" to EntryValue.Plain(title))
    )

    private fun credentialEntry(
        uuid: UUID,
        title: String,
        username: String,
        password: String
    ): Entry = Entry(
        uuid = uuid,
        fields = EntryFields.of(
            "Title" to EntryValue.Plain(title),
            "UserName" to EntryValue.Plain(username),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString(password))
        )
    )

    private fun field(entry: Entry, name: String): String = entry.fields.getValue(name).content

    private fun title(entry: Entry): String = entry.fields.getValue("Title").content

    private fun findEntry(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        group.groups.forEach { child -> findEntry(child, uuid)?.let { return it } }
        return null
    }

    private fun allEntries(group: Group): List<Entry> = group.entries + group.groups.flatMap(::allEntries)

    private fun findEntryParent(group: Group, uuid: UUID): UUID? {
        if (group.entries.any { it.uuid == uuid }) return group.uuid
        group.groups.forEach { child -> findEntryParent(child, uuid)?.let { return it } }
        return null
    }
}
