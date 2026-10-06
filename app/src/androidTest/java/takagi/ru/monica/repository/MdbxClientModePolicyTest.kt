package takagi.ru.monica.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import uniffi.mdbx_ffi.*
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import uniffi.mdbx_ffi.MdbxTigaMode as RustMode

class MdbxClientModePolicyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun fixture(block: suspend (PasswordDatabase, Mdbx2VaultSessionExecutor) -> Unit) {
        val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try { block(room, Mdbx2VaultSessionExecutor(context, room.localMdbxDatabaseDao(), SecurityManager(context))) }
        finally { room.close() }
    }

    @Test fun clientCannotCreateGlitterEvenWithBothFactors() = runBlocking {
        fixture { _, sessions ->
            val result = runCatching { sessions.createInitializedVaultFile(MdbxTigaMode.GLITTER,
                MdbxVaultCredential(MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE, "synthetic", ByteArray(32) { it.toByte() })) }
            assertEquals(Mdbx2FailureKind.UNSUPPORTED_SOURCE, (result.exceptionOrNull() as Mdbx2OperationException).kind)
        }
    }

    @Test fun unsupportedRecordIsRejectedBeforeFileAccessAndPreserved() = runBlocking {
        fixture { room, sessions ->
            for ((mode, password) in listOf("GLITTER" to "opaque", "MULTI" to "glitter-hw:v1:old")) {
                val row = LocalMdbxDatabase(name = "Synthetic unsupported", filePath = "missing-fixture-file",
                    engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = mode, encryptedPassword = password)
                val id = room.localMdbxDatabaseDao().insertDatabase(row)
                assertFalse(row.isUsable)
                assertTrue(MdbxCapability.entries.none { row.supports(it) })
                val result = runCatching { sessions.withMutatingVault(id) { _, _ -> fail("Unsupported vault must never open") } }
                assertEquals(Mdbx2FailureKind.UNSUPPORTED_SOURCE, (result.exceptionOrNull() as Mdbx2OperationException).kind)
                assertEquals(row.copy(id = id), room.localMdbxDatabaseDao().getDatabaseById(id))
            }
        }
    }

    @Test fun nativeGlitterRemainsSupportedButAndroidImportPreservesAndRejectsIt() = runBlocking {
        Mdbx2NativeRuntime.ensureLoaded()
        val file = File(context.cacheDir, "unsupported-mode-${UUID.randomUUID()}.mdbx")
        val key = ByteArray(32) { (it + 1).toByte() }
        try {
            createVaultWithPasswordSecurityKey(file.absolutePath, "synthetic-password", key, "android-policy-fixture",
                RustMode.GLITTER, MdbxDeviceContext(MdbxDeviceAssurance.STANDARD, true, true, true)).close()
            val before = file.readBytes()
            fixture { _, sessions ->
                // Even an incorrect password must produce unsupported-mode, not attempt a KDF.
                val result = runCatching { sessions.validateVaultFile(file,
                    MdbxVaultCredential(MdbxUnlockMethod.MASTER_PASSWORD, "incorrect")) }
                assertEquals(Mdbx2FailureKind.UNSUPPORTED_SOURCE, (result.exceptionOrNull() as Mdbx2OperationException).kind)
            }
            assertArrayEquals("Rejected import must not rewrite the encrypted database", before, file.readBytes())
            val native = openVaultWithPasswordSecurityKey(file.absolutePath, "synthetic-password", key, "native-still-supported")
            try { assertEquals(RustMode.GLITTER, native.resolveTigaPolicy(MdbxTigaScope(MdbxTigaScopeType.VAULT, null)).profile) }
            finally { native.close() }
        } finally {
            key.fill(0)
            listOf(file, File("${file.path}-wal"), File("${file.path}-shm")).forEach { it.delete() }
        }
    }

    @Test fun ordinaryProfilesStillCreateAndOpenWithCombinedCredentials() = runBlocking {
        fixture { _, sessions ->
            for (mode in listOf(MdbxTigaMode.SKY, MdbxTigaMode.MULTI, MdbxTigaMode.POWER)) {
                val credential = MdbxVaultCredential(MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE,
                    "synthetic-ordinary", ByteArray(32) { it.toByte() })
                val file = sessions.createInitializedVaultFile(mode, credential)
                try { assertEquals(mode, sessions.validateVaultFile(file, credential)) }
                finally { sessions.deleteOwnedVaultFile(file); credential.keyFileBytes?.fill(0) }
            }
        }
    }
}
