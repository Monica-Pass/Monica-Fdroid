package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
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
import org.junit.Test

class KeePassTemplateEngineTest {
    @Test
    fun `saving as template creates metadata group and preserves native payload`() {
        val binary = BinaryData.Uncompressed(false, "attachment".toByteArray())
        val sourceUuid = UUID.randomUUID()
        val source = Entry(
            uuid = sourceUuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("GitHub"),
                "UserName" to EntryValue.Plain("alice"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("secret")),
                "PluginField" to EntryValue.Plain("opaque"),
            ),
            binaries = listOf(BinaryReference(binary.hash, "proof.txt")),
            autoType = AutoTypeData(
                enabled = true,
                items = listOf(AutoTypeItem("GitHub", "{USERNAME}{TAB}{PASSWORD}{ENTER}")),
            ),
            tags = listOf("login"),
        )
        val groupUuid = UUID.randomUUID()
        val database = database().modifyContent {
            copy(group = group.copy(groups = listOf(Group(uuid = groupUuid, name = "Accounts", entries = listOf(source)))))
        }.modifyBinaries { mapOf(binary.hash to binary) }

        val result = KeePassTemplateEngine.saveAsTemplate(database, sourceUuid)
        val template = allEntries(result.database.content.group).single { it.uuid == result.entryUuid }

        assertNotNull(result.database.content.meta.entryTemplatesGroup)
        assertEquals(result.templateGroupUuid, result.database.content.meta.entryTemplatesGroup)
        assertEquals("opaque", template.fields.getValue("PluginField").content)
        assertEquals(listOf("proof.txt"), template.binaries.map { it.name })
        assertEquals(source.autoType, template.autoType)
        assertEquals(KeePassTemplateEngine.TEMPLATE_MARKER_VALUE, template.fields.getValue(KeePassTemplateEngine.TEMPLATE_MARKER_FIELD).content)
        assertTrue(KeePassTemplateEngine.listTemplates(result.database).any { it.uuid == result.entryUuid })
    }

    @Test
    fun `instantiating template removes marker and allocates a fresh entry`() {
        val sourceUuid = UUID.randomUUID()
        val targetUuid = UUID.randomUUID()
        val source = Entry(
            uuid = sourceUuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Template"),
                KeePassTemplateEngine.TEMPLATE_MARKER_FIELD to EntryValue.Plain(KeePassTemplateEngine.TEMPLATE_MARKER_VALUE),
                "UserName" to EntryValue.Plain("user"),
            ),
        )
        val templateGroupUuid = UUID.randomUUID()
        val database = database().modifyContent {
            copy(
                meta = meta.copy(entryTemplatesGroup = templateGroupUuid),
                group = group.copy(
                    groups = listOf(
                        Group(uuid = templateGroupUuid, name = KeePassTemplateEngine.TEMPLATE_GROUP_NAME, entries = listOf(source)),
                        Group(uuid = targetUuid, name = "Accounts"),
                    ),
                ),
            )
        }

        val result = KeePassTemplateEngine.instantiate(database, sourceUuid, targetUuid, "New account")
        val created = allEntries(result.database.content.group).single { it.uuid == result.entryUuid }

        assertNotEquals(sourceUuid, created.uuid)
        assertEquals("New account", created.fields.getValue("Title").content)
        assertFalse(created.fields.containsKey(KeePassTemplateEngine.TEMPLATE_MARKER_FIELD))
        assertEquals(listOf(sourceUuid, result.entryUuid), allEntries(result.database.content.group).map { it.uuid })
    }

    @Test
    fun `deleting template rejects normal entries and removes only template`() {
        val templateUuid = UUID.randomUUID()
        val regularUuid = UUID.randomUUID()
        val templateGroupUuid = UUID.randomUUID()
        val database = database().modifyContent {
            copy(
                meta = meta.copy(entryTemplatesGroup = templateGroupUuid),
                group = group.copy(
                    groups = listOf(
                        Group(
                            uuid = templateGroupUuid,
                            name = KeePassTemplateEngine.TEMPLATE_GROUP_NAME,
                            entries = listOf(
                                Entry(
                                    uuid = templateUuid,
                                    fields = EntryFields.of(
                                        "Title" to EntryValue.Plain("T"),
                                        KeePassTemplateEngine.TEMPLATE_MARKER_FIELD to EntryValue.Plain("1"),
                                    ),
                                ),
                            ),
                        ),
                        Group(uuid = UUID.randomUUID(), name = "Regular", entries = listOf(Entry(uuid = regularUuid))),
                    ),
                ),
            )
        }

        val deleted = KeePassTemplateEngine.deleteTemplate(database, templateUuid)
        assertFalse(allEntries(deleted.content.group).any { it.uuid == templateUuid })
        assertTrue(allEntries(deleted.content.group).any { it.uuid == regularUuid })
    }

    private fun database(): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica template test", name = "Templates"),
        credentials = Credentials.from(EncryptedValue.fromString("password")),
    )

    private fun allEntries(group: Group): List<Entry> = group.entries + group.groups.flatMap(::allEntries)
}
