package takagi.ru.monica.repository

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.transfer.DatabaseExportSnapshotLoader
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.MdbxViewModel
import uniffi.mdbx_ffi.MdbxWriteCommand
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MdbxKeyInteropTest {
    @Test fun nativeKeysImportEditReopenAndExportWithoutLoss() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val dao = room.localMdbxDatabaseDao()
        val security = SecurityManager(context)
        val repo = Mdbx2Repository(context, dao, security, passwordEntryDao = room.passwordEntryDao(), secureItemDao = room.secureItemDao(), customFieldDao = room.customFieldDao())
        val file = repo.createInitializedVaultFile(MdbxTigaMode.SKY, "Synthetic interop vault 123")
        var id = 0L
        var vm: MdbxViewModel? = null
        try {
            id = dao.insertDatabase(LocalMdbxDatabase(name = "Key interop fixture", filePath = file.absolutePath,
                engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = security.encryptData("Synthetic interop vault 123"),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue))
            val folder = repo.createFolder(id, "CLI fixtures", null)
            val ssh = SshKeyGenerator.generate(SshKeyGenerator.Request.Ed25519, "interop")
            val sshJson = JSONObject(SshKeyDataCodec.encode(ssh)).put("future", JSONObject().put("retained", true)).toString()
            val gpg = GpgKeyGenerator.generate("Interop", "interop@example.test", passphrase = "test passphrase".toCharArray())
            val fields = JSONArray()
            GpgEntryFields.encode(gpg).forEachIndexed { i, f -> fields.put(JSONObject().put("title", f.title).put("value", f.value).put("is_protected", false).put("sort_order", i)) }
            val sshId = "password:${UUID.randomUUID()}"
            val gpgId = "password:${UUID.randomUUID()}"
            repo.withVaultForSync(id) { _, vault ->
                fun command(logical: String, type: String, secret: String, keyData: String, custom: JSONArray) = MdbxWriteCommand.CreateEntry(
                    mdbx2PhysicalEntryId(vault.info().vaultId, logical), folder.folderId, "login", type,
                    JSONObject().put("kind", "password").put("room_id", 0).put("monica_entry_id", logical)
                        .put("mdbx_folder_id", folder.folderId).put("login_type", type).put("password_plain", secret)
                        .put("ssh_key_data", keyData).put("custom_fields", custom).toString())
                vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic CLI contract", listOf(
                    command(sshId, "SSH_KEY", "", sshJson, JSONArray()), command(gpgId, "GPG_KEY", gpg.privateKey, "", fields)))
            }
            val source = ImportDestination(ImportDestinationKind.MDBX, id)
            val before = DatabaseExportSnapshotLoader(context).load(source, BackupPreferences())
            assertEquals(sshJson, before.passwords.single { it.loginType == "SSH_KEY" }.sshKeyData)
            val beforeGpg = before.passwords.single { it.loginType == "GPG_KEY" }
            assertEquals(gpg.publicKey, GpgEntryFields.publicKey(before.fields.getValue(beforeGpg.id).associate { it.title to it.value }))
            val snapshot = repo.createSnapshot(id, "Native fixture")
            val viewModel = MdbxViewModel(context.applicationContext as Application, dao, room.mdbxRemoteSourceDao(),
                room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(), room.attachmentDao(), room.customFieldDao(), security)
            vm = viewModel
            viewModel.revertToSnapshot(id, snapshot.snapshotId)
            val result = withTimeout(60_000) { viewModel.operationState.first { it is MdbxViewModel.OperationState.Success || it is MdbxViewModel.OperationState.Error } }
            assertTrue("Native fixture import failed", result is MdbxViewModel.OperationState.Success)
            val imported = room.passwordEntryDao().getByMdbxDatabaseIdSync(id)
            assertEquals(2, imported.size)
            val sshEntry = imported.single { it.loginType == "SSH_KEY" }
            assertEquals(sshJson, sshEntry.sshKeyData)
            val gpgEntry = imported.single { it.loginType == "GPG_KEY" }
            assertTrue("GPG secret survives Room import", gpg.privateKey == security.decryptData(gpgEntry.password))
            val passwords = PasswordRepository(room.passwordEntryDao(), mdbxRepository = repo)
            passwords.updatePasswordEntry(sshEntry.copy(sshKeyData = SshKeyDataCodec.encode(checkNotNull(SshKeyDataCodec.decode(sshEntry.sshKeyData)).copy(comment = "Android edit"))))
            passwords.updatePasswordEntry(gpgEntry.copy(title = "Android GPG edit"))
            val reopened = Mdbx2Repository(context, dao, security).readStoredEntries(id).filter { it.entryType == "login" }
            assertEquals(2, reopened.size)
            val sshPayload = JSONObject(reopened.single { it.entryId == sshId }.payloadJson)
            val key = checkNotNull(SshKeyDataCodec.decode(sshPayload.getString("ssh_key_data")))
            assertTrue("SSH secret survives edit", ssh.privateKeyOpenSsh == key.privateKeyOpenSsh)
            assertEquals(ssh.publicKeyOpenSsh, key.publicKeyOpenSsh)
            assertEquals(ssh.fingerprintSha256, key.fingerprintSha256)
            assertTrue(key.additionalFields.containsKey("future"))
            val gpgPayload = JSONObject(reopened.single { it.entryId == gpgId }.payloadJson)
            assertTrue("GPG secret survives save", gpg.privateKey == gpgPayload.getString("password_plain"))
            val exportedFields = gpgPayload.getJSONArray("custom_fields")
            val fieldMap = (0 until exportedFields.length()).associate { val f = exportedFields.getJSONObject(it); f.getString("title") to f.getString("value") }
            assertEquals(gpg.publicKey, GpgEntryFields.publicKey(fieldMap))
            val after = DatabaseExportSnapshotLoader(context).load(source, BackupPreferences())
            assertEquals(key, SshKeyDataCodec.decode(after.passwords.single { it.loginType == "SSH_KEY" }.sshKeyData))
            val afterGpg = after.passwords.single { it.loginType == "GPG_KEY" }
            assertEquals(gpg.publicKey, GpgEntryFields.publicKey(after.fields.getValue(afterGpg.id).associate { it.title to it.value }))
        } finally {
            vm?.viewModelScope?.cancel()
            if (id > 0) {
                room.passwordEntryDao().getByMdbxDatabaseIdSync(id).forEach { room.passwordEntryDao().deletePasswordEntryById(it.id) }
                dao.deleteDatabaseById(id)
            }
            repo.deleteOwnedVaultFile(file)
        }
    }
}
