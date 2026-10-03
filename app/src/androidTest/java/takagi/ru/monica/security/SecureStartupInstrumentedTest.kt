package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SecureStartupInstrumentedTest {
    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "startup-fixture-${UUID.randomUUID()}-"
        val dataDir = java.io.File(base.cacheDir, prefix)
        val context = object : ContextWrapper(base) {
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = this@Fixture.dataDir.absolutePath
            }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
        }
        val raw get() = context.getSharedPreferences("monica_secure_prefs", Context.MODE_PRIVATE)
        fun create() {
            SecurityManager(context)
        }
        fun corruptKeyset() {
            val name = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
            val hex = checkNotNull(raw.getString(name, null))
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            // EncryptedKeyset protobuf field 2 is the length-delimited AEAD ciphertext.
            check(bytes[0].toInt() == 0x12)
            var cursor = 1
            var length = 0
            var shift = 0
            do {
                val value = bytes[cursor++].toInt() and 255
                length = length or ((value and 127) shl shift)
                shift += 7
            } while (value and 128 != 0)
            val tagEnd = cursor + length - 1
            check(tagEnd in bytes.indices)
            bytes[tagEnd] = (bytes[tagEnd].toInt() xor 1).toByte()
            check(raw.edit().putString(name, bytes.joinToString("") { "%02x".format(it) }).commit())
        }
        override fun close() {
            base.deleteSharedPreferences(prefix + "monica_secure_prefs")
            base.deleteSharedPreferences(prefix + "bitwarden_secure_prefs")
            java.io.File(dataDir, "shared_prefs/monica_secure_prefs.xml").delete()
            java.io.File(dataDir, "shared_prefs").delete()
            dataDir.delete()
        }
    }

    @Test fun reproduceAuthenticatedKeysetFailureWithoutTouchingUserData() {
        Fixture().use { fixture ->
            fixture.create()
            fixture.corruptKeyset()
            val before = fixture.raw.all.toMap()
            repeat(2) {
                val error = runCatching { SecurityManager(fixture.context) }.exceptionOrNull()
                assertTrue("Corrupted keyset must fail closed", error is SecureStorageUnavailableException)
                assertTrue(SecureStorageStartup.prepare(fixture.context) is SecureStartupResult.Blocked)
                assertFalse(SecureStorageStartup.readyForMaintenance)
                val causes = generateSequence(error) { it.cause }.map { it.javaClass.simpleName }.toList()
                assertTrue("Expected authenticated-decryption failure: $causes",
                    causes.any { it == "AEADBadTagException" || it == "KeyStoreException" })
                assertEquals(before, fixture.raw.all)
            }
        }
    }
    @Test fun retrySucceedsOnlyAfterTheOriginalConfigurationIsRestored() {
        Fixture().use { f ->
            f.create()
            val original = f.raw.all.mapValues { it.value as String }
            f.corruptKeyset()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
            val editor = f.raw.edit().clear()
            original.forEach { (key, value) -> editor.putString(key, value) }
            assertTrue(editor.commit())
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Ready)
            assertTrue(SecureStorageStartup.readyForMaintenance)
            assertEquals(original, f.raw.all)
        }
    }

    @Test fun missingMasterKeyIsNotRecreatedForAnExistingStore() {
        Fixture().use { f ->
            val alias = f.prefix + "master"
            val keystore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            try {
                SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA, alias)
                val before = f.raw.all.toMap()
                keystore.deleteEntry(alias) // Fixture-owned key only; never delete the application alias.
                val error = runCatching { SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA, alias) }.exceptionOrNull()
                assertEquals("MISSING_MASTER_KEY", (error as SecureStorageUnavailableException).reason)
                assertFalse(keystore.containsAlias(alias))
                assertEquals(before, f.raw.all)
            } finally { if (keystore.containsAlias(alias)) keystore.deleteEntry(alias) }
        }
    }

    @Test fun missingKeysetIsNotGeneratedOverExistingEncryptedValues() {
        Fixture().use { f ->
            f.create()
            f.raw.edit().remove("__androidx_security_crypto_encrypted_prefs_value_keyset__").commit()
            val before = f.raw.all.toMap()
            val result = SecureStorageStartup.prepare(f.context) as SecureStartupResult.Blocked
            assertEquals("INCOMPLETE_KEYSET", result.failure.reason)
            assertEquals(before, f.raw.all)
        }
    }

    @Test fun corruptIndividualPreferenceIsDetectedBeforeNormalStartup() {
        Fixture().use { f ->
            val prefs = SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA).preferences
            prefs.edit().putString("fixture-value", "synthetic-value").commit()
            val key = f.raw.all.keys.single { !it.startsWith("__androidx_security_crypto_") }
            val value = android.util.Base64.decode(f.raw.getString(key, null), android.util.Base64.DEFAULT)
            value[value.lastIndex] = (value.last().toInt() xor 1).toByte()
            f.raw.edit().putString(key, android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)).commit()
            val before = f.raw.all.toMap()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
            assertEquals(before, f.raw.all)
        }
    }

    @Test fun bitwardenFailureDoesNotBlockOrModifyTheLocalVault() {
        Fixture().use { f ->
            f.create()
            SecurePreferencesStore.open(f.context, SecurePreferencesStore.BITWARDEN)
            val raw = f.context.getSharedPreferences(SecurePreferencesStore.BITWARDEN, Context.MODE_PRIVATE)
            raw.edit().remove("__androidx_security_crypto_encrypted_prefs_key_keyset__").commit()
            val before = f.raw.all.toMap()
            val damaged = raw.all.toMap()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Ready)
            assertEquals(before, f.raw.all)
            assertEquals(damaged, raw.all)
        }
    }

    @Test fun healthyStoreRoundTripsAndDiagnosticsExcludeExceptionMessages() {
        Fixture().use { f ->
            val opened = SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA)
            opened.preferences.edit().putString("fixture", "synthetic-secret").commit()
            assertEquals("synthetic-secret", SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA).preferences.getString("fixture", null))
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Ready)
            val diagnostic = SecureStorageStartup.diagnostic(SecureStorageUnavailableException(
                SecurePreferencesStore.MONICA, "AUTHENTICATION_FAILED", Exception("synthetic-secret")))
            assertFalse(diagnostic.contains("synthetic-secret"))
            assertTrue(diagnostic.contains("AUTHENTICATION_FAILED"))
        }
    }

    @Test fun existingUnreadableXmlIsNotTreatedAsANewInstallation() {
        Fixture().use { f ->
            val file = java.io.File(f.dataDir, "shared_prefs/monica_secure_prefs.xml")
            file.parentFile!!.mkdirs()
            val broken = "<map><broken-encrypted-configuration".toByteArray()
            file.writeBytes(broken)
            val result = SecureStorageStartup.prepare(f.context) as SecureStartupResult.Blocked
            assertEquals("EMPTY_OR_UNREADABLE_STORE", result.failure.reason)
            assertArrayEquals(broken, file.readBytes())
            assertTrue(f.raw.all.isEmpty())
        }
    }

    @Test fun healthyBackgroundStartupDoesNotDependOnAnActivity() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            f.corruptKeyset()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
        }
        Fixture().use { fresh ->
            kotlinx.coroutines.withTimeout(10_000) { SecureStorageStartup.awaitReadyForMaintenance(fresh.context) }
            assertTrue(SecureStorageStartup.readyForMaintenance)
        }
    }

    @Test fun concurrentFirstOpenDoesNotMistakeAnInProgressKeysetForCorruption() {
        Fixture().use { f ->
            val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val start = java.util.concurrent.CountDownLatch(1)
                val tasks = (0 until 4).map {
                    executor.submit<SecurePreferencesStore.Opened> {
                        start.await()
                        SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA)
                    }
                }
                start.countDown()
                tasks.forEach { assertNotNull(it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
                assertEquals(2, f.raw.all.size)
            } finally { executor.shutdownNow() }
        }
    }

}
