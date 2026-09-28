package takagi.ru.monica.repository

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.MdbxViewModel
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.createPortableBackup
import uniffi.mdbx_ffi.openVault

@RunWith(AndroidJUnit4::class)
class Mdbx2UnknownTypeInstrumentedTest {
    @Test fun nativeFutureTypeImportsViewsRejectsLossyEditsAndFollowsDeletion() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
        val password = "Synthetic future-type fixture!"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val backup = File(file.parentFile, "unknown-backup-${UUID.randomUUID()}.mdbx")
        val id = room.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
            name = "Future type fixture", filePath = file.absolutePath, workingCopyPath = file.absolutePath,
            engineType = MdbxEngineType.RUST_MDBX2.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            storageLocation = MdbxStorageLocation.INTERNAL.name, encryptedPassword = security.encryptData(password)))
        val objectId = UUID.randomUUID().toString()
        val folder = UUID.randomUUID().toString()
        val type = "com.example.recovery-kit.v9"
        val payload = """{"account":"synthetic-user","token":"synthetic-secret","recovery_codes":["one","二",null],"metadata":{"counter":9007199254740993,"empty":"","enabled":false}}"""
        val models = mutableListOf<MdbxViewModel>()
        fun preload() {
            val vm = MdbxViewModel(context.applicationContext as Application, room.localMdbxDatabaseDao(),
                room.mdbxRemoteSourceDao(), room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(),
                room.attachmentDao(), room.customFieldDao(), security)
            models += vm
            vm.preloadActiveMdbxDatabase(id)
        }
        try {
            repository.withVaultForSync(id) { _, vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "future-client-create", listOf(
                    MdbxWriteCommand.CreateProject(folder, "Future category"),
                    MdbxWriteCommand.CreateEntry(objectId, folder, type, "CLI future item", payload)))
            }
            preload()
            val projected = withTimeout(30_000) {
                var found: PasswordEntry? = null
                while (found == null) {
                    found = room.passwordEntryDao().getByMdbxDatabaseIdSync(id).firstOrNull { it.replicaGroupId == objectId }
                    if (found == null) delay(100)
                }
                found
            }
            assertTrue(MdbxUnknownEntry.isProjection(projected))
            assertEquals(folder, projected.mdbxFolderId)
            assertTrue(projected.password.isEmpty() && projected.notes.isEmpty() && projected.username.isEmpty())
            assertTrue(room.customFieldDao().getFieldsByEntryIdSync(projected.id).isEmpty())
            val original = repository.readUnknownEntry(id, objectId)
            assertEquals(type, original.entryType)
            // The native writer canonicalizes object key order; compare every
            // JSON value without converting large numeric literals to doubles.
            assertEquals(Json.parseToJsonElement(payload), Json.parseToJsonElement(original.payloadJson))
            val passwords = PasswordRepository(room.passwordEntryDao(), mdbxRepository = repository)
            assertTrue(runCatching { passwords.updatePasswordEntry(projected.copy(title = "Lossy edit")) }.isFailure)
            assertTrue(runCatching { passwords.updatePasswordEntry(projected.copy(loginType = "PASSWORD")) }.isFailure)
            assertTrue(runCatching { passwords.deletePasswordEntry(projected.copy(loginType = "PASSWORD")) }.isFailure)
            assertTrue(runCatching { passwords.updateMdbxDatabaseForPasswords(listOf(projected.id), null) }.isFailure)
            assertTrue(runCatching { repository.upsertPassword(projected.copy(loginType = "PASSWORD")) }.isFailure)
            assertEquals(original, repository.readUnknownEntry(id, objectId))
            assertEquals(projected, room.passwordEntryDao().getPasswordEntryById(projected.id))
            createPortableBackup(file.absolutePath, backup.absolutePath)
            val independent = openVault(backup.absolutePath, password, "independent-reader")
            try {
                val readback = independent.listEntries(folder, null).single()
                assertEquals(objectId, readback.entryId)
                assertEquals(type, readback.entryType)
                assertEquals(Json.parseToJsonElement(payload), Json.parseToJsonElement(readback.payloadJson))
            } finally { independent.close() }
            repository.withVaultForSync(id) { _, vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "future-client-rename", listOf(
                    MdbxWriteCommand.UpdateEntry(objectId, folder, type, "Renamed by future client", payload)))
            }
            preload()
            withTimeout(30_000) {
                while (room.passwordEntryDao().getPasswordEntryById(projected.id)?.title != "Renamed by future client") delay(100)
            }
            assertEquals(objectId, room.passwordEntryDao().getByMdbxDatabaseIdSync(id).single().replicaGroupId)
            repository.withVaultForSync(id) { _, vault ->
                vault.executeWriteOperation(UUID.randomUUID().toString(), "future-client-delete",
                    listOf(MdbxWriteCommand.DeleteEntry(objectId, folder)))
            }
            preload()
            withTimeout(30_000) {
                while (room.passwordEntryDao().getPasswordEntryById(projected.id) != null) delay(100)
            }
            assertTrue(repository.readStoredEntries(id).single { it.entryId == objectId }.deleted)
        } finally {
            models.forEach { it.viewModelScope.cancel() }
            room.passwordEntryDao().getByMdbxDatabaseIdSync(id).forEach { room.passwordEntryDao().deletePasswordEntryById(it.id) }
            room.localMdbxDatabaseDao().deleteDatabaseById(id)
            repository.deleteOwnedVaultFile(backup)
            repository.deleteOwnedVaultFile(file)
        }
    }
}
