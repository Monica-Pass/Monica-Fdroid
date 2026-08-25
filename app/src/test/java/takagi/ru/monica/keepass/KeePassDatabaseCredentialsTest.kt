package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Meta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import takagi.ru.monica.utils.KeePassCodecSupport

class KeePassDatabaseCredentialsTest {
    @Test
    fun `password replacement rewrites native credentials and preserves content`() {
        val original = database(passwordCredentials("old-password"))
        val oldMasterSeed = original.header.masterSeed
        val replacement = passwordCredentials("new-password")

        val updated = KeePassDatabaseCredentialEditor.replace(
            database = original,
            credentials = replacement,
            nowProvider = { Instant.parse("2026-08-17T17:00:00Z") }
        )
        val bytes = encode(updated)

        val reopened = decode(bytes, replacement)
        assertEquals("Credential fixture", reopened.content.meta.name)
        assertEquals("keep-me", reopened.content.meta.customData.getValue("plugin").value)
        assertEquals(Instant.parse("2026-08-17T17:00:00Z"), reopened.content.meta.masterKeyChanged)
        assertNotEquals(oldMasterSeed, reopened.header.masterSeed)
        assertCannotDecode(bytes, passwordCredentials("old-password"))
    }

    @Test
    fun `combined password and key file replacement supports reopening`() {
        val original = database(passwordCredentials("old-password"))
        val keyFile = ByteArray(32) { index -> (index + 1).toByte() }
        val replacement = Credentials.from(EncryptedValue.fromString("new-password"), keyFile)

        val bytes = encode(KeePassDatabaseCredentialEditor.replace(original, replacement))

        assertEquals("Credential fixture", decode(bytes, replacement).content.meta.name)
        assertCannotDecode(bytes, passwordCredentials("new-password"))
        assertCannotDecode(bytes, Credentials.from(keyFile))
    }

    @Test
    fun `key-file-only replacement is valid`() {
        val original = database(passwordCredentials("old-password"))
        val keyFile = "arbitrary-key-file-content".encodeToByteArray()
        val replacement = Credentials.from(keyFile)

        val bytes = encode(KeePassDatabaseCredentialEditor.replace(original, replacement))

        assertEquals("Credential fixture", decode(bytes, replacement).content.meta.name)
        assertCannotDecode(bytes, passwordCredentials("old-password"))
    }

    @Test
    fun `service verifies and writes new credentials before committing registration`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()
        val method = source
            .substringAfter("internal suspend fun changeMasterCredentials")
            .substringBefore("suspend fun syncRemoteDatabase")

        val prepare = method.indexOf("credentialTransitionStore.prepare")
        val preview = method.indexOf("val previewBytes = encodeDatabase")
        val verification = method.indexOf("decodeDatabase(", startIndex = preview)
        val write = method.indexOf("writeDatabase(", startIndex = verification)
        val registration = method.indexOf("dao.updateDatabase", startIndex = write)
        val clear = method.indexOf("credentialTransitionStore.clear", startIndex = registration)

        assertTrue(prepare >= 0)
        assertTrue(preview > prepare)
        assertTrue(verification > preview)
        assertTrue(write > verification)
        assertTrue(registration > write)
        assertTrue(clear > registration)
    }

    private fun database(credentials: Credentials): KeePassDatabase {
        val base = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(
                generator = "Fixture",
                name = "Credential fixture",
                customData = mapOf("plugin" to CustomDataValue("keep-me"))
            ),
            credentials = credentials
        )
        val salt = when (val kdf = base.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        return base.copy(
            header = base.header.copy(
                kdfParameters = KdfParameters.Aes(rounds = 100U, seed = salt)
            )
        )
    }

    private fun passwordCredentials(password: String): Credentials =
        Credentials.from(EncryptedValue.fromString(password))

    private fun encode(database: KeePassDatabase): ByteArray = ByteArrayOutputStream().use { output ->
        database.encode(output, cipherProviders = KeePassCodecSupport.cipherProviders)
        output.toByteArray()
    }

    private fun decode(bytes: ByteArray, credentials: Credentials): KeePassDatabase =
        KeePassDatabase.decode(
            ByteArrayInputStream(bytes),
            credentials,
            cipherProviders = KeePassCodecSupport.cipherProviders
        )

    private fun assertCannotDecode(bytes: ByteArray, credentials: Credentials) {
        try {
            decode(bytes, credentials)
            fail("Expected credentials to be rejected")
        } catch (error: Throwable) {
            assertTrue(error.message.orEmpty().isNotBlank() || error.cause != null)
        }
    }

    private fun projectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
