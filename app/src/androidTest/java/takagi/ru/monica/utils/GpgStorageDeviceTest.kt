package takagi.ru.monica.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Meta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.GpgEntryFields
import takagi.ru.monica.security.SecurityManager
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GpgStorageDeviceTest {
    @Test fun keySurvivesRealKeePassWriteCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localKeePassDatabaseDao()
        val security = SecurityManager(context)
        val file = File(context.filesDir, "gpg-test-${UUID.randomUUID()}.kdbx")
        val credentials = Credentials.from(EncryptedValue.fromString("synthetic-gpg-test"))
        val base = KeePassDatabase.Ver4x.create(rootName = "GPG test", meta = Meta(generator = "GPG test", name = "GPG test"), credentials = credentials)
        val seed = when (val kdf = base.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val database = base.copy(header = base.header.copy(kdfParameters = KdfParameters.Aes(rounds = 100U, seed = seed)))
        file.outputStream().use { database.encode(it) }
        var id = 0L
        try {
            id = dao.insertDatabase(LocalKeePassDatabase(name = "GPG test", filePath = file.name,
                encryptedPassword = security.encryptData("synthetic-gpg-test")))
            val key = GpgKeyGenerator.generate("KeePass fixture", "fixture@example.org")
            val service = KeePassKdbxService(context, dao, security)
            val fields = GpgEntryFields.encode(key).map { KeePassCustomFieldData(it.title, it.value, false) }
            val password = PasswordEntry(id = Long.MAX_VALUE - 4096, title = "GPG test", website = "", username = key.userId,
                password = security.encryptData(key.privateKey), loginType = GpgEntryFields.TYPE, keepassDatabaseId = id)
            service.addPasswordEntry(id, password, resolvePassword = { key.privateKey },
                customFields = fields + KeePassCustomFieldData(GpgEntryFields.PUBLIC_PREFIX + "0009", "stale certificate chunk", false) +
                    KeePassCustomFieldData("Unrelated note", "preserve me", false)).getOrThrow()
            KeePassKdbxService.invalidateProcessCache(id)
            val first = KeePassKdbxService(context, dao, security).loadWorkspace(id).getOrThrow().passwords.single()
            service.addOrUpdatePasswordEntries(id, listOf(password.copy(id = requireNotNull(first.monicaLocalId), keepassEntryUuid = first.entryUuid, keepassGroupUuid = first.groupUuid)), resolvePassword = { key.privateKey },
                customFieldsByEntryId = mapOf(requireNotNull(first.monicaLocalId) to fields)).getOrThrow()
            KeePassKdbxService.invalidateProcessCache(id)
            val reopened = KeePassKdbxService(context, dao, security).loadWorkspace(id).getOrThrow().passwords.single()
            assertFalse(reopened.customFields.any { it.title == GpgEntryFields.PUBLIC_PREFIX + "0009" })
            assertTrue(reopened.customFields.any { it.title == "Unrelated note" && it.value == "preserve me" })
            assertEquals(GpgEntryFields.TYPE, reopened.loginType)
            assertEquals(key.privateKey, reopened.password)
            assertEquals(key.publicKey, GpgEntryFields.publicKey(reopened.customFields.associate { it.title to it.value }))
            val native = file.inputStream().use { KeePassDatabase.decode(it, credentials) }.content.group.entries.single()
            assertTrue(native.fields.getValue("Password") is app.keemobile.kotpass.models.EntryValue.Encrypted)
            assertFalse(file.readText(Charsets.ISO_8859_1).contains("PRIVATE KEY"))
        } finally {
            if (id != 0L) { KeePassKdbxService.invalidateProcessCache(id); dao.deleteDatabaseById(id) }
            file.delete()
        }
    }
}
