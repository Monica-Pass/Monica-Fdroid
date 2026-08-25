package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.data.KeePassKdfAlgorithm

class KeePassDatabaseSettingsTest {
    @Test
    fun `kdbx4 settings update changes native header and meta without rebuilding content`() {
        val recycleUuid = UUID.randomUUID()
        val templatesUuid = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val original = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(
                generator = "External KeePass client",
                name = "Before",
                customData = mapOf("plugin" to CustomDataValue("keep-me"))
            ),
            credentials = passwordCredentials("old-password")
        ).let { database ->
            database.copy(
                content = database.content.copy(
                    group = database.content.group.copy(
                        groups = listOf(
                            Group(uuid = recycleUuid, name = "Trash"),
                            Group(uuid = templatesUuid, name = "Templates")
                        ),
                        entries = listOf(
                            Entry(
                                uuid = entryUuid,
                                fields = EntryFields.of(
                                    "Title" to EntryValue.Plain("Preserve me"),
                                    "Plugin Field" to EntryValue.Plain("opaque")
                                )
                            )
                        )
                    )
                )
            )
        }
        val oldMasterSeed = original.header.masterSeed

        val updated = KeePassDatabaseSettingsEditor.apply(
            database = original,
            update = KeePassDatabaseSettingsUpdate(
                name = "After",
                description = "Native settings",
                defaultUsername = "octocat",
                color = "#4F7DFF",
                maintenanceHistoryDays = 120,
                historyMaxItems = 25,
                historyMaxSizeBytes = 8 * 1024 * 1024,
                masterKeyChangeRecommendationDays = 90,
                masterKeyChangeForceDays = 365,
                recycleBinEnabled = true,
                recycleBinGroupUuid = recycleUuid,
                templateGroupUuid = templatesUuid,
                compression = KeePassDatabaseCompression.NONE,
                cipherAlgorithm = KeePassCipherAlgorithm.CHACHA20,
                kdfAlgorithm = KeePassKdfAlgorithm.ARGON2ID,
                transformRounds = 5,
                memoryBytes = 48L * 1024L * 1024L,
                parallelism = 3
            ),
            nowProvider = { Instant.parse("2026-08-17T16:00:00Z") }
        )
        val snapshot = KeePassDatabaseSettingsEditor.snapshot(DATABASE_ID, updated, readOnly = true)

        assertEquals("After", snapshot.name)
        assertEquals("Native settings", snapshot.description)
        assertEquals("octocat", snapshot.defaultUsername)
        assertEquals("#4F7DFF", snapshot.color)
        assertEquals(120, snapshot.maintenanceHistoryDays)
        assertEquals(25, snapshot.historyMaxItems)
        assertEquals(8 * 1024 * 1024, snapshot.historyMaxSizeBytes)
        assertEquals(90, snapshot.masterKeyChangeRecommendationDays)
        assertEquals(365, snapshot.masterKeyChangeForceDays)
        assertTrue(snapshot.recycleBinEnabled)
        assertEquals(recycleUuid, snapshot.recycleBinGroupUuid)
        assertEquals(templatesUuid, snapshot.templateGroupUuid)
        assertEquals(KeePassDatabaseCompression.NONE, snapshot.compression)
        assertEquals(KeePassCipherAlgorithm.CHACHA20, snapshot.cipherAlgorithm)
        assertEquals(KeePassKdfAlgorithm.ARGON2ID, snapshot.kdfAlgorithm)
        assertEquals(5L, snapshot.transformRounds)
        assertEquals(48L * 1024L * 1024L, snapshot.memoryBytes)
        assertEquals(3, snapshot.parallelism)
        assertTrue(snapshot.readOnly)
        assertNotEquals(oldMasterSeed, updated.header.masterSeed)
        assertEquals("External KeePass client", updated.content.meta.generator)
        assertEquals("keep-me", updated.content.meta.customData.getValue("plugin").value)
        assertNotNull(findEntry(updated.content.group, entryUuid))
        assertEquals(
            "opaque",
            findEntry(updated.content.group, entryUuid)!!.fields.getValue("Plugin Field").content
        )
    }

    @Test
    fun `enabling recycle bin without a selected group creates one safely`() {
        val original = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(generator = "Fixture", name = "Recycle"),
            credentials = passwordCredentials("password")
        )

        val updated = KeePassDatabaseSettingsEditor.apply(
            original,
            KeePassDatabaseSettingsEditor.snapshot(DATABASE_ID, original, readOnly = false)
                .toUpdate()
                .copy(recycleBinEnabled = true, recycleBinGroupUuid = null)
        )

        val recycleUuid = updated.content.meta.recycleBinUuid
        assertTrue(updated.content.meta.recycleBinEnabled)
        assertNotNull(recycleUuid)
        assertNotNull(findGroup(updated.content.group, recycleUuid!!))
    }

    @Test
    fun `kdbx3 rejects unsupported chacha and argon settings`() {
        val original = KeePassDatabase.Ver3x.create(
            rootName = "Root",
            meta = Meta(generator = "Fixture", name = "KDBX3"),
            credentials = passwordCredentials("password")
        )
        val current = KeePassDatabaseSettingsEditor.snapshot(DATABASE_ID, original, readOnly = false)

        try {
            KeePassDatabaseSettingsEditor.apply(
                original,
                current.toUpdate().copy(
                    cipherAlgorithm = KeePassCipherAlgorithm.CHACHA20,
                    kdfAlgorithm = KeePassKdfAlgorithm.ARGON2ID
                )
            )
            fail("Expected unsupported KDBX3 settings to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("KDBX3"))
        }
    }

    @Test
    fun `compression setting maps to native header`() {
        val original = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(generator = "Fixture", name = "Compression"),
            credentials = passwordCredentials("password")
        )
        val update = KeePassDatabaseSettingsEditor.snapshot(DATABASE_ID, original, readOnly = false)
            .toUpdate()
            .copy(compression = KeePassDatabaseCompression.NONE)

        val updated = KeePassDatabaseSettingsEditor.apply(original, update)

        assertEquals(DatabaseHeader.Compression.None, updated.header.compression)
    }

    private fun passwordCredentials(password: String): Credentials =
        Credentials.from(EncryptedValue.fromString(password))

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

    private companion object {
        const val DATABASE_ID = 42L
    }
}
