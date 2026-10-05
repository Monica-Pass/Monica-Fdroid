package takagi.ru.monica.utils

import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class KeePassCloudRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun webDavKdbxRoundTripRejectsStaleWriter() = runBlocking {
        val endpoint = requireNotNull(InstrumentationRegistry.getArguments().getString("mdbxWebDavUrl"))
        val path = "kdbx-roundtrip-${UUID.randomUUID()}.kdbx"
        val source = WebDavKeePassFileSource(endpoint, "synthetic-fixture", "synthetic-fixture", path,
            AppLocaleStringResolver(context))
        try { exercise(source) } finally {
            com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine().delete("$endpoint/$path")
        }
    }

    private suspend fun exercise(source: KeePassFileSource) {
        val credentials = Credentials.from(EncryptedValue.fromString("synthetic-kdbx-password"))
        val created = KeePassDatabase.Ver3x.create("Root", Meta(name = "Cloud fixture"), credentials)
        val base = created.copy(header = created.header.copy(transformRounds = 100U))
        val entryId = UUID.randomUUID()
        fun payload(value: String): ByteArray {
            val entry = Entry(uuid = entryId, fields = EntryFields.of(
                "Title" to EntryValue.Plain("Cloud fixture"),
                "UserName" to EntryValue.Plain("synthetic-user"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString(value)),
                "otp" to EntryValue.Encrypted(EncryptedValue.fromString("otpauth://totp/fixture?secret=JBSWY3DPEHPK3PXP")),
                "Notes" to EntryValue.Plain("Unicode 备注 · synthetic")
            ))
            val db = base.modifyParentGroup { copy(entries = listOf(entry)) }
            return ByteArrayOutputStream().also { db.encode(it) }.toByteArray()
        }
        val original = payload("original")
        source.write(original, null)
        assertArrayEquals(original, source.read())
        val oldVersion = requireNotNull(source.stat().versionToken)
        val edited = payload("edited")
        source.write(edited, oldVersion)
        assertTrue("Stale writer must be rejected", runCatching { source.write(payload("stale"), oldVersion) }.isFailure)
        val downloaded = source.read()
        assertArrayEquals(edited, downloaded)
        val entry = KeePassDatabase.decode(downloaded.inputStream(), credentials).content.group.entries.single()
        assertEquals(entryId, entry.uuid)
        assertEquals("edited", entry.fields.getValue("Password").content)
        assertEquals("synthetic-user", entry.fields.getValue("UserName").content)
        assertEquals("Unicode 备注 · synthetic", entry.fields.getValue("Notes").content)
        assertEquals("otpauth://totp/fixture?secret=JBSWY3DPEHPK3PXP", entry.fields.getValue("otp").content)
    }
}
