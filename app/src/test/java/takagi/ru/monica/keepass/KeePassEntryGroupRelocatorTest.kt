package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassEntryGroupRelocatorTest {
    @Test
    fun `moves complete entry between groups and applies field update`() {
        val entryUuid = UUID.randomUUID()
        val attachment = BinaryData.Uncompressed(false, "attachment".toByteArray())
        val history = entry(UUID.randomUUID(), "Old title")
        val sourceEntry = entry(entryUuid, "Original").copy(
            binaries = listOf(BinaryReference(attachment.hash, "receipt.pdf")),
            history = listOf(history),
            tags = listOf("finance"),
            customData = mapOf(
                "plugin" to CustomDataValue(
                    "preserve",
                    Instant.parse("2026-08-17T00:00:00Z")
                )
            )
        )
        val root = Group(
            uuid = UUID.randomUUID(),
            name = "Root",
            groups = listOf(
                Group(uuid = UUID.randomUUID(), name = "Source", entries = listOf(sourceEntry)),
                Group(uuid = UUID.randomUUID(), name = "Target")
            )
        )
        val titlePatch = titlePatch("Updated")

        val result = KeePassEntryGroupRelocator.updateOrMove(
            rootGroup = root,
            entryUuid = entryUuid,
            targetGroupSegments = listOf("Target"),
            update = titlePatch::applyTo
        )

        val source = result.rootGroup.groups.single { it.name == "Source" }
        val moved = result.rootGroup.groups.single { it.name == "Target" }
            .entries.single { it.uuid == entryUuid }
        assertTrue(result.found)
        assertTrue(result.moved)
        assertTrue(source.entries.isEmpty())
        assertEquals("Updated", moved.fields.getValue("Title").content)
        assertEquals("unknown", moved.fields.getValue("Plugin field").content)
        assertEquals(listOf("receipt.pdf"), moved.binaries.map { it.name })
        assertEquals(listOf(history.uuid), moved.history.map { it.uuid })
        assertEquals(listOf("finance"), moved.tags)
        assertEquals("preserve", moved.customData.getValue("plugin").value)
    }

    @Test
    fun `updates entry in place when target group is unchanged`() {
        val firstUuid = UUID.randomUUID()
        val secondUuid = UUID.randomUUID()
        val group = Group(
            uuid = UUID.randomUUID(),
            name = "Accounts",
            entries = listOf(entry(firstUuid, "First"), entry(secondUuid, "Second"))
        )
        val root = Group(uuid = UUID.randomUUID(), name = "Root", groups = listOf(group))
        val titlePatch = titlePatch("Updated")

        val result = KeePassEntryGroupRelocator.updateOrMove(
            rootGroup = root,
            entryUuid = firstUuid,
            targetGroupSegments = listOf("Accounts"),
            update = titlePatch::applyTo
        )

        val entries = result.rootGroup.groups.single().entries
        assertTrue(result.found)
        assertFalse(result.moved)
        assertEquals(listOf(firstUuid, secondUuid), entries.map { it.uuid })
        assertEquals("Updated", entries.first().fields.getValue("Title").content)
    }

    @Test
    fun `keeps tree unchanged when entry is missing`() {
        val root = Group(uuid = UUID.randomUUID(), name = "Root")

        val result = KeePassEntryGroupRelocator.updateOrMove(
            rootGroup = root,
            entryUuid = UUID.randomUUID(),
            targetGroupSegments = listOf("Target"),
            update = { it }
        )

        assertFalse(result.found)
        assertFalse(result.moved)
        assertEquals(root, result.rootGroup)
        assertNotNull(result.rootGroup)
    }

    private fun entry(uuid: UUID, title: String): Entry {
        return Entry(
            uuid = uuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain(title),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("secret")),
                "Plugin field" to EntryValue.Plain("unknown")
            )
        )
    }

    private fun titlePatch(title: String): KeePassEntryFieldPatch {
        return KeePassEntryFieldPatch.fromEntryFields(
            replacementFields = EntryFields.of("Title" to EntryValue.Plain(title)),
            removeManagedField = { false },
            removeFieldNames = setOf("Title")
        )
    }
}
