package takagi.ru.monica.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SecurityManager

@RunWith(AndroidJUnit4::class)
class Mdbx2BoundaryInstrumentedTest {
    @Test
    fun batchCrudSurvivesRepositoryReopen() = runBlocking {
        withTestVault { room, securityManager, databaseId, repository, _ ->
            val passwords = (1L..80L).map { index ->
                PasswordEntry(
                    id = 10_000L + index,
                    title = "Batch password $index",
                    website = "https://batch-$index.test",
                    username = "user-$index",
                    password = "secret-$index",
                    mdbxDatabaseId = databaseId
                )
            }
            val secureItems = (1L..20L).map { index ->
                SecureItem(
                    id = 20_000L + index,
                    itemType = ItemType.NOTE,
                    title = "Batch note $index",
                    itemData = "note-$index",
                    mdbxDatabaseId = databaseId
                )
            }
            val passkeys = (1L..20L).map { index ->
                PasskeyEntry(
                    id = 30_000L + index,
                    credentialId = "batch-credential-$index",
                    rpId = "batch-$index.test",
                    rpName = "Batch $index",
                    userId = "dXNlci0$index",
                    userName = "user-$index@batch.test",
                    userDisplayName = "Batch User $index",
                    publicKey = "public-key-$index",
                    privateKeyAlias = "private-key-$index",
                    mdbxDatabaseId = databaseId
                )
            }

            repository.upsertPasswords(passwords)
            repository.upsertSecureItems(secureItems)
            repository.upsertPasskeys(passkeys)
            assertEquals(120, repository.readStoredEntries(databaseId).count { !it.deleted })

            val updatedPasswords = passwords.take(40).map { it.copy(title = "Updated ${it.id}") }
            repository.upsertPasswords(updatedPasswords)
            repository.deletePasswords(passwords.takeLast(20))

            val reopened = MdbxRepositoryFactory.create(
                InstrumentationRegistry.getInstrumentation().targetContext,
                room,
                securityManager
            )
            val reopenedEntries = reopened.readStoredEntries(databaseId)
            assertEquals(100, reopenedEntries.count { !it.deleted })
            assertEquals(20, reopenedEntries.count { it.deleted })
            assertEquals(
                "Updated 10001",
                reopenedEntries.single { it.entryId == "password:10001" }.title
            )
            assertEquals(
                "user-1",
                JSONObject(reopenedEntries.single { it.entryId == "password:10001" }.payloadJson)
                    .getString("username")
            )
        }
    }

    @Test
    fun attachmentLimitAcceptsExactly64MiBAndRejectsOneByteMore() = runBlocking {
        withTestVault { room, securityManager, databaseId, repository, vaultFile ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val parent = PasswordEntry(
                id = 90_001L,
                title = "Attachment boundary parent",
                website = "https://attachment-boundary.test",
                username = "boundary",
                password = "test-only",
                mdbxDatabaseId = databaseId
            )
            repository.upsertPassword(parent)

            val storage = AttachmentStorage(context)
            val encrypted = storage.writeEncrypted(ZeroInputStream(MAX_ATTACHMENT_BYTES))
            val wrappedCek = try {
                AttachmentKeyVault(securityManager).wrap(encrypted.cek)
            } finally {
                encrypted.cek.fill(0)
            }
            val attachment = Attachment(
                parentPasswordId = parent.id,
                source = AttachmentSource.LOCAL.name,
                fileName = "exact-64-mib.bin",
                mimeType = "application/octet-stream",
                sizeBytes = MAX_ATTACHMENT_BYTES,
                sha256Hex = encrypted.sha256Hex,
                wrappedCek = wrappedCek,
                localPath = encrypted.relativePath,
                downloadState = AttachmentDownloadState.DOWNLOADED.name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            try {
                repository.upsertAttachment(databaseId, "password:${parent.id}", attachment)
                assertEquals(1, repository.getVaultDiagnostics(databaseId).attachmentCount)
                assertTrue(File("${vaultFile.absolutePath}.blobs").isDirectory)

                val reopened = MdbxRepositoryFactory.create(context, room, securityManager)
                assertEquals(1, reopened.getVaultDiagnostics(databaseId).attachmentCount)

                val oversizedFailure = runCatching {
                    reopened.upsertAttachment(
                        databaseId,
                        "password:${parent.id}",
                        attachment.copy(
                            fileName = "too-large.bin",
                            sizeBytes = MAX_ATTACHMENT_BYTES + 1L
                        )
                    )
                }.exceptionOrNull()
                assertTrue(oversizedFailure is IllegalArgumentException)
                assertEquals(1, reopened.getVaultDiagnostics(databaseId).attachmentCount)

                reopened.deleteAttachment(databaseId, "password:${parent.id}", attachment)
                assertEquals(0, reopened.getVaultDiagnostics(databaseId).attachmentCount)
            } finally {
                storage.delete(encrypted.relativePath)
            }
        }
    }

    private suspend fun withTestVault(
        block: suspend (
            PasswordDatabase,
            SecurityManager,
            Long,
            MdbxRepository,
            File
        ) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val rawRepository = Mdbx2Repository(context, databaseDao, securityManager)
        val password = "boundary-${UUID.randomUUID()}"
        val vaultFile = rawRepository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val databaseId = databaseDao.insertDatabase(
            LocalMdbxDatabase(
                name = "MDBX2 boundary ${UUID.randomUUID()}",
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
        try {
            block(
                room,
                securityManager,
                databaseId,
                MdbxRepositoryFactory.create(context, room, securityManager),
                vaultFile
            )
        } finally {
            room.passwordEntryDao().deleteAllByMdbxDatabaseId(databaseId)
            room.secureItemDao().deleteAllByMdbxDatabaseId(databaseId)
            room.passkeyDao().deleteAllByMdbxDatabaseId(databaseId)
            databaseDao.deleteDatabaseById(databaseId)
            rawRepository.deleteOwnedVaultFile(vaultFile)
            check(!File("${vaultFile.absolutePath}.blobs").exists())
        }
    }

    private class ZeroInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            remaining--
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 64L * 1024L * 1024L
    }
}
