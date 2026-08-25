package takagi.ru.monica.repository

import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import uniffi.mdbx_ffi.createPortableBackup

@RunWith(AndroidJUnit4::class)
class Mdbx2FeatureParityInstrumentedTest {
    @Test
    fun everyUnlockMethodReopensAfterTheCreatingSessionCloses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val createdDatabaseIds = mutableListOf<Long>()
        val createdVaults = mutableListOf<File>()
        val createdKeyFiles = mutableListOf<File>()

        suspend fun exercise(
            label: String,
            credential: MdbxVaultCredential,
            encryptedCredential: String?,
            keyFile: File? = null
        ) {
            val creator = Mdbx2Repository(context, databaseDao, securityManager)
            val vaultFile = creator.createInitializedVaultFile(MdbxTigaMode.SKY, credential)
            createdVaults += vaultFile
            val databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = "MDBX2 unlock $label ${UUID.randomUUID()}",
                    filePath = vaultFile.absolutePath,
                    storageLocation = MdbxStorageLocation.INTERNAL.name,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    engineType = MdbxEngineType.RUST_MDBX2.name,
                    tigaMode = MdbxTigaMode.SKY.name,
                    encryptedPassword = encryptedCredential,
                    unlockMethod = credential.unlockMethod.storedValue,
                    kdfProfile = "argon2id-mdbx2",
                    keyFileName = keyFile?.name,
                    keyFileUri = keyFile?.let(Uri::fromFile)?.toString(),
                    keyFileFingerprint = keyFile?.readBytes()?.let(MdbxVaultCrypto::fingerprint),
                    workingCopyPath = vaultFile.absolutePath,
                    cacheCopyPath = vaultFile.absolutePath,
                    isOfflineAvailable = true,
                    lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
                )
            )
            createdDatabaseIds += databaseId

            val marker = creator.createFolder(databaseId, "Unlock $label", null)
            val reopened = Mdbx2Repository(context, databaseDao, securityManager)
            assertEquals(
                "Unlock $label",
                reopened.listFolders(databaseId).single { it.folderId == marker.folderId }.name
            )
            assertEquals("MDBX2", reopened.getVaultDiagnostics(databaseId).formatVersion)
        }

        try {
            val password = "password-${UUID.randomUUID()}"
            exercise(
                label = "password",
                credential = MdbxVaultCredential(MdbxUnlockMethod.MASTER_PASSWORD, password = password),
                encryptedCredential = securityManager.encryptData(password)
            )

            val keyFileOnly = File(context.cacheDir, "mdbx2-key-${UUID.randomUUID()}.key").apply {
                writeBytes(MdbxVaultCrypto.generateKeyFileBytes())
            }
            createdKeyFiles += keyFileOnly
            val keyFileOnlyBytes = keyFileOnly.readBytes()
            try {
                exercise(
                    label = "key-file",
                    credential = MdbxVaultCredential(
                        unlockMethod = MdbxUnlockMethod.KEY_FILE,
                        keyFileBytes = keyFileOnlyBytes
                    ),
                    encryptedCredential = null,
                    keyFile = keyFileOnly
                )
            } finally {
                keyFileOnlyBytes.fill(0)
            }

            val combinedKeyFile = File(context.cacheDir, "mdbx2-combined-${UUID.randomUUID()}.key").apply {
                writeBytes(MdbxVaultCrypto.generateKeyFileBytes())
            }
            createdKeyFiles += combinedKeyFile
            val combinedPassword = "combined-${UUID.randomUUID()}"
            val combinedBytes = combinedKeyFile.readBytes()
            try {
                exercise(
                    label = "combined",
                    credential = MdbxVaultCredential(
                        unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE,
                        password = combinedPassword,
                        keyFileBytes = combinedBytes
                    ),
                    encryptedCredential = securityManager.encryptData(combinedPassword),
                    keyFile = combinedKeyFile
                )
            } finally {
                combinedBytes.fill(0)
            }

            val deviceKey = MdbxVaultCrypto.generateDeviceKeyBytes()
            try {
                exercise(
                    label = "device-key",
                    credential = MdbxVaultCredential(
                        unlockMethod = MdbxUnlockMethod.DEVICE_KEY,
                        deviceKeyBytes = deviceKey
                    ),
                    encryptedCredential = MdbxVaultCrypto.encodeDeviceKey(
                        deviceKey,
                        securityManager::encryptData
                    )
                )
            } finally {
                deviceKey.fill(0)
            }
        } finally {
            createdDatabaseIds.forEach { databaseId ->
                runCatching { databaseDao.deleteDatabaseById(databaseId) }
            }
            val cleaner = Mdbx2Repository(context, databaseDao, securityManager)
            createdVaults.forEach { vault -> runCatching { cleaner.deleteOwnedVaultFile(vault) } }
            createdKeyFiles.forEach(File::delete)
        }
    }

    @Test
    fun projectTagsReplaceAggregateSearchAndSurviveReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val password = "tags-${UUID.randomUUID()}"
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        try {
            databaseId = insertPasswordDatabase(
                databaseDao = databaseDao,
                securityManager = securityManager,
                vaultFile = vaultFile,
                password = password,
                name = "MDBX2 tags"
            )
            val work = repository.createFolder(databaseId, "Work accounts", null)
            val personal = repository.createFolder(databaseId, "Personal", null)
            repository.setProjectTags(databaseId, work.folderId, listOf(" Work ", "Cloud", "work"))
            repository.setProjectTags(databaseId, personal.folderId, listOf("Cloud", "Home"))

            assertEquals(listOf("Cloud", "Work"), repository.listProjectTags(databaseId, work.folderId))
            assertEquals(
                listOf("Cloud" to 2, "Home" to 1, "Work" to 1),
                repository.listAllProjectTags(databaseId).map { it.tag to it.projectCount }
            )
            assertEquals(
                listOf(work.folderId),
                repository.searchProjects(databaseId, "accounts", listOf("cloud", "WORK"))
                    .map { it.projectId }
            )

            repository.setProjectTags(databaseId, work.folderId, listOf("Team"))
            val reopened = Mdbx2Repository(context, databaseDao, securityManager)
            assertEquals(listOf("Team"), reopened.listProjectTags(databaseId, work.folderId))
            assertEquals(
                listOf(personal.folderId),
                reopened.searchProjects(databaseId, "", listOf("cloud")).map { it.projectId }
            )
            assertFalse(reopened.listAllProjectTags(databaseId).any { it.tag.equals("Work", true) })
        } finally {
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(vaultFile)
        }
    }

    @Test
    fun externalWorkingCopiesPublishRefreshAndReopenFromTheSourceDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val password = "external-${UUID.randomUUID()}"
        val seed = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val source = File(context.cacheDir, "external-${UUID.randomUUID()}.mdbx")
        val ownedCopies = mutableListOf<File>()
        val databaseIds = mutableListOf<Long>()
        try {
            createPortableBackup(seed.absolutePath, source.absolutePath)
            val sourceUri = Uri.fromFile(source)
            val firstCopy = repository.copyExternalDocumentToOwnedFile(sourceUri)
            ownedCopies += firstCopy
            val firstId = insertExternalPasswordDatabase(
                databaseDao,
                securityManager,
                sourceUri,
                firstCopy,
                password,
                "External first"
            )
            databaseIds += firstId

            val firstFolder = repository.createFolder(firstId, "Published by first", null)
            val afterFirstPublish = source.sha256()

            val secondCopy = repository.copyExternalDocumentToOwnedFile(sourceUri)
            ownedCopies += secondCopy
            val secondId = insertExternalPasswordDatabase(
                databaseDao,
                securityManager,
                sourceUri,
                secondCopy,
                password,
                "External second"
            )
            databaseIds += secondId
            assertTrue(repository.listFolders(secondId).any { it.folderId == firstFolder.folderId })

            val secondFolder = repository.createFolder(secondId, "Published by second", null)
            assertFalse(MessageDigest.isEqual(afterFirstPublish, source.sha256()))
            repository.refreshExternalWorkingCopy(firstId)
            val refreshed = Mdbx2Repository(context, databaseDao, securityManager)
                .listFolders(firstId)
                .associateBy { it.folderId }
            assertEquals("Published by first", refreshed.getValue(firstFolder.folderId).name)
            assertEquals("Published by second", refreshed.getValue(secondFolder.folderId).name)
            assertEquals(MdbxSyncStatus.IN_SYNC.name, databaseDao.getDatabaseById(firstId)?.lastSyncStatus)
        } finally {
            databaseIds.forEach { id -> runCatching { databaseDao.deleteDatabaseById(id) } }
            ownedCopies.forEach { copy -> runCatching { repository.deleteOwnedVaultFile(copy) } }
            repository.deleteOwnedVaultFile(seed)
            source.delete()
        }
    }

    @Test
    fun manualSyncBundleAppliesToAReplicaRejectsTamperingAndIsIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val password = "manual-sync-${UUID.randomUUID()}"
        val sourceFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val replicaFile = File(context.cacheDir, "mdbx2-replica-${UUID.randomUUID()}.mdbx")
        val databaseIds = mutableListOf<Long>()
        try {
            createPortableBackup(sourceFile.absolutePath, replicaFile.absolutePath)
            val sourceId = insertPasswordDatabase(
                databaseDao,
                securityManager,
                sourceFile,
                password,
                "Manual sync source"
            )
            val replicaId = insertPasswordDatabase(
                databaseDao,
                securityManager,
                replicaFile,
                password,
                "Manual sync replica"
            )
            databaseIds += sourceId
            databaseIds += replicaId

            val sourceFolder = repository.createFolder(sourceId, "Manual bundle folder", null)
            repository.setProjectTags(sourceId, sourceFolder.folderId, listOf("Synced", "Manual"))
            val bundle = repository.exportSyncBundle(sourceId, null)
            assertTrue(bundle.commitCount > 0)

            val firstApply = repository.importSyncBundle(replicaId, bundle)
            assertTrue(firstApply.appliedObjectCount > 0)
            assertEquals(
                "Manual bundle folder",
                repository.listFolders(replicaId).single { it.folderId == sourceFolder.folderId }.name
            )
            assertEquals(
                listOf("Manual", "Synced"),
                repository.listProjectTags(replicaId, sourceFolder.folderId)
            )

            val secondApply = repository.importSyncBundle(replicaId, bundle)
            assertEquals(0, secondApply.appliedObjectCount)
            assertTrue(secondApply.keptLocalObjectCount > 0)

            val envelope = JSONObject(bundle.payloadJson)
            val tamperedBytes = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
            try {
                tamperedBytes[tamperedBytes.lastIndex] =
                    (tamperedBytes.last().toInt() xor 0x01).toByte()
                val tamperedBundle = bundle.copy(
                    payloadJson = envelope
                        .put("data", Base64.encodeToString(tamperedBytes, Base64.NO_WRAP))
                        .toString(),
                    payloadHash = tamperedBytes.sha256Hex()
                )
                val tamperFailure = runCatching {
                    repository.importSyncBundle(replicaId, tamperedBundle)
                }.exceptionOrNull()
                assertTrue(tamperFailure != null)
                assertEquals(
                    listOf(sourceFolder.folderId),
                    repository.listFolders(replicaId).map { it.folderId }
                )
            } finally {
                tamperedBytes.fill(0)
            }
        } finally {
            databaseIds.forEach { id -> runCatching { databaseDao.deleteDatabaseById(id) } }
            repository.deleteOwnedVaultFile(sourceFile)
            replicaFile.delete()
        }
    }

    @Test
    fun metadataBenchmarkIsBoundedCreatesNoVisibleItemsAndSurvivesReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = PasswordDatabase.getDatabase(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val securityManager = SecurityManager(context)
        val password = "benchmark-${UUID.randomUUID()}"
        val repository = Mdbx2Repository(context, databaseDao, securityManager)
        val vaultFile = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        var databaseId = 0L
        try {
            databaseId = insertPasswordDatabase(
                databaseDao,
                securityManager,
                vaultFile,
                password,
                "MDBX2 benchmark"
            )
            val foldersBefore = repository.listFolders(databaseId)
            val historyBefore = repository.listDeltaHistory(databaseId).size

            val result = repository.runBenchmark(databaseId, operationCount = 7)

            assertEquals("rust-metadata-commit", result.scenario)
            assertEquals(7, result.operationCount)
            assertTrue(result.elapsedMs >= 0L)
            assertEquals(foldersBefore, repository.listFolders(databaseId))
            assertEquals(historyBefore + 7, repository.listDeltaHistory(databaseId).size)

            val reopened = Mdbx2Repository(context, databaseDao, securityManager)
            assertEquals(foldersBefore, reopened.listFolders(databaseId))
            assertEquals(historyBefore + 7, reopened.listDeltaHistory(databaseId).size)
        } finally {
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            repository.deleteOwnedVaultFile(vaultFile)
        }
    }

    private suspend fun insertPasswordDatabase(
        databaseDao: takagi.ru.monica.data.LocalMdbxDatabaseDao,
        securityManager: SecurityManager,
        vaultFile: File,
        password: String,
        name: String
    ): Long = databaseDao.insertDatabase(
        LocalMdbxDatabase(
            name = "$name ${UUID.randomUUID()}",
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

    private suspend fun insertExternalPasswordDatabase(
        databaseDao: takagi.ru.monica.data.LocalMdbxDatabaseDao,
        securityManager: SecurityManager,
        sourceUri: Uri,
        workingCopy: File,
        password: String,
        name: String
    ): Long = databaseDao.insertDatabase(
        LocalMdbxDatabase(
            name = "$name ${UUID.randomUUID()}",
            filePath = sourceUri.toString(),
            storageLocation = MdbxStorageLocation.EXTERNAL.name,
            sourceType = MdbxSourceType.LOCAL_EXTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            tigaMode = MdbxTigaMode.SKY.name,
            encryptedPassword = securityManager.encryptData(password),
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
            kdfProfile = "argon2id-mdbx2",
            workingCopyPath = workingCopy.absolutePath,
            cacheCopyPath = workingCopy.absolutePath,
            isOfflineAvailable = true,
            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
        )
    )

    private fun File.sha256(): ByteArray =
        inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest()
        }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
}
