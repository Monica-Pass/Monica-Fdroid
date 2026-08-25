package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.CustomDataValue
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
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassNativeEntryMutationTest {
    @Test
    fun `generic ordered field replacement preserves protection metadata and creates history`() {
        val entryUuid = UUID.randomUUID()
        val original = Entry(
            uuid = entryUuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Original"),
                "Plugin Secret" to EntryValue.Encrypted(EncryptedValue.fromString("old-secret")),
                "Opaque" to EntryValue.Plain("retain")
            ),
            tags = listOf("plugin"),
            customData = mapOf("plugin-state" to CustomDataValue("opaque", null)),
            autoType = AutoTypeData(enabled = false),
            foregroundColor = "#ffffff",
            backgroundColor = "#101010"
        )
        val database = database().modifyParentGroup { copy(entries = listOf(original)) }
        val changeSet = KeePassChangeSet(
            databaseId = 1L,
            target = KeePassChangeTarget.UNKNOWN_ENTRY,
            operation = KeePassChangeOperation.FIELD_PATCH,
            entryUuid = entryUuid.toString(),
            baseFingerprint = null,
            fieldPatch = KeePassFieldChangePatch(
                managedScope = KeePassManagedFieldScope.EXPLICIT_ONLY,
                replacementFields = listOf(
                    KeePassFieldChange("Opaque", "retain"),
                    KeePassFieldChange("Title", "Updated"),
                    KeePassFieldChange("Plugin Secret", "new-secret", protected = true),
                    KeePassFieldChange("New Field", "new-value")
                ),
                replaceAllFields = true
            )
        )

        val updated = findEntry(KeePassChangeSetApplier().apply(database, changeSet).updatedDatabase, entryUuid)!!

        assertEquals(listOf("Opaque", "Title", "Plugin Secret", "New Field"), updated.fields.keys.toList())
        assertEquals("Updated", updated.fields.getValue("Title").content)
        assertTrue(updated.fields.getValue("Plugin Secret") is EntryValue.Encrypted)
        assertEquals("new-secret", updated.fields.getValue("Plugin Secret").content)
        assertEquals(original.tags, updated.tags)
        assertEquals(original.customData, updated.customData)
        assertEquals(original.autoType, updated.autoType)
        assertEquals(original.foregroundColor, updated.foregroundColor)
        assertEquals(original.backgroundColor, updated.backgroundColor)
        assertEquals(1, updated.history.size)
        assertEquals("Original", updated.history.single().fields.getValue("Title").content)
        assertTrue(updated.history.single().fields.getValue("Plugin Secret") is EntryValue.Encrypted)
    }

    @Test
    fun `history restore keeps identity and all history while snapshot becomes current`() {
        val entryUuid = UUID.randomUUID()
        val historical = Entry(
            uuid = UUID.randomUUID(),
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Historical"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("old-password"))
            ),
            tags = listOf("old-tag"),
            foregroundColor = "#00ff00"
        )
        val current = Entry(
            uuid = entryUuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Current"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("current-password"))
            ),
            history = listOf(historical),
            tags = listOf("current-tag")
        )
        val database = database().modifyParentGroup { copy(entries = listOf(current)) }
        val changeSet = historyChange(entryUuid, KeePassChangeOperation.RESTORE_HISTORY, index = 0)

        val updated = findEntry(KeePassChangeSetApplier().apply(database, changeSet).updatedDatabase, entryUuid)!!

        assertEquals(entryUuid, updated.uuid)
        assertEquals("Historical", updated.fields.getValue("Title").content)
        assertEquals("old-password", updated.fields.getValue("Password").content)
        assertEquals(listOf("old-tag"), updated.tags)
        assertEquals("#00ff00", updated.foregroundColor)
        assertEquals(2, updated.history.size)
        assertTrue(updated.history.any { it.fields.getValue("Title").content == "Historical" })
        assertTrue(updated.history.any { it.fields.getValue("Title").content == "Current" })
    }

    @Test
    fun `history deletion removes only selected snapshot without creating another version`() {
        val entryUuid = UUID.randomUUID()
        val first = entry("First")
        val second = entry("Second")
        val current = entry("Current").copy(uuid = entryUuid, history = listOf(first, second))
        val database = database().modifyParentGroup { copy(entries = listOf(current)) }
        val changeSet = historyChange(entryUuid, KeePassChangeOperation.DELETE_HISTORY, index = 0)

        val updated = findEntry(KeePassChangeSetApplier().apply(database, changeSet).updatedDatabase, entryUuid)!!

        assertEquals("Current", updated.fields.getValue("Title").content)
        assertEquals(listOf("Second"), updated.history.map { it.fields.getValue("Title").content })
    }

    @Test
    fun `history change sets round trip and reject missing history patches`() {
        val uuid = UUID.randomUUID()
        val restore = historyChange(uuid, KeePassChangeOperation.RESTORE_HISTORY, index = 2)
        val delete = historyChange(uuid, KeePassChangeOperation.DELETE_HISTORY, index = 1)

        assertEquals(restore, KeePassChangeSetCodec.decode(KeePassChangeSetCodec.encode(restore)))
        assertEquals(delete, KeePassChangeSetCodec.decode(KeePassChangeSetCodec.encode(delete)))
        assertTrue(restore.requiresBaseFingerprint())

        val error = runCatching {
            KeePassChangeSet(
                databaseId = 1L,
                target = KeePassChangeTarget.UNKNOWN_ENTRY,
                operation = KeePassChangeOperation.RESTORE_HISTORY,
                entryUuid = uuid.toString(),
                baseFingerprint = "base"
            )
        }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun `UUID group operations isolate duplicate same-path groups`() {
        val firstUuid = UUID.randomUUID()
        val secondUuid = UUID.randomUUID()
        val database = database().modifyParentGroup {
            copy(groups = listOf(Group(uuid = firstUuid, name = "Duplicate"), Group(uuid = secondUuid, name = "Duplicate")))
        }
        val rename = KeePassChangeSet(
            databaseId = 1L,
            target = KeePassChangeTarget.GROUP,
            operation = KeePassChangeOperation.RENAME_GROUP,
            entryUuid = null,
            baseFingerprint = null,
            structurePatch = KeePassStructureChangePatch(
                sourceGroupUuid = secondUuid.toString(),
                newGroupName = "Renamed"
            )
        )
        val renamedDatabase = KeePassChangeSetApplier().apply(database, rename).updatedDatabase

        assertEquals("Duplicate", findGroup(renamedDatabase.content.group, firstUuid)?.name)
        assertEquals("Renamed", findGroup(renamedDatabase.content.group, secondUuid)?.name)

        val duplicateName = rename.copy(
            changeId = "allow-duplicate-name",
            structurePatch = rename.structurePatch?.copy(newGroupName = "Duplicate")
        )
        val duplicateNamedDatabase = KeePassChangeSetApplier().apply(renamedDatabase, duplicateName).updatedDatabase
        assertEquals(2, duplicateNamedDatabase.content.group.groups.count { it.name == "Duplicate" })

        val move = KeePassChangeSet(
            databaseId = 1L,
            target = KeePassChangeTarget.GROUP,
            operation = KeePassChangeOperation.MOVE_GROUP,
            entryUuid = null,
            baseFingerprint = null,
            structurePatch = KeePassStructureChangePatch(
                sourceGroupUuid = firstUuid.toString(),
                targetGroupUuid = secondUuid.toString()
            )
        )
        val movedDatabase = KeePassChangeSetApplier().apply(duplicateNamedDatabase, move).updatedDatabase

        assertEquals(firstUuid, findGroup(movedDatabase.content.group, secondUuid)?.groups?.single()?.uuid)
        assertFalse(movedDatabase.content.group.groups.any { it.uuid == firstUuid })
    }

    private fun historyChange(
        entryUuid: UUID,
        operation: KeePassChangeOperation,
        index: Int
    ): KeePassChangeSet = KeePassChangeSet(
        databaseId = 1L,
        target = KeePassChangeTarget.UNKNOWN_ENTRY,
        operation = operation,
        entryUuid = entryUuid.toString(),
        baseFingerprint = null,
        historyPatch = KeePassHistoryChangePatch(index)
    )

    private fun database(): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica native mutation test", name = "Native mutation"),
        credentials = Credentials.from(EncryptedValue.fromString("password"))
    )

    private fun entry(title: String): Entry = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields.of("Title" to EntryValue.Plain(title))
    )

    private fun findEntry(database: KeePassDatabase, uuid: UUID): Entry? =
        findEntry(database.content.group, uuid)

    private fun findEntry(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        group.groups.forEach { child -> findEntry(child, uuid)?.let { return it } }
        return null
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child -> findGroup(child, uuid)?.let { return it } }
        return null
    }
}
