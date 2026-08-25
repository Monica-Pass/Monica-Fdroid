package takagi.ru.monica.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.security.SecurityManager
import uniffi.mdbx_ffi.MdbxVault
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.MdbxDeviceAssurance
import uniffi.mdbx_ffi.MdbxDeviceContext
import uniffi.mdbx_ffi.createVaultWithTigaMode
import uniffi.mdbx_ffi.openVault
import uniffi.mdbx_ffi.openVaultWithPasswordSecurityKey
import uniffi.mdbx_ffi.openVaultWithSecurityKey
import uniffi.mdbx_ffi.MdbxTigaMode as RustTigaMode

internal class Mdbx2VaultSessionExecutor(
    context: Context,
    private val databaseDao: LocalMdbxDatabaseDao,
    private val securityManager: SecurityManager,
    private val externalStorage: Mdbx2ExternalStorage = Mdbx2ExternalStorage(context)
) {
    private val appContext = context.applicationContext
    private val vaultLocks = ConcurrentHashMap<Long, Mutex>()

    private val deviceId: String by lazy {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(DEVICE_ID_KEY, null)?.takeIf { it.isNotBlank() }
            ?: "monica-android-${UUID.randomUUID()}".also { generated ->
                preferences.edit().putString(DEVICE_ID_KEY, generated).apply()
            }
    }

    suspend fun createInitializedVaultFile(
        tigaMode: MdbxTigaMode,
        password: String
    ): File = createInitializedVaultFile(
        tigaMode = tigaMode,
        credential = MdbxVaultCredential(
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
            password = password
        )
    )

    suspend fun createInitializedVaultFile(
        tigaMode: MdbxTigaMode,
        credential: MdbxVaultCredential
    ): File = withContext(Dispatchers.IO) {
        Mdbx2NativeRuntime.ensureLoaded()
        // Native setup consumes the byte arrays synchronously, but callers may
        // still need their key-file selection after creation (for example to
        // persist its fingerprint or retry an external publication).  Work on
        // private copies and wipe only those copies on every exit path.
        val workingCredential = credential.copy(
            keyFileBytes = credential.keyFileBytes?.clone(),
            deviceKeyBytes = credential.deviceKeyBytes?.clone()
        )
        try {
            validateCredential(workingCredential)
            val directory = File(appContext.filesDir, MDBX2_DIRECTORY).also { target ->
                check(target.exists() || target.mkdirs()) { "Cannot create MDBX2 directory" }
            }
            val file = File(directory, "${UUID.randomUUID()}.mdbx")
            val bootstrapPassword = normalizePassword(
                workingCredential.password?.takeIf { it.isNotEmpty() }
                    ?: "mdbx2-bootstrap-${UUID.randomUUID()}"
            )
            val vault = try {
                createVaultWithTigaMode(
                    path = file.absolutePath,
                    password = bootstrapPassword,
                    deviceId = deviceId,
                    mode = tigaMode.toRustMode()
                )
            } catch (error: Throwable) {
                deleteVaultArtifacts(file)
                val mapped = Mdbx2ErrorMapper.createFailure(error)
                MdbxDiagLogger.append(
                    "[MDBX2][create] failed kind=${mapped.kind.name} chain=${error.toDiagnosticChain()}"
                )
                throw mapped
            }
            var vaultClosed = false
            fun closeVault() {
                if (!vaultClosed) {
                    runCatching { vault.close() }
                    vaultClosed = true
                }
            }
            try {
                val rootProjectId = rootProjectId(vault.info().vaultId)
                vault.executeWriteOperation(
                    operationId = UUID.randomUUID().toString(),
                    operationKind = "monica-initialize",
                    commands = listOf(
                        MdbxWriteCommand.CreateProject(
                            projectId = rootProjectId,
                            title = ROOT_PROJECT_TITLE
                        )
                    )
                )
                configureRequestedUnlockMethod(vault, workingCredential)
            } catch (error: Throwable) {
                closeVault()
                deleteVaultArtifacts(file)
                val mapped = Mdbx2ErrorMapper.createFailure(error)
                MdbxDiagLogger.append(
                    "[MDBX2][create] failed kind=${mapped.kind.name} chain=${error.toDiagnosticChain()}"
                )
                throw mapped
            } finally {
                closeVault()
            }
            file
        } finally {
            workingCredential.keyFileBytes?.fill(0)
            workingCredential.deviceKeyBytes?.fill(0)
        }
    }

    suspend fun deleteOwnedVaultFile(file: File): Boolean = withContext(Dispatchers.IO) {
        val directory = ownedVaultDirectory()
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return@withContext false
        if (candidate.parentFile != directory || candidate.extension.lowercase() != "mdbx") {
            return@withContext false
        }
        val blobStoreSidecars = listOf("blobs", "leases", "transfers").map { suffix ->
            File(directory, "${candidate.name}.$suffix").canonicalFile
        }
        if (blobStoreSidecars.any { sidecar -> sidecar.parentFile != directory }) {
            return@withContext false
        }
        val sidecarsDeleted = blobStoreSidecars
            .map { sidecar -> !sidecar.exists() || sidecar.deleteRecursively() }
            .all { deleted -> deleted }
        sidecarsDeleted && deleteVaultArtifacts(candidate)
    }

    internal fun isOwnedVaultFile(file: File): Boolean {
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return candidate.parentFile == ownedVaultDirectory() &&
            candidate.extension.equals("mdbx", ignoreCase = true)
    }

    suspend fun <T> withVault(
        databaseId: Long,
        block: suspend (LocalMdbxDatabase, MdbxVault) -> T
    ): T = withVaultInternal(databaseId, mutating = false, block)

    suspend fun <T> withMutatingVault(
        databaseId: Long,
        block: suspend (LocalMdbxDatabase, MdbxVault) -> T
    ): T = withVaultInternal(databaseId, mutating = true, block)

    suspend fun createExternalDocument(
        treeUri: Uri,
        displayName: String,
        workingCopy: File
    ): Mdbx2ExternalDocument = externalStorage.createDocument(treeUri, displayName, workingCopy)

    suspend fun deleteCreatedExternalDocument(document: Mdbx2ExternalDocument) {
        externalStorage.deleteCreatedDocument(document)
    }

    suspend fun copyExternalDocumentToOwnedFile(
        sourceUri: Uri,
        sourceTreeUri: Uri? = null
    ): File = withContext(Dispatchers.IO) {
        val directory = ownedVaultDirectory().also { target ->
            check(target.exists() || target.mkdirs()) { "Cannot create MDBX2 directory" }
        }
        val target = File(directory, "${UUID.randomUUID()}.mdbx")
        externalStorage.copyDocumentToOwnedFile(sourceUri, target, sourceTreeUri)
        target
    }

    suspend fun inspectVaultFormat(file: File): String? = withContext(Dispatchers.IO) {
        Mdbx2NativeRuntime.ensureLoaded()
        uniffi.mdbx_ffi.inspectVaultMigration(file.absolutePath).formatVersion
    }

    suspend fun validatePasswordVaultFile(file: File, password: String) = withContext(Dispatchers.IO) {
        Mdbx2NativeRuntime.ensureLoaded()
        val vault = openVault(
            path = file.absolutePath,
            password = normalizePassword(password),
            deviceId = deviceId
        )
        vault.close()
    }

    suspend fun validateVaultFile(file: File, credential: MdbxVaultCredential) =
        withContext(Dispatchers.IO) {
            Mdbx2NativeRuntime.ensureLoaded()
            val workingCredential = credential.copy(
                keyFileBytes = credential.keyFileBytes?.clone(),
                deviceKeyBytes = credential.deviceKeyBytes?.clone()
            )
            try {
                val vault = openVaultWithCredential(file, workingCredential)
                vault.close()
            } finally {
                workingCredential.keyFileBytes?.fill(0)
                workingCredential.deviceKeyBytes?.fill(0)
            }
        }

    suspend fun flushExternalWorkingCopy(databaseId: Long, onlyIfPending: Boolean) =
        withContext(Dispatchers.IO) {
            vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
                val database = requireDatabase(databaseId)
                if (database.sourceTypeEnum != MdbxSourceType.LOCAL_EXTERNAL) return@withLock
                if (onlyIfPending && database.lastSyncStatus != takagi.ru.monica.data.MdbxSyncStatus.PENDING_UPLOAD.name) {
                    return@withLock
                }
                val file = resolveLocalFile(database)
                if (!file.isFile) throw Mdbx2ErrorMapper.fileMissing()
                publishExternal(database, file)
            }
        }

    suspend fun refreshExternalWorkingCopy(databaseId: Long) = withContext(Dispatchers.IO) {
        vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
            var database = requireDatabase(databaseId)
            require(database.sourceTypeEnum == MdbxSourceType.LOCAL_EXTERNAL) {
                "MDBX2 refresh requires an external local vault"
            }
            val workingCopy = resolveLocalFile(database)
            if (database.lastSyncStatus == takagi.ru.monica.data.MdbxSyncStatus.PENDING_UPLOAD.name) {
                publishExternal(database, workingCopy)
                database = requireDatabase(databaseId)
            }
            externalStorage.replaceWorkingCopyFromDocument(
                sourceUri = Uri.parse(database.filePath),
                sourceTreeUri = database.externalTreeUri?.takeIf(String::isNotBlank)?.let(Uri::parse),
                workingCopy = workingCopy
            ) { staged ->
                val vault = openVaultForDatabase(database, staged)
                vault.close()
            }
            databaseDao.updateSyncSuccess(
                databaseId = databaseId,
                status = takagi.ru.monica.data.MdbxSyncStatus.IN_SYNC.name,
                time = System.currentTimeMillis()
            )
        }
    }

    private suspend fun <T> withVaultInternal(
        databaseId: Long,
        mutating: Boolean,
        block: suspend (LocalMdbxDatabase, MdbxVault) -> T
    ): T = withContext(Dispatchers.IO) {
        Mdbx2NativeRuntime.ensureLoaded()
        vaultLocks.getOrPut(databaseId) { Mutex() }.withLock {
            val database = requireDatabase(databaseId)
            val file = resolveLocalFile(database)
            if (!file.isFile) throw Mdbx2ErrorMapper.fileMissing()
            val vault = try {
                openVaultForDatabase(database, file)
            } catch (error: Throwable) {
                val mapped = Mdbx2ErrorMapper.openFailure(error)
                MdbxDiagLogger.append(
                    "[MDBX2][open] failed databaseId=$databaseId kind=${mapped.kind.name} cause=${error::class.java.simpleName}"
                )
                throw mapped
            }
            val result = try {
                block(database, vault)
            } finally {
                vault.close()
            }
            if (mutating) finalizeMutation(database, file)
            result
        }
    }

    private fun decryptPassword(database: LocalMdbxDatabase): String {
        if (!database.unlockMethodEnum.requiresPassword()) return ""
        return try {
            database.encryptedPassword
                ?.takeIf { it.isNotBlank() }
                ?.let(securityManager::decryptData)
                .orEmpty()
        } catch (error: Throwable) {
            throw Mdbx2ErrorMapper.credentialUnavailable(error)
        }
    }

    private fun openVaultForDatabase(
        database: LocalMdbxDatabase,
        file: File
    ): MdbxVault {
        val credential = credentialForDatabase(database)
        return try {
            openVaultWithCredential(file, credential)
        } finally {
            credential.keyFileBytes?.fill(0)
            credential.deviceKeyBytes?.fill(0)
        }
    }

    private fun openVaultWithCredential(
        file: File,
        credential: MdbxVaultCredential
    ): MdbxVault {
        validateCredential(credential)
        val password = credential.password?.let(::normalizePassword).orEmpty()
        val keyMaterial = credential.keyFileBytes ?: credential.deviceKeyBytes
        return when (credential.unlockMethod) {
            MdbxUnlockMethod.MASTER_PASSWORD -> openVault(
                path = file.absolutePath,
                password = password,
                deviceId = deviceId
            )
            MdbxUnlockMethod.KEY_FILE,
            MdbxUnlockMethod.DEVICE_KEY -> openVaultWithSecurityKey(
                path = file.absolutePath,
                keyMaterial = keyMaterial ?: error("MDBX security key is missing"),
                deviceId = deviceId
            )
            MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE -> openVaultWithPasswordSecurityKey(
                path = file.absolutePath,
                password = password,
                keyMaterial = keyMaterial ?: error("MDBX security key is missing"),
                deviceId = deviceId
            )
        }
    }

    private fun credentialForDatabase(database: LocalMdbxDatabase): MdbxVaultCredential {
        val method = database.unlockMethodEnum
        val password = if (method.requiresPassword()) decryptPassword(database) else null
        val keyMaterial = when (method) {
            MdbxUnlockMethod.KEY_FILE,
            MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE -> readKeyFile(database)
            MdbxUnlockMethod.DEVICE_KEY -> MdbxVaultCrypto.decodeDeviceKey(
                value = database.encryptedPassword,
                decrypt = securityManager::decryptData
            )
            MdbxUnlockMethod.MASTER_PASSWORD -> null
        }
        return MdbxVaultCredential(
            unlockMethod = method,
            password = password,
            keyFileBytes = keyMaterial.takeIf {
                method == MdbxUnlockMethod.KEY_FILE ||
                    method == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
            },
            deviceKeyBytes = keyMaterial.takeIf { method == MdbxUnlockMethod.DEVICE_KEY }
        )
    }

    private fun readKeyFile(database: LocalMdbxDatabase): ByteArray {
        val uriString = database.keyFileUri?.takeIf { it.isNotBlank() }
            ?: throw Mdbx2ErrorMapper.credentialUnavailable(
                IllegalStateException("MDBX key file URI is missing")
            )
        val bytes = appContext.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(KEY_FILE_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_KEY_FILE_BYTES) {
                    throw IllegalArgumentException("MDBX key file is too large")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw Mdbx2ErrorMapper.credentialUnavailable(
            IllegalStateException("MDBX key file cannot be read")
        )
        database.keyFileFingerprint?.takeIf { it.isNotBlank() }?.let { expected ->
            check(MdbxVaultCrypto.fingerprint(bytes).equals(expected, ignoreCase = true)) {
                "MDBX key file fingerprint does not match"
            }
        }
        return bytes
    }

    private fun validateCredential(credential: MdbxVaultCredential) {
        when (credential.unlockMethod) {
            MdbxUnlockMethod.MASTER_PASSWORD -> require(!credential.password.isNullOrEmpty()) {
                "MDBX master password is required"
            }
            MdbxUnlockMethod.KEY_FILE,
            MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE -> {
                if (credential.unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE) {
                    require(!credential.password.isNullOrEmpty()) {
                        "MDBX master password is required"
                    }
                }
                require(credential.keyFileBytes?.isEmpty() == false) {
                    "MDBX key file is required"
                }
            }
            MdbxUnlockMethod.DEVICE_KEY -> require(credential.deviceKeyBytes?.isEmpty() == false) {
                "MDBX device key is required"
            }
        }
    }

    private fun configureRequestedUnlockMethod(
        vault: MdbxVault,
        credential: MdbxVaultCredential
    ) {
        if (credential.unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD) return
        val device = snapshotDeviceContext()
        val passwordMethod = vault.listUnlockMethods()
            .firstOrNull { it.methodType == uniffi.mdbx_ffi.MdbxUnlockMethodType.PASSWORD }
            ?: error("MDBX bootstrap password method is missing")
        when (credential.unlockMethod) {
            MdbxUnlockMethod.KEY_FILE,
            MdbxUnlockMethod.DEVICE_KEY -> vault.setupLocalSecurityKeyUnlockWithDeviceContext(
                keyMaterial = credential.keyFileBytes ?: credential.deviceKeyBytes
                    ?: error("MDBX security key is missing"),
                device = device
            )
            MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE -> vault.setupPasswordSecurityKeyUnlock(
                password = normalizePassword(credential.password ?: error("MDBX password is missing")),
                keyMaterial = credential.keyFileBytes ?: error("MDBX key file is missing"),
                device = device
            )
            MdbxUnlockMethod.MASTER_PASSWORD -> Unit
        }
        vault.removeUnlockMethod(passwordMethod.methodId, device)
    }

    private fun snapshotDeviceContext(): MdbxDeviceContext = MdbxDeviceContext(
        assurance = MdbxDeviceAssurance.STANDARD,
        secureClipboardAvailable = true,
        screenCaptureProtectionAvailable = true,
        secureTempFilesAvailable = true
    )

    private suspend fun finalizeMutation(database: LocalMdbxDatabase, file: File) {
        when (database.sourceTypeEnum) {
            MdbxSourceType.LOCAL_INTERNAL -> databaseDao.updateSyncStatus(
                database.id,
                takagi.ru.monica.data.MdbxSyncStatus.LOCAL_ONLY.name,
                null
            )
            MdbxSourceType.LOCAL_EXTERNAL -> {
                databaseDao.updateSyncStatus(
                    database.id,
                    takagi.ru.monica.data.MdbxSyncStatus.PENDING_UPLOAD.name,
                    null
                )
                publishExternal(database, file)
            }
            MdbxSourceType.REMOTE_WEBDAV,
            MdbxSourceType.REMOTE_ONEDRIVE -> databaseDao.updateSyncStatus(
                database.id,
                takagi.ru.monica.data.MdbxSyncStatus.PENDING_UPLOAD.name,
                null
            )
        }
    }

    private suspend fun publishExternal(database: LocalMdbxDatabase, file: File) {
        try {
            val publication = externalStorage.publishWithMerge(
                database = database,
                workingCopy = file
            ) { stagedRemote ->
                mergeExternalRevision(
                    database = database,
                    workingCopy = file,
                    stagedRemote = stagedRemote
                )
            }
            if (publication.conflictCount > 0) {
                MdbxDiagLogger.append(
                    "[MDBX2][external-merge] publication completed " +
                        "databaseId=${database.id} conflicts=${publication.conflictCount}"
                )
            }
            databaseDao.updateSyncSuccess(
                databaseId = database.id,
                status = takagi.ru.monica.data.MdbxSyncStatus.IN_SYNC.name,
                time = System.currentTimeMillis()
            )
        } catch (error: Throwable) {
            databaseDao.updateSyncStatus(
                database.id,
                takagi.ru.monica.data.MdbxSyncStatus.FAILED.name,
                error.message
            )
            throw IllegalStateException(
                "Failed to publish MDBX2 vault to the selected local document",
                error
            )
        }
    }

    private fun mergeExternalRevision(
        database: LocalMdbxDatabase,
        workingCopy: File,
        stagedRemote: File
    ): Int {
        val localVault = openVaultForDatabase(database, workingCopy)
        var conflictCount = 0
        try {
            val remoteVault = openVaultForDatabase(database, stagedRemote)
            try {
                require(localVault.info().vaultId == remoteVault.info().vaultId) {
                    "External MDBX2 vault identity does not match the local working copy"
                }
                externalStorage.mergeSidecarIntoWorkingCopy(stagedRemote, workingCopy)
                val bundleDirectory = File(appContext.cacheDir, "mdbx2-external-merge")
                    .also { directory ->
                        check(directory.exists() || directory.mkdirs()) {
                            "Cannot create MDBX2 external merge directory"
                        }
                    }
                val bundle = File(bundleDirectory, "${UUID.randomUUID()}.mdbxsync")
                try {
                    remoteVault.exportManualSyncBundle(bundle.absolutePath)
                    val result = localVault.applyManualSyncBundle(bundle.absolutePath)
                    check(result.missingParentCount == 0u) {
                        "External MDBX2 merge contains commits with missing parents"
                    }
                    MdbxDiagLogger.append(
                        "[MDBX2][external-merge] imported " +
                            "databaseId=${database.id} applied=${result.appliedCommits} " +
                            "skipped=${result.skippedCommits} conflicts=${result.conflictCount}"
                    )
                    conflictCount = result.conflictCount.toInt()
                } finally {
                    bundle.delete()
                }
            } finally {
                remoteVault.close()
            }
        } finally {
            runCatching { localVault.close() }
        }
        return conflictCount
    }

    private suspend fun requireDatabase(databaseId: Long): LocalMdbxDatabase {
        val database = databaseDao.getDatabaseById(databaseId)
            ?: throw Mdbx2ErrorMapper.databaseNotFound()
        if (database.engineTypeEnum != MdbxEngineType.RUST_MDBX2) {
            throw Mdbx2ErrorMapper.unsupportedSource()
        }
        if (database.sourceTypeEnum !in SUPPORTED_SOURCE_TYPES) {
            throw Mdbx2ErrorMapper.unsupportedSource()
        }
        return database
    }

    private fun resolveLocalFile(database: LocalMdbxDatabase): File {
        val rawPath = database.resolvedActiveFilePath().takeIf { it.isNotBlank() }
            ?: throw Mdbx2ErrorMapper.fileMissing()
        return File(rawPath).let { file ->
            if (file.isAbsolute) file else File(appContext.filesDir, rawPath)
        }
    }

    private fun ownedVaultDirectory(): File =
        File(appContext.filesDir, MDBX2_DIRECTORY).canonicalFile

    private fun deleteVaultArtifacts(file: File): Boolean {
        val artifacts = listOf(
            file,
            File("${file.absolutePath}-wal"),
            File("${file.absolutePath}-shm")
        )
        val deletionResults = artifacts.map { artifact ->
            !artifact.exists() || artifact.delete()
        }
        return deletionResults.all { it }
    }

    companion object {
        private const val PREFERENCES_NAME = "mdbx2_vault_sessions"
        private const val DEVICE_ID_KEY = "device_id"
        private const val MDBX2_DIRECTORY = "mdbx2"
        private const val MAX_KEY_FILE_BYTES = 1024 * 1024
        private const val KEY_FILE_BUFFER_BYTES = 16 * 1024
        internal const val ROOT_PROJECT_TITLE = ".monica-root"
        private val SUPPORTED_SOURCE_TYPES = setOf(
            MdbxSourceType.LOCAL_INTERNAL,
            MdbxSourceType.LOCAL_EXTERNAL,
            MdbxSourceType.REMOTE_WEBDAV,
            MdbxSourceType.REMOTE_ONEDRIVE
        )

        internal fun rootProjectId(vaultId: String): String =
            UUID.nameUUIDFromBytes("monica-root:$vaultId".toByteArray(Charsets.UTF_8)).toString()

        internal fun normalizePassword(password: String): String =
            Normalizer.normalize(password, Normalizer.Form.NFC)
    }
}

private fun MdbxUnlockMethod.requiresPassword(): Boolean =
    this == MdbxUnlockMethod.MASTER_PASSWORD ||
        this == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE

private fun MdbxTigaMode.toRustMode(): RustTigaMode = when (this) {
    MdbxTigaMode.SKY -> RustTigaMode.SKY
    MdbxTigaMode.MULTI -> RustTigaMode.MULTI
    MdbxTigaMode.POWER -> RustTigaMode.POWER
}
