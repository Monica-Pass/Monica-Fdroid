package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassNativeBrowserTest {
    @Test
    fun `unknown native entry remains visible with raw fields metadata attachment and history`() {
        val attachment = BinaryData.Uncompressed(
            memoryProtection = false,
            rawContent = "native attachment".toByteArray()
        )
        val history = Entry(
            uuid = UUID.randomUUID(),
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Old device"),
                "Plugin Secret" to EntryValue.Encrypted(EncryptedValue.fromString("old-secret"))
            )
        )
        val entryUuid = UUID.randomUUID()
        val entry = Entry(
            uuid = entryUuid,
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Device profile"),
                "Plugin Secret" to EntryValue.Encrypted(EncryptedValue.fromString("secret")),
                "Only Plugin Understands" to EntryValue.Plain("opaque")
            ),
            binaries = listOf(BinaryReference(hash = attachment.hash, name = "device.bin")),
            history = listOf(history),
            tags = listOf("hardware", "unknown-template"),
            customData = mapOf(
                "plugin-state" to CustomDataValue("retain-me", Instant.parse("2026-08-17T00:00:00Z"))
            ),
            autoType = AutoTypeData(enabled = false)
        )
        val database = database()
            .modifyBinaries { binaries -> binaries + (attachment.hash to attachment) }
            .modifyParentGroup { copy(entries = listOf(entry)) }
        val session = session(database)

        val browser = KeePassNativeBrowserBuilder.build(session)
        val record = browser.entriesByIdentity
            .getValue(KeePassNativeEntryIdentity(1L, entryUuid))
            .single()

        assertEquals(KeePassNativeEntryKind.UNKNOWN, record.kind)
        assertEquals("Device profile", record.title)
        assertEquals(listOf("Title", "Plugin Secret", "Only Plugin Understands"), record.fields.map { it.name })
        assertTrue(record.fields.single { it.name == "Plugin Secret" }.isProtected)
        assertEquals("secret", record.fields.single { it.name == "Plugin Secret" }.rawValue)
        assertEquals("opaque", record.fields.single { it.name == "Only Plugin Understands" }.displayValue)
        assertEquals(listOf("hardware", "unknown-template"), record.tags)
        assertEquals("retain-me", record.customData.getValue("plugin-state").value)
        assertFalse(record.autoType?.enabled ?: true)
        assertEquals("device.bin", record.attachments.single().name)
        assertSame(attachment, record.attachments.single().binary)
        assertEquals("Old device", record.history.single().fields.single { it.name == "Title" }.rawValue)
        assertTrue(record.history.single().fields.single { it.name == "Plugin Secret" }.isProtected)
    }

    @Test
    fun `duplicate legacy group paths remain separate browser nodes by UUID`() {
        val firstUuid = UUID.randomUUID()
        val secondUuid = UUID.randomUUID()
        val database = database().modifyParentGroup {
            copy(
                groups = listOf(
                    Group(uuid = firstUuid, name = "Duplicate"),
                    Group(uuid = secondUuid, name = "Duplicate")
                )
            )
        }

        val browser = KeePassNativeBrowserBuilder.build(session(database))

        assertEquals(2, browser.groupsByLegacyPath.getValue("Duplicate").size)
        assertNotNull(browser.group(KeePassNativeGroupIdentity(1L, firstUuid)))
        assertNotNull(browser.group(KeePassNativeGroupIdentity(1L, secondUuid)))
    }

    @Test
    fun `entry classifier routes recognized Monica types and keeps templates generic`() {
        val entries = listOf(
            entry("Login", "UserName" to EntryValue.Plain("alice"), "Password" to EntryValue.Plain("pw")),
            entry("OTP", "otp" to EntryValue.Plain("otpauth://totp/Test?secret=JBSWY3DPEHPK3PXP")),
            entry("Note", "MonicaItemType" to EntryValue.Plain("NOTE")),
            entry("Card", "MonicaItemType" to EntryValue.Plain("BANK_CARD")),
            entry("Document", "MonicaItemType" to EntryValue.Plain("DOCUMENT")),
            entry("Passkey", "KPEX_PASSKEY_CREDENTIAL_ID" to EntryValue.Plain("credential")),
            entry("Template", "_etm_template" to EntryValue.Plain("1"))
        )
        val database = database().modifyParentGroup { copy(entries = entries) }

        val kinds = KeePassNativeBrowserBuilder.build(session(database)).entries.map { it.kind }

        assertEquals(
            listOf(
                KeePassNativeEntryKind.PASSWORD,
                KeePassNativeEntryKind.TOTP,
                KeePassNativeEntryKind.NOTE,
                KeePassNativeEntryKind.BANK_CARD,
                KeePassNativeEntryKind.DOCUMENT,
                KeePassNativeEntryKind.PASSKEY,
                KeePassNativeEntryKind.TEMPLATE
            ),
            kinds
        )
    }

    @Test
    fun `browser skips the field reference index when entries contain no references`() {
        val database = database().modifyParentGroup {
            copy(entries = listOf(entry("Login", "UserName" to EntryValue.Plain("alice"))))
        }
        var referenceBuildCount = 0

        val browser = KeePassNativeBrowserBuilder.build(session(database)) { entries ->
            referenceBuildCount++
            takagi.ru.monica.utils.KeePassFieldReferenceResolver.buildContext(entries)
        }

        assertEquals(0, referenceBuildCount)
        assertEquals("alice", browser.entries.single().field("UserName")?.displayValue)
    }

    @Test
    fun `browser retains reference resolution when a field uses KeePass REF syntax`() {
        val targetUuid = UUID.randomUUID()
        val target = Entry(
            uuid = targetUuid,
            fields = EntryFields.of("Title" to EntryValue.Plain("Referenced title"))
        )
        val referencing = entry(
            "Shortcut",
            "UserName" to EntryValue.Plain("{REF:T@I:$targetUuid}")
        )
        val database = database().modifyParentGroup { copy(entries = listOf(target, referencing)) }
        var referenceBuildCount = 0

        val browser = KeePassNativeBrowserBuilder.build(session(database)) { entries ->
            referenceBuildCount++
            takagi.ru.monica.utils.KeePassFieldReferenceResolver.buildContext(entries)
        }

        assertEquals(1, referenceBuildCount)
        assertEquals("Referenced title", browser.entries.last().field("UserName")?.displayValue)
    }

    private fun database(): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica native browser test", name = "Native browser"),
        credentials = Credentials.from(EncryptedValue.fromString("password"))
    )

    private fun session(database: KeePassDatabase): KeePassNativeSession {
        return KeePassNativeSessionBuilder.build(
            databaseId = 1L,
            sourceRevision = KeePassSourceRevision("browser", 1),
            database = database,
            pathKeyBuilder = { parent, name -> if (parent.isNullOrBlank()) name else "$parent/$name" }
        )
    }

    private fun entry(title: String, vararg fields: Pair<String, EntryValue>): Entry {
        return Entry(
            uuid = UUID.randomUUID(),
            fields = EntryFields.of(
                "Title" to EntryValue.Plain(title),
                *fields
            )
        )
    }
}
