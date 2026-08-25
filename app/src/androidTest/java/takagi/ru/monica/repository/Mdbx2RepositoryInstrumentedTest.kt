package takagi.ru.monica.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.MdbxViewModel

@RunWith(AndroidJUnit4::class)
class Mdbx2RepositoryInstrumentedTest {
    @Test
    fun nestedFolderCrudAndReopenRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val password = "nested-folders-${System.currentTimeMillis()}"
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        try {
            databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = "MDBX2 nested folder test",
                    filePath = vaultFile.absolutePath,
                    storageLocation = MdbxStorageLocation.INTERNAL.name,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    engineType = MdbxEngineType.RUST_MDBX2.name,
                    tigaMode = MdbxTigaMode.SKY.name,
                    encryptedPassword = securityManager.encryptData(password),
                    unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                    kdfProfile = "argon2id-mdbx2",
                    workingCopyPath = vaultFile.absolutePath,
                    cacheCopyPath = vaultFile.absolutePath,
                    isOfflineAvailable = true,
                    lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
                )
            )

            val parent = repository.createFolder(databaseId, "Parent", null)
            val child = repository.createFolder(databaseId, "Child", parent.folderId)
            val grandchild = repository.createFolder(databaseId, "Grandchild", child.folderId)
            val initial = repository.listFolders(databaseId).associateBy { it.folderId }
            assertEquals(null, initial.getValue(parent.folderId).parentFolderId)
            assertEquals(parent.folderId, initial.getValue(child.folderId).parentFolderId)
            assertEquals(child.folderId, initial.getValue(grandchild.folderId).parentFolderId)
            assertEquals(
                "/${parent.folderId}/${child.folderId}/${grandchild.folderId}",
                initial.getValue(grandchild.folderId).pathKey
            )

            assertTrue(
                runCatching {
                    repository.moveFolder(databaseId, parent.folderId, grandchild.folderId)
                }.isFailure
            )
            repository.moveFolder(databaseId, grandchild.folderId, parent.folderId)
            repository.renameFolder(databaseId, child.folderId, "Renamed child")
            repository.deleteFolder(databaseId, child.folderId)
            assertTrue(repository.listFolders(databaseId).none { it.folderId == child.folderId })
            repository.restoreFolder(databaseId, child.folderId, parent.folderId)

            val reopened = MdbxRepositoryFactory.create(context, room, securityManager)
            val restored = reopened.listFolders(databaseId).associateBy { it.folderId }
            assertEquals("Renamed child", restored.getValue(child.folderId).name)
            assertEquals(parent.folderId, restored.getValue(child.folderId).parentFolderId)
            assertEquals(parent.folderId, restored.getValue(grandchild.folderId).parentFolderId)
        } finally {
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(vaultFile)
        }
    }

    @Test
    fun snapshotRestoreReconcilesRoomWithoutRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val rawRepository = Mdbx2Repository(
            context = context,
            databaseDao = databaseDao,
            securityManager = securityManager,
            passwordEntryDao = room.passwordEntryDao(),
            secureItemDao = room.secureItemDao(),
            customFieldDao = room.customFieldDao()
        )
        val password = "room-history-${System.currentTimeMillis()}"
        val vaultFile = rawRepository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        var passwordId = 0L
        var revertedCreatePasswordId = 0L
        try {
            databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = "MDBX2 Room history test",
                    filePath = vaultFile.absolutePath,
                    storageLocation = MdbxStorageLocation.INTERNAL.name,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    engineType = MdbxEngineType.RUST_MDBX2.name,
                    tigaMode = MdbxTigaMode.SKY.name,
                    encryptedPassword = securityManager.encryptData(password),
                    unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                    kdfProfile = "argon2id-mdbx2",
                    workingCopyPath = vaultFile.absolutePath,
                    cacheCopyPath = vaultFile.absolutePath,
                    isOfflineAvailable = true,
                    lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
                )
            )
            val passwordRepository = PasswordRepository(
                passwordEntryDao = room.passwordEntryDao(),
                mdbxRepository = rawRepository
            )
            passwordId = passwordRepository.insertPasswordEntry(
                PasswordEntry(
                    title = "Room before",
                    website = "https://room-history.test",
                    username = "room-before",
                    password = "room-secret",
                    mdbxDatabaseId = databaseId
                )
            )
            val before = room.passwordEntryDao().getPasswordEntryById(passwordId)!!
            val snapshot = rawRepository.createSnapshot(databaseId, "Room before update")
            passwordRepository.updatePasswordEntry(before.copy(title = "Room after"))
            assertEquals("Room after", room.passwordEntryDao().getPasswordEntryById(passwordId)?.title)

            val viewModel = MdbxViewModel(
                application = application,
                databaseDao = databaseDao,
                remoteSourceDao = room.mdbxRemoteSourceDao(),
                passwordEntryDao = room.passwordEntryDao(),
                secureItemDao = room.secureItemDao(),
                passkeyDao = room.passkeyDao(),
                attachmentDao = room.attachmentDao(),
                customFieldDao = room.customFieldDao(),
                securityManager = securityManager
            )
            viewModel.clearOperationState()
            viewModel.revertToSnapshot(databaseId, snapshot.snapshotId)
            val operation = withTimeout(30_000) {
                viewModel.operationState.first { state ->
                    state is MdbxViewModel.OperationState.Success ||
                        state is MdbxViewModel.OperationState.Error
                }
            }
            assertTrue(operation.toString(), operation is MdbxViewModel.OperationState.Success)
            assertEquals("Room before", room.passwordEntryDao().getPasswordEntryById(passwordId)?.title)
            assertEquals("room-before", room.passwordEntryDao().getPasswordEntryById(passwordId)?.username)

            revertedCreatePasswordId = passwordRepository.insertPasswordEntry(
                PasswordEntry(
                    title = "Created for commit revert",
                    website = "https://commit-revert.test",
                    username = "commit-revert",
                    password = "commit-secret",
                    mdbxDatabaseId = databaseId
                )
            )
            var createCommitId: String? = null
            for (delta in rawRepository.listDeltaHistory(databaseId)) {
                if (
                    rawRepository.listCommitDiff(databaseId, delta.commitId)
                        .any { it.currentTitle == "Created for commit revert" }
                ) {
                    createCommitId = delta.commitId
                    break
                }
            }
            assertNotNull(createCommitId)
            viewModel.clearOperationState()
            viewModel.revertCommit(databaseId, requireNotNull(createCommitId))
            val revertOperation = withTimeout(30_000) {
                viewModel.operationState.first { state ->
                    state is MdbxViewModel.OperationState.Success ||
                        state is MdbxViewModel.OperationState.Error
                }
            }
            assertTrue(
                revertOperation.toString(),
                revertOperation is MdbxViewModel.OperationState.Success
            )
            assertTrue(
                room.passwordEntryDao().getPasswordEntryById(revertedCreatePasswordId)?.isDeleted == true
            )
        } finally {
            if (revertedCreatePasswordId > 0L) {
                room.passwordEntryDao().deletePasswordEntryById(revertedCreatePasswordId)
            }
            if (passwordId > 0L) room.passwordEntryDao().deletePasswordEntryById(passwordId)
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            rawRepository.deleteOwnedVaultFile(vaultFile)
        }
    }

    @Test
    fun historyDiffSnapshotRestoreAndDeleteRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(
            context = context,
            databaseDao = databaseDao,
            securityManager = securityManager,
            passwordEntryDao = room.passwordEntryDao(),
            secureItemDao = room.secureItemDao(),
            customFieldDao = room.customFieldDao()
        )
        val password = "history-${System.currentTimeMillis()}"
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        try {
            databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = "MDBX2 history test",
                    filePath = vaultFile.absolutePath,
                    storageLocation = MdbxStorageLocation.INTERNAL.name,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    engineType = MdbxEngineType.RUST_MDBX2.name,
                    tigaMode = MdbxTigaMode.SKY.name,
                    encryptedPassword = securityManager.encryptData(password),
                    unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                    kdfProfile = "argon2id-mdbx2",
                    workingCopyPath = vaultFile.absolutePath,
                    cacheCopyPath = vaultFile.absolutePath,
                    isOfflineAvailable = true,
                    lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
                )
            )
            val folder = repository.createFolder(databaseId, "History", null)
            val before = PasswordEntry(
                id = 701L,
                title = "Before",
                website = "https://history.test",
                username = "before-user",
                password = "before-password",
                mdbxDatabaseId = databaseId,
                mdbxFolderId = folder.folderId
            )
            repository.upsertPassword(before)
            assertTrue(repository.listDeltaHistory(databaseId).isNotEmpty())

            val snapshot = repository.createSnapshot(
                databaseId = databaseId,
                name = "Before update",
                fullSnapshot = false
            )
            assertEquals("Before update", snapshot.name)
            assertEquals("manual", snapshot.snapshotType)
            assertTrue(snapshot.isFull)
            assertTrue(snapshot.integrityOk)

            repository.upsertPassword(before.copy(title = "After", username = "after-user"))
            val history = repository.listDeltaHistory(databaseId)
            assertTrue(history.size >= 2)
            var diff: MdbxCommitDiff? = null
            for (delta in history) {
                diff = repository.listCommitDiff(databaseId, delta.commitId)
                    .firstOrNull { it.currentTitle == "After" }
                if (diff != null) break
            }
            assertNotNull(diff)
            assertEquals("Before", diff?.previousTitle)

            val preview = repository.getSnapshotStructurePreview(databaseId, snapshot.snapshotId)
            assertEquals(snapshot.snapshotId, preview.snapshotId)
            assertTrue(preview.snapshotNodes.any { it.name == "Before" })
            assertTrue(preview.currentNodes.any { it.name == "After" })

            assertEquals(
                "After",
                repository.readStoredEntries(databaseId)
                    .single { it.entryId == "password:701" && !it.deleted }
                    .title
            )
            repository.revertToSnapshot(databaseId, snapshot.snapshotId)
            val restored = repository.readStoredEntries(databaseId)
                .single { it.entryId == "password:701" && !it.deleted }
            assertEquals("Before", restored.title)
            assertEquals("before-user", JSONObject(restored.payloadJson).getString("username"))

            repository.deleteSnapshot(databaseId, snapshot.snapshotId)
            assertTrue(repository.listSnapshots(databaseId).none { it.snapshotId == snapshot.snapshotId })
        } finally {
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(vaultFile)
        }
    }

    @Test
    fun passwordAttachmentAndReopenRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val password = "mdbx2-repository-password"
        val bootstrapRepository = Mdbx2Repository(
            context = context,
            databaseDao = databaseDao,
            securityManager = securityManager,
            passwordEntryDao = room.passwordEntryDao(),
            secureItemDao = room.secureItemDao(),
            customFieldDao = room.customFieldDao()
        )
        val vaultFile = bootstrapRepository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        var encryptedAttachmentPath: String? = null
        try {
            databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = "MDBX2 repository test",
                    filePath = vaultFile.absolutePath,
                    storageLocation = MdbxStorageLocation.INTERNAL.name,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    engineType = MdbxEngineType.RUST_MDBX2.name,
                    tigaMode = MdbxTigaMode.SKY.name,
                    encryptedPassword = securityManager.encryptData(password),
                    unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                    kdfProfile = "argon2id-mdbx2",
                    workingCopyPath = vaultFile.absolutePath,
                    cacheCopyPath = vaultFile.absolutePath,
                    isOfflineAvailable = true,
                    lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
                )
            )
            val repository = MdbxRepositoryFactory.create(context, room, securityManager)
            val folder = repository.createFolder(databaseId, "Personal", null)
            val entry = PasswordEntry(
                id = 42L,
                title = "Initial",
                website = "https://example.com",
                username = "alice",
                password = "secret",
                mdbxDatabaseId = databaseId,
                mdbxFolderId = folder.folderId
            )

            repository.upsertPassword(entry)
            val created = repository.readStoredEntries(databaseId).single { !it.deleted }
            assertEquals("password:42", created.entryId)
            assertEquals("Initial", created.title)
            assertEquals("alice", JSONObject(created.payloadJson).getString("username"))

            repository.upsertPassword(entry.copy(title = "Updated", username = "bob"))
            val updated = repository.readStoredEntries(databaseId).single { !it.deleted }
            assertEquals("Updated", updated.title)
            assertEquals("bob", JSONObject(updated.payloadJson).getString("username"))

            val authenticator = SecureItem(
                id = 51L,
                itemType = ItemType.TOTP,
                title = "GitHub OTP",
                itemData = """{"secret":"JBSWY3DPEHPK3PXP","issuer":"GitHub","accountName":"alice","digits":6,"period":30,"algorithm":"SHA1","otpType":"TOTP"}""",
                mdbxDatabaseId = databaseId,
                mdbxFolderId = folder.folderId
            )
            val passkey = PasskeyEntry(
                id = 61L,
                credentialId = "credential-test-61",
                rpId = "example.com",
                rpName = "Example",
                userId = "dXNlci02MQ",
                userName = "alice@example.com",
                userDisplayName = "Alice",
                publicKey = "test-public-key",
                privateKeyAlias = "missing-test-private-key",
                mdbxDatabaseId = databaseId,
                mdbxFolderId = folder.folderId
            )
            repository.upsertSecureItem(authenticator)
            repository.upsertPasskey(passkey)
            val steamEntryId = repository.upsertSteamMaFileEntry(
                databaseId = databaseId,
                entryId = null,
                title = "Steam test account",
                maFileJson = """{"steamid":"76561199000000001","account_name":"mdbx2_test"}"""
            )
            val activeEntries = repository.readStoredEntries(databaseId).filterNot { it.deleted }
            assertEquals(
                setOf("login", "totp", "passkey", "steam-mafile"),
                activeEntries.map { it.entryType }.toSet()
            )
            assertEquals(
                "GitHub OTP",
                activeEntries.single { it.entryType == "totp" }.title
            )
            assertEquals(
                "credential-test-61",
                JSONObject(activeEntries.single { it.entryType == "passkey" }.payloadJson)
                    .getString("credential_id")
            )
            assertEquals("steam-mafile:76561199000000001", steamEntryId)
            assertEquals(
                "mdbx2_test",
                JSONObject(repository.listSteamMaFileEntries(databaseId).single().payloadJson)
                    .getString("account_name")
            )

            val attachmentStorage = AttachmentStorage(context)
            val encrypted = attachmentStorage.writeEncrypted("mdbx2 attachment".byteInputStream())
            encryptedAttachmentPath = encrypted.relativePath
            val wrappedCek = try {
                AttachmentKeyVault(securityManager).wrap(encrypted.cek)
            } finally {
                encrypted.cek.fill(0)
            }
            val attachment = Attachment(
                parentPasswordId = entry.id,
                source = AttachmentSource.LOCAL.name,
                fileName = "sample.txt",
                mimeType = "text/plain",
                sizeBytes = encrypted.sizeBytes,
                sha256Hex = encrypted.sha256Hex,
                wrappedCek = wrappedCek,
                localPath = encrypted.relativePath,
                downloadState = AttachmentDownloadState.DOWNLOADED.name,
                createdAt = Date().time,
                updatedAt = Date().time
            )
            repository.upsertAttachment(databaseId, "password:42", attachment)
            val storedAttachment = repository.readStoredAttachments(databaseId).single()
            assertEquals("password:42", storedAttachment.entryId)
            assertEquals("sample.txt", storedAttachment.fileName)
            assertTrue(storedAttachment.blob.isNotEmpty())
            assertFalse(storedAttachment.wrappedCek.isNullOrBlank())

            repository.deleteAttachment(databaseId, "password:42", attachment)
            assertTrue(repository.readStoredAttachments(databaseId).isEmpty())

            repository.deletePassword(entry)
            repository.deleteSecureItem(authenticator)
            repository.deletePasskey(passkey)
            repository.deleteSteamMaFileEntry(databaseId, steamEntryId)
            val reopenedRepository = MdbxRepositoryFactory.create(context, room, securityManager)
            val deleted = reopenedRepository.readStoredEntries(databaseId)
            assertEquals(4, deleted.size)
            assertEquals(
                setOf(
                    "password:42",
                    "totp:51",
                    "passkey:credential-test-61",
                    "steam-mafile:76561199000000001"
                ),
                deleted.map { it.entryId }.toSet()
            )
            assertTrue(deleted.all { it.deleted })
        } finally {
            encryptedAttachmentPath?.let { AttachmentStorage(context).delete(it) }
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            vaultFile.delete()
        }
    }
}
