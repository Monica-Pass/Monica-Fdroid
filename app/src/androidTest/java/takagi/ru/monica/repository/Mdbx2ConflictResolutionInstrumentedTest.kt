package takagi.ru.monica.repository

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.MdbxViewModel
import uniffi.mdbx_ffi.openVault

class Mdbx2ConflictResolutionInstrumentedTest {
    @Test fun choosingIncomingDeletionDoesNotRescueRoomCacheAndLocalChoicePreservesContent() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, room.localMdbxDatabaseDao(), security)
        val password = "synthetic-conflict-fixture"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val copy = File(file.parentFile, "conflict-${UUID.randomUUID()}.mdbx")
        val segment = File(context.cacheDir, "conflict-${UUID.randomUUID()}.mdbxsync")
        val db = LocalMdbxDatabase(name = "Conflict fixture", filePath = file.absolutePath, workingCopyPath = file.absolutePath,
            engineType = MdbxEngineType.RUST_MDBX2.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            storageLocation = MdbxStorageLocation.INTERNAL.name, encryptedPassword = security.encryptData(password))
        val id = room.localMdbxDatabaseDao().insertDatabase(db)
        val entries = (1L..2L).map { PasswordEntry(id = 8_600_000_000L + it, title = "Conflict item $it",
            username = "base", password = "", website = "", mdbxDatabaseId = id) }
        val vm = MdbxViewModel(context.applicationContext as Application, room.localMdbxDatabaseDao(),
            room.mdbxRemoteSourceDao(), room.passwordEntryDao(), room.secureItemDao(), room.passkeyDao(),
            room.attachmentDao(), room.customFieldDao(), security)
        try {
            repository.upsertPasswords(entries)
            val base = repository.withVaultForSync(id) { _, vault -> vault.createIncrementalSyncBootstrap(copy.absolutePath).checkpoint }
            repository.upsertPasswords(entries.map { it.copy(username = "local-edited") })
            val remote = openVault(copy.absolutePath, password, "incoming-device")
            try {
                remote.listCollectionSummaries(100u, null).items.forEach { project ->
                    remote.listEntries(project.collectionId, null).forEach { remote.deleteEntry(project.collectionId, it.entryId) }
                }
                remote.exportIncrementalSyncSegment(segment.absolutePath, base, null, 128u)
            } finally { remote.close() }
            repository.withVaultForSync(id) { _, vault -> vault.applyIncrementalSyncSegment(segment.absolutePath, base, null) }
            val conflicts = repository.listConflicts(id)
            assertEquals(2, conflicts.size)
            vm.preloadActiveMdbxDatabase(id)
            withTimeout(30_000) { while (room.passwordEntryDao().getByMdbxDatabaseIdSync(id).size != 2) delay(100) }
            vm.showConflicts(db.copy(id = id))
            withTimeout(30_000) {
                while ((vm.conflictDialogState.value as? MdbxViewModel.MdbxConflictDialogState.Visible)?.let { !it.isLoading && it.conflicts.size == 2 } != true) delay(100)
            }
            withContext(Dispatchers.Main) {
                vm.resolveConflict(id, conflicts[0].conflictId, MdbxConflictResolution.INCOMING_WINS)
                vm.resolveConflict(id, conflicts[0].conflictId, MdbxConflictResolution.LOCAL_WINS)
            }
            withTimeout(30_000) { while ((vm.conflictDialogState.value as? MdbxViewModel.MdbxConflictDialogState.Visible)?.let { !it.isLoading && it.conflicts.size == 1 } != true) delay(100) }
            withContext(Dispatchers.Main) { vm.resolveConflict(id, conflicts[1].conflictId, MdbxConflictResolution.LOCAL_WINS) }
            withTimeout(30_000) { while ((vm.conflictDialogState.value as? MdbxViewModel.MdbxConflictDialogState.Visible)?.let { !it.isLoading && it.conflicts.isEmpty() } != true) delay(100) }
            val native = repository.withVaultForSync(id) { _, vault ->
                vault.listCollectionSummaries(100u, null).items.flatMap { vault.listEntries(it.collectionId, null) }
            }
            assertEquals(1, native.count { !it.deleted })
            assertTrue(native.single { !it.deleted }.payloadJson.contains("local-edited"))
            assertEquals(1, room.passwordEntryDao().getByMdbxDatabaseIdSync(id).count { !it.isDeleted })
            assertTrue(repository.listConflicts(id).isEmpty())
        } finally {
            vm.viewModelScope.cancel()
            room.passwordEntryDao().getByMdbxDatabaseIdSync(id).forEach { room.passwordEntryDao().deletePasswordEntryById(it.id) }
            room.localMdbxDatabaseDao().deleteDatabaseById(id)
            repository.deleteOwnedVaultFile(copy)
            repository.deleteOwnedVaultFile(file)
            segment.delete()
        }
    }
}
