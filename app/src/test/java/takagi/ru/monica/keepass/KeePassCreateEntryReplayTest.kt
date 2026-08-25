package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassCreateEntryReplayTest {
    @Test
    fun fullNativeCreateReplaysTemplateMetadataAndOpaqueEntryState() {
        val entryUuid = UUID.randomUUID()
        val templateGroupUuid = UUID.randomUUID()
        val customIconUuid = UUID.randomUUID()
        val attachmentBytes = "large-entry-attachment".toByteArray()
        val attachment: BinaryData = BinaryData.Uncompressed(false, attachmentBytes).toCompressed()
        val iconBytes = byteArrayOf(1, 3, 5, 7)
        val iconModified = Instant.ofEpochMilli(1_234L)
        val metaChanged = Instant.ofEpochMilli(5_678L)
        val database = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(generator = "Monica replay test", name = "Fixture"),
            credentials = Credentials.from(EncryptedValue.fromString("password")),
        ).modifyParentGroup {
            copy(groups = listOf(Group(uuid = templateGroupUuid, name = "Templates")))
        }.modifyBinaries {
            it + (attachment.hash to attachment)
        }.modifyCustomIcons {
            it + (customIconUuid to CustomIcon(iconBytes, "Template icon", iconModified))
        }
        val history = KeePassEntryTreeSnapshot(
            uuid = entryUuid.toString(),
            fields = listOf(
                KeePassFieldChange("Title", "Previous template", false),
                KeePassFieldChange("PluginField", "history-plugin", false),
            ),
            binaries = listOf(
                KeePassBinaryReferencePatch("previous.bin", attachment.hash.hex()),
            ),
            customIconUuid = customIconUuid.toString(),
        )
        val nativeEntry = KeePassEntryTreeSnapshot(
            uuid = entryUuid.toString(),
            fields = listOf(
                KeePassFieldChange("Title", "Server template", false),
                KeePassFieldChange("Password", "secret", true),
                KeePassFieldChange("_etm_template", "1", false),
                KeePassFieldChange("PluginField", "opaque-plugin-value", false),
            ),
            binaries = listOf(
                KeePassBinaryReferencePatch("proof.bin", attachment.hash.hex()),
            ),
            history = listOf(history),
            customIconUuid = customIconUuid.toString(),
            foregroundColor = "#112233",
            backgroundColor = "#445566",
            overrideUrl = "cmd://template",
            autoType = KeePassAutoTypePatch(
                enabled = true,
                defaultSequence = "{USERNAME}{TAB}{PASSWORD}{ENTER}",
                items = listOf(
                    KeePassAutoTypeItemPatch("Server *", "{PASSWORD}{ENTER}"),
                ),
            ),
            tags = listOf("template", "server"),
            customData = listOf(
                KeePassCustomDataPatch("plugin-state", "preserve-me", 2_345L),
            ),
            qualityCheck = false,
        )
        val changeSet = KeePassChangeSet(
            databaseId = 42L,
            target = KeePassChangeTarget.UNKNOWN_ENTRY,
            operation = KeePassChangeOperation.CREATE_ENTRY,
            entryUuid = entryUuid.toString(),
            baseFingerprint = null,
            entryPatch = KeePassEntryCreatePatch(
                targetGroupUuid = templateGroupUuid.toString(),
                fields = nativeEntry.fields,
                nativeEntry = nativeEntry,
                binaryPool = listOf(
                    KeePassBinaryPoolItemPatch(
                        hash = attachment.hash.hex(),
                        protected = false,
                        compressed = attachment is BinaryData.Compressed,
                        contentBase64 = Base64.getEncoder().encodeToString(attachment.rawContent),
                        rawStorageContent = true,
                    ),
                ),
                customIconPool = listOf(
                    KeePassCustomIconPoolItemPatch(
                        uuid = customIconUuid.toString(),
                        dataBase64 = Base64.getEncoder().encodeToString(iconBytes),
                        name = "Template icon",
                        lastModifiedEpochMillis = iconModified.toEpochMilli(),
                    ),
                ),
            ),
            databaseMetaPatch = KeePassDatabaseMetaPatch(
                entryTemplatesGroupUuid = templateGroupUuid.toString(),
                entryTemplatesGroupChangedEpochMillis = metaChanged.toEpochMilli(),
            ),
        )

        val replayedChangeSet = KeePassChangeSetCodec.decode(KeePassChangeSetCodec.encode(changeSet))
        val updated = KeePassChangeSetApplier().apply(database, replayedChangeSet).updatedDatabase
        val entry = updated.content.group.groups.single().entries.single()

        assertEquals(entryUuid, entry.uuid)
        assertEquals("opaque-plugin-value", entry.fields.getValue("PluginField").content)
        assertTrue(entry.fields.getValue("Password") is EntryValue.Encrypted)
        assertEquals(listOf("template", "server"), entry.tags)
        assertEquals("preserve-me", entry.customData.getValue("plugin-state").value)
        assertEquals("{USERNAME}{TAB}{PASSWORD}{ENTER}", entry.autoType!!.defaultSequence)
        assertEquals("Server *", entry.autoType!!.items.single().window)
        assertEquals("proof.bin", entry.binaries.single().name)
        assertEquals("history-plugin", entry.history.single().fields.getValue("PluginField").content)
        assertEquals(customIconUuid, entry.customIconUuid)
        assertEquals("cmd://template", entry.overrideUrl)
        assertFalse(entry.qualityCheck)
        val replayedAttachment = updated.binaries.getValue(attachment.hash)
        assertTrue(replayedAttachment.rawContent.contentEquals(attachment.rawContent))
        assertTrue(replayedAttachment.inputStream().use { it.readBytes() }.contentEquals(attachmentBytes))
        assertTrue(updated.content.meta.customIcons.getValue(customIconUuid).data.contentEquals(iconBytes))
        assertEquals(templateGroupUuid, updated.content.meta.entryTemplatesGroup)
        assertEquals(metaChanged, updated.content.meta.entryTemplatesGroupChanged)
    }
}
