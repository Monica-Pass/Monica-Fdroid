package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.room.Room
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.utils.KeePassCredentialTransitionStore
import takagi.ru.monica.utils.KeePassKeyFileStore
import takagi.ru.monica.utils.WebDavHelper
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey

/** Synthetic private contexts: never delete or replace the installed user's vault or Keystore aliases. */
class ConnectionRecoveryInstrumentedTest {
    private fun credentials(password: String, key: ByteArray?): Credentials =
        takagi.ru.monica.utils.KeePassCredentialSupport.buildExactCredentials(password, key)
    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "connection-recovery-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val aliases = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.path }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names.add(name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val password = "Synthetic connection recovery 316!"
        val recovery = LocalVaultRecovery(context)
        var security: SecurityManager
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        init {
            SecurityManager.clearRuntimeUnlockCache()
            security = SecurityManager(context)
            assertTrue(security.setMasterPassword(password))
        }
        fun legacy(plain: String): String {
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .getKey("monica_data_key_v2_compat", null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return "C2|" + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }
        fun damage(name: String = SecurePreferencesStore.MONICA) {
            context.getSharedPreferences(name, 0).edit()
                .remove("__androidx_security_crypto_encrypted_prefs_value_keyset__").commit()
            SecurityManager.clearRuntimeUnlockCache()
        }
        fun recover() {
            val original = context.getSharedPreferences(SecurePreferencesStore.MONICA, 0).all.toMap()
            assertFalse(recovery.recover("wrong password"))
            assertEquals(original, context.getSharedPreferences(SecurePreferencesStore.MONICA, 0).all)
            assertTrue(recovery.recover(password))
            aliases.add(recovery.activeStore()!!.second)
            assertEquals(original, context.getSharedPreferences(SecurePreferencesStore.MONICA, 0).all)
            SecurityManager.clearRuntimeUnlockCache()
            security = SecurityManager(context)
            assertTrue(security.unlockVaultWithPassword(password))
        }
        override fun close() {
            db.close()
            Mdbx2NativeReadSessions.clear()
            SecurityManager.clearRuntimeUnlockCache()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) }
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name.startsWith("connection-recovery-"))
            root.deleteRecursively()
        }
    }

    @Test fun webDavAndBitwardenSettingsRecoverWithoutEnablingCloudWrites() = runBlocking {
        Fixture().use { f ->
            val webdav = WebDavHelper(f.context)
            webdav.configure("https://example.invalid/dav", "synthetic-user", " DAV 密码  ")
            webdav.configureEncryption(true, " archive password  ")
            // Preserve the existing multi-account serialization; this migration must
            // not log out an account, replace its keys or acknowledge queued edits.
            fun encoded(value: String) = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
            val keyBytes = ByteArray(32) { it.toByte() }
            val encodedKey = encoded(Base64.encodeToString(keyBytes, Base64.NO_WRAP))
            val account = takagi.ru.monica.data.bitwarden.BitwardenVault(email = "fixture@example.invalid",
                serverUrl = "https://vault.example.invalid", identityUrl = "https://vault.example.invalid/identity",
                apiUrl = "https://vault.example.invalid/api", encryptedAccessToken = encoded("synthetic-access"),
                encryptedRefreshToken = encoded("synthetic-refresh"), encryptedMasterKey = encodedKey,
                encryptedEncKey = encodedKey, encryptedMacKey = encodedKey, syncEnabled = false, isLocked = true)
            val vaultId = f.db.bitwardenVaultDao().insert(account)
            val operation = takagi.ru.monica.data.bitwarden.BitwardenPendingOperation(vaultId = vaultId,
                operationType = "UPDATE", targetType = "CIPHER", payloadJson = "{\"cipherId\":\"synthetic-unsynced\"}",
                status = "FAILED", retryCount = 2, lastError = "synthetic offline")
            val operationId = f.db.bitwardenPendingOperationDao().insert(operation)
            val rawRecord = takagi.ru.monica.data.bitwarden.BitwardenSyncRawEntryRecord(vaultId = vaultId,
                bitwardenCipherId = "synthetic-cipher", operation = "sync", endpoint = "/sync",
                payloadCipherText = f.legacy("{\"synthetic\":\"remote diagnostic\"}"), payloadDigest = "fixture-digest",
                payloadSource = "fixture", responseCode = 200)
            val rawId = f.db.bitwardenSyncRawEntryRecordDao().insert(rawRecord)
            assertEquals(1, PortableLocalCipherMigration.run(f.db, f.security))
            val settings = RecoverableBitwardenSettings.open(f.context)
            assertTrue(settings.edit().putLong("active_vault_id", 41).putLong("last_sync_time", 123456)
                .putBoolean("auto_sync_enabled", true).putBoolean("sync_on_wifi_only", true)
                .putBoolean("never_lock_bitwarden", true).commit())
            f.damage(SecurePreferencesStore.BITWARDEN)
            val oldBitwarden = f.context.getSharedPreferences(SecurePreferencesStore.BITWARDEN, 0).all.toMap()
            f.damage(); f.recover()
            assertEquals(account.copy(id = vaultId), f.db.bitwardenVaultDao().getVaultById(vaultId))
            assertEquals(operation.copy(id = operationId), f.db.bitwardenPendingOperationDao().getById(operationId))
            val rawAfter = f.db.bitwardenSyncRawEntryRecordDao().getLatestByCipher(vaultId, "synthetic-cipher")!!
            assertEquals(rawRecord.copy(id = rawId), rawAfter.copy(payloadCipherText = rawRecord.payloadCipherText))
            assertEquals("{\"synthetic\":\"remote diagnostic\"}", f.security.decryptData(rawAfter.payloadCipherText))
            val restoredKey = f.db.bitwardenVaultDao().getVaultById(vaultId)!!.encryptedEncKey!!
            assertArrayEquals(keyBytes, Base64.decode(String(Base64.decode(restoredKey, Base64.NO_WRAP)), Base64.NO_WRAP))
            val restored = WebDavHelper(f.context)
            assertEquals(webdav.getCurrentConfig(), restored.getCurrentConfig())
            assertEquals(" DAV 密码  ", restored.getCurrentPasswordForEdit())
            assertTrue(restored.isEncryptionEnabled())
            assertEquals(" archive password  ", f.security.getProtectedString("webdav_secure_encryption_password"))
            val recovered = RecoverableBitwardenSettings.open(f.context)
            assertEquals(41L, recovered.getLong("active_vault_id", -1))
            assertEquals(123456L, recovered.getLong("last_sync_time", -1))
            assertTrue(recovered.getBoolean("sync_on_wifi_only", false))
            assertFalse(recovered.getBoolean("auto_sync_enabled", true))
            assertFalse(recovered.getBoolean("never_lock_bitwarden", true))
            assertEquals(oldBitwarden, f.context.getSharedPreferences(SecurePreferencesStore.BITWARDEN, 0).all)
            assertFalse("An old settings manager must not overwrite recovery", settings.edit().putLong("active_vault_id", 99).commit())
            recovered.edit().putLong("active_vault_id", 42).apply()
            assertEquals(42L, RecoverableBitwardenSettings.open(f.context).getLong("active_vault_id", -1))
            val envelope = File(f.context.filesDir, "local-vault-recovery/recovery.json").readText()
            assertFalse(envelope.contains("synthetic-user")); assertFalse(envelope.contains("archive password"))
        }
    }

    @Test fun failedAndOldKeePassTransitionsRemainAvailableAndOfflineCacheMigrates() = runBlocking {
        Fixture().use { f ->
            val pending = f.context.getSharedPreferences("keepass_credential_transitions", 0)
            val offline = f.context.getSharedPreferences("bitwarden_offline_secret_cache", 0)
            pending.edit().putString("database_71_password", f.legacy("pending-new-password"))
                .putLong("database_71_created_at", 1).putString("database_72_password", "C2|broken")
                .putLong("database_72_created_at", 2).commit()
            offline.edit().putString("secret_91", f.legacy("offline-unsynced-secret"))
                .putString("cipher_91", "remote-cipher-91").putString("secret_92", "C2|broken").commit()
            val store = KeePassCredentialTransitionStore(f.context)
            assertNull(store.read(72))
            assertEquals("C2|broken", pending.getString("database_72_password", null))
            assertEquals("pending-new-password", store.read(71)!!.password)
            assertEquals(2, PortablePreferenceCipherMigration.run(f.context, f.security))
            assertEquals(0, PortablePreferenceCipherMigration.run(f.context, f.security))
            assertEquals(1L, pending.getLong("database_71_created_at", 0))
            assertEquals("remote-cipher-91", offline.getString("cipher_91", null))
            f.damage(); f.recover()
            assertEquals("pending-new-password", KeePassCredentialTransitionStore(f.context).read(71)!!.password)
            assertEquals("offline-unsynced-secret", f.security.decryptData(offline.getString("secret_91", null)!!))
            assertEquals("C2|broken", offline.getString("secret_92", null))
        }
    }

    @Test fun remoteCredentialsAndAllKdbxUnlockFormsSurviveWithoutChangingFilesOrPendingState() = runBlocking {
        Fixture().use { f ->
            val remote = KeepassRemoteSource(providerType = KeePassRemoteProviderType.WEBDAV,
                displayName = "synthetic DAV", remotePath = "/safe/vault.kdbx", baseUrl = "https://example.invalid",
                usernameEncrypted = f.legacy("remote-user"), passwordEncrypted = f.legacy("remote-password"),
                autoSyncEnabled = false, updatedAt = 123)
            val sourceId = f.db.keepassRemoteSourceDao().insertSource(remote)
            val mSource = MdbxRemoteSource(displayName = "synthetic MDBX DAV", remotePath = "/safe/vault.mdbx",
                usernameEncrypted = f.legacy("mdbx-user"), passwordEncrypted = f.legacy("mdbx-password"), updatedAt = 456)
            val mSourceId = f.db.mdbxRemoteSourceDao().insertSource(mSource)
            val files = mutableListOf<Pair<File, ByteArray>>()
            val rows = mutableListOf<LocalKeePassDatabase>()
            val key = ByteArray(32) { (it + 7).toByte() }
            for (version3 in listOf(false, true)) for (mode in 0..2) {
                val password = if (mode == 1) "" else "KDBX 密码  "
                val material = key.takeIf { mode != 0 }
                val credentials = credentials(password, material)
                val base: KeePassDatabase = if (version3) KeePassDatabase.Ver3x.create("Root", Meta(), credentials)
                    .let { it.copy(header = it.header.copy(transformRounds = 100U)) }
                else KeePassDatabase.Ver4x.create("Root", Meta(), credentials).let {
                    val seed = when (val kdf = it.header.kdfParameters) { is KdfParameters.Aes -> kdf.seed; is KdfParameters.Argon2 -> kdf.salt }
                    it.copy(header = it.header.copy(kdfParameters = KdfParameters.Aes(rounds = 100U, seed = seed)))
                }
                val file = File(f.context.filesDir, "fixture-$version3-$mode.kdbx")
                val entry = Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                    "Title" to EntryValue.Plain("Recovery fixture"),
                    "Password" to EntryValue.Encrypted(EncryptedValue.fromString("entry-secret")),
                    "otp" to EntryValue.Plain("otpauth://totp/Fixture?secret=JBSWY3DPEHPK3PXP"),
                    "UnknownField" to EntryValue.Plain("preserved")))
                file.outputStream().use { base.modifyParentGroup { copy(entries = listOf(entry)) }.encode(it) }
                try {
                    file.inputStream().use { KeePassDatabase.decode(it, credentials(password, material)) }
                } catch (error: Exception) { throw AssertionError("Fresh KDBX cannot reopen: ${file.name}", error) }
                val copy = material?.let { KeePassKeyFileStore(f.context).copyBytes(it) }
                // Model a keyfile produced by an old installation before MDK conversion.
                copy?.let { File(f.context.noBackupFilesDir, it.relativePath).writeText(f.legacy(Base64.encodeToString(key, Base64.NO_WRAP))) }
                val row = LocalKeePassDatabase(name = file.name, filePath = file.name, encryptedPassword = f.legacy(password),
                    keyFileInternalPath = copy?.relativePath, sourceId = sourceId, lastSyncStatus = KeePassSyncStatus.PENDING_UPLOAD,
                    lastSyncError = "synthetic offline", lastSyncStateUpdatedAt = 12345)
                val id = f.db.localKeePassDatabaseDao().insertDatabase(row)
                rows.add(row.copy(id = id)); files.add(file to file.readBytes())
            }
            assertEquals(10, PortableLocalCipherMigration.run(f.db, f.security))
            KeePassKeyFileStore(f.context).migrateDeviceEncryptedCopies()
            assertEquals(0, PortableLocalCipherMigration.run(f.db, f.security))
            f.damage(); f.recover()
            files.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
            for (original in rows) {
                val row = f.db.localKeePassDatabaseDao().getDatabaseById(original.id)!!
                assertEquals(original, row.copy(encryptedPassword = original.encryptedPassword))
                val password = f.security.decryptData(row.encryptedPassword!!)
                val material = row.keyFileInternalPath?.let { KeePassKeyFileStore(f.context).readInternal(it) }
                assertEquals(row.name, f.security.decryptData(original.encryptedPassword!!), password)
                if (material != null) assertArrayEquals(row.name, key, material)
                val db = try { File(f.context.filesDir, row.filePath).inputStream().use {
                    KeePassDatabase.decode(it, credentials(password, material))
                } } catch (error: Exception) { throw AssertionError("Recovered KDBX cannot reopen: ${row.name}", error) }
                assertEquals("entry-secret", db.content.group.entries.single().fields.getValue("Password").content)
                assertEquals("preserved", db.content.group.entries.single().fields.getValue("UnknownField").content)
            }
            val restored = f.db.keepassRemoteSourceDao().getSourceById(sourceId)!!
            assertEquals(remote.copy(id = sourceId), restored.copy(usernameEncrypted = remote.usernameEncrypted, passwordEncrypted = remote.passwordEncrypted))
            assertEquals("remote-user", f.security.decryptData(restored.usernameEncrypted!!))
            assertEquals("remote-password", f.security.decryptData(restored.passwordEncrypted!!))
            val restoredM = f.db.mdbxRemoteSourceDao().getSourceById(mSourceId)!!
            assertEquals(mSource.copy(id = mSourceId), restoredM.copy(usernameEncrypted = mSource.usernameEncrypted, passwordEncrypted = mSource.passwordEncrypted))
            assertEquals("mdbx-password", f.security.decryptData(restoredM.passwordEncrypted!!))
        }
    }

    @Test fun allMdbxUnlockFormsKeepNativeContentsAndDeviceKeyTagsAfterRecovery() = runBlocking {
        Fixture().use { f ->
            val dao = f.db.localMdbxDatabaseDao()
            val sessions = Mdbx2VaultSessionExecutor(f.context, dao, f.security)
            val rows = mutableListOf<LocalMdbxDatabase>()
            val files = mutableListOf<Pair<File, ByteArray>>()
            for (mode in MdbxUnlockMethod.entries) {
                val key = MdbxVaultCrypto.generateKeyFileBytes()
                val device = MdbxVaultCrypto.generateDeviceKeyBytes()
                val keyfile = File(f.context.filesDir, "$mode.key").apply { writeBytes(key) }
                val credential = MdbxVaultCredential(mode, password = "MDBX fixture password",
                    keyFileBytes = key.takeIf { mode == MdbxUnlockMethod.KEY_FILE || mode == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE },
                    deviceKeyBytes = device.takeIf { mode == MdbxUnlockMethod.DEVICE_KEY })
                val file = sessions.createInitializedVaultFile(MdbxTigaMode.SKY, credential)
                val secret = if (mode == MdbxUnlockMethod.DEVICE_KEY) MdbxVaultCrypto.encodeDeviceKey(device, f::legacy)
                    else f.legacy("MDBX fixture password")
                val row = LocalMdbxDatabase(name = mode.name, filePath = file.absolutePath,
                    storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    engineType = MdbxEngineType.RUST_MDBX2.name, encryptedPassword = secret,
                    unlockMethod = mode.storedValue, keyFileUri = Uri.fromFile(keyfile).toString(),
                    lastSyncStatus = MdbxSyncStatus.PENDING_UPLOAD.name, lastAccessedAt = 789)
                val id = dao.insertDatabase(row)
                sessions.withVault(id) { _, vault ->
                    vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-recovery-fixture", listOf(
                        uniffi.mdbx_ffi.MdbxWriteCommand.CreateProject(UUID.randomUUID().toString(), "preserved project $mode")))
                    assertTrue(vault.listCollectionSummaries(100U, null).items.any { it.title == "preserved project $mode" })
                }
                rows.add(row.copy(id = id)); files.add(file to MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            }
            assertEquals(4, PortableLocalCipherMigration.run(f.db, f.security))
            assertEquals(0, PortableLocalCipherMigration.run(f.db, f.security))
            f.damage(); f.recover()
            files.forEach { (file, digest) -> assertArrayEquals(digest, MessageDigest.getInstance("SHA-256").digest(file.readBytes())) }
            val restoredSessions = Mdbx2VaultSessionExecutor(f.context, dao, f.security)
            for (original in rows) {
                val row = dao.getDatabaseById(original.id)!!
                assertEquals(original, row.copy(encryptedPassword = original.encryptedPassword))
                if (row.unlockMethodEnum == MdbxUnlockMethod.DEVICE_KEY) assertTrue(row.encryptedPassword!!.startsWith("mdbx2-device-key:v1:MDK|"))
                restoredSessions.withVault(row.id) { _, vault ->
                    assertTrue(vault.listCollectionSummaries(100U, null).items.any { it.title == "preserved project ${row.name}" })
                }
            }
        }
    }
}
