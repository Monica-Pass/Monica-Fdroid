package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassEntryPresentationPatchTest {
    @Test
    fun selectingNewIconAddsPoolItemAndUpdatesEntry() {
        val entryUuid = UUID.randomUUID()
        val iconUuid = UUID.randomUUID()
        val database = fixtureDatabase(entryUuid)
        val patch = KeePassChangeSet(
            databaseId = 42,
            target = KeePassChangeTarget.PASSWORD,
            operation = KeePassChangeOperation.ENTRY_PRESENTATION_PATCH,
            entryUuid = entryUuid.toString(),
            baseFingerprint = KeePassEntryFingerprint.build(findEntry(database, entryUuid)!!),
            entryPresentationPatch = KeePassEntryPresentationPatch(
                customIconUuid = iconUuid.toString(),
                customIcon = KeePassCustomIconPoolItemPatch(
                    uuid = iconUuid.toString(),
                    dataBase64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                    name = "Uploaded",
                    lastModifiedEpochMillis = 123L,
                ),
                basePresentationSignature = KeePassEntryFingerprint.buildPresentation(findEntry(database, entryUuid)!!),
            ),
        )

        val updated = KeePassChangeSetApplier().apply(database, patch).updatedDatabase
        val entry = findEntry(updated, entryUuid)!!

        assertEquals(iconUuid, entry.customIconUuid)
        assertEquals(byteArrayOf(1, 2, 3).toList(), updated.content.meta.customIcons[iconUuid]!!.data.toList())
        assertEquals("Uploaded", updated.content.meta.customIcons[iconUuid]!!.name)
        assertEquals(1, entry.history.size)
    }

    @Test
    fun clearingCurrentReferenceKeepsIconUsedByHistory() {
        val entryUuid = UUID.randomUUID()
        val iconUuid = UUID.randomUUID()
        val database = fixtureDatabase(entryUuid).modifyCustomIcons {
            it + (iconUuid to CustomIcon(byteArrayOf(9), "Old", Instant.EPOCH))
        }.modifyParentGroup {
            updateEntry(this, entryUuid) { it.copy(customIconUuid = iconUuid) }
        }
        val patch = KeePassChangeSet(
            databaseId = 42,
            target = KeePassChangeTarget.PASSWORD,
            operation = KeePassChangeOperation.ENTRY_PRESENTATION_PATCH,
            entryUuid = entryUuid.toString(),
            baseFingerprint = KeePassEntryFingerprint.build(findEntry(database, entryUuid)!!),
            entryPresentationPatch = KeePassEntryPresentationPatch(
                clearCustomIcon = true,
                removeCustomIconUuid = iconUuid.toString(),
                basePresentationSignature = KeePassEntryFingerprint.buildPresentation(findEntry(database, entryUuid)!!),
            ),
        )

        val updated = KeePassChangeSetApplier().apply(database, patch).updatedDatabase

        assertNull(findEntry(updated, entryUuid)!!.customIconUuid)
        // The edit creates a history snapshot that still references the old icon;
        // removing it from the pool would corrupt that historical version.
        assertNotNull(updated.content.meta.customIcons[iconUuid])
    }

    @Test
    fun combinedEntryEditAppliesFieldsAndIconAsOnePatch() {
        val entryUuid = UUID.randomUUID()
        val iconUuid = UUID.randomUUID()
        val database = fixtureDatabase(entryUuid)
        val current = findEntry(database, entryUuid)!!
        val patch = KeePassChangeSet(
            databaseId = 42,
            target = KeePassChangeTarget.PASSWORD,
            operation = KeePassChangeOperation.ENTRY_EDIT_PATCH,
            entryUuid = entryUuid.toString(),
            baseFingerprint = KeePassEntryFingerprint.build(current),
            fieldPatch = KeePassFieldChangePatch(
                managedScope = KeePassManagedFieldScope.EXPLICIT_ONLY,
                replacementFields = listOf(
                    KeePassFieldChange("Title", "Updated", false),
                    KeePassFieldChange("Password", "new-secret", true),
                ),
                baseFields = current.fields.map { (name, value) ->
                    KeePassFieldBaseValue(
                        name = name,
                        value = value.content,
                        protected = value is EntryValue.Encrypted,
                    )
                },
                replaceAllFields = true,
            ),
            entryPresentationPatch = KeePassEntryPresentationPatch(
                customIconUuid = iconUuid.toString(),
                customIcon = KeePassCustomIconPoolItemPatch(
                    uuid = iconUuid.toString(),
                    dataBase64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(7, 8)),
                    name = "Combined",
                ),
                basePresentationSignature = KeePassEntryFingerprint.buildPresentation(current),
            ),
        )

        val updated = KeePassChangeSetApplier().apply(database, patch).updatedDatabase
        val entry = findEntry(updated, entryUuid)!!

        assertEquals("Updated", entry.fields["Title"]?.content)
        assertEquals("new-secret", entry.fields["Password"]?.content)
        assertEquals(iconUuid, entry.customIconUuid)
        assertEquals("Combined", updated.content.meta.customIcons[iconUuid]!!.name)
        assertEquals(1, entry.history.size)
    }

    private fun fixtureDatabase(entryUuid: UUID): KeePassDatabase {
        return KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(generator = "Monica presentation test", name = "Fixture"),
            credentials = Credentials.from(EncryptedValue.fromString("password")),
        ).modifyParentGroup {
            copy(
                entries = listOf(
                    Entry(
                        uuid = entryUuid,
                        fields = EntryFields.of(
                            "Title" to EntryValue.Plain("Icon test"),
                            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("secret")),
                        ),
                    ),
                ),
            )
        }
    }

    private fun findEntry(database: KeePassDatabase, uuid: UUID): Entry? =
        database.content.group.entries.firstOrNull { it.uuid == uuid }

    private fun updateEntry(
        group: app.keemobile.kotpass.models.Group,
        uuid: UUID,
        transform: (Entry) -> Entry,
    ): app.keemobile.kotpass.models.Group = group.copy(
        entries = group.entries.map { entry -> if (entry.uuid == uuid) transform(entry) else entry },
    )
}
