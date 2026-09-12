package takagi.ru.monica.attachments.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.BitwardenAttachmentKeyResolver

class BitwardenAttachmentKeyResolverTest {
    private val vaultKey = testKey(1)
    private val itemKey = testKey(65)
    private val fileKey = testKey(129)

    @Test
    fun accountKeyIsCopiedForLegacyCiphers() {
        val resolved = BitwardenAttachmentKeyResolver.resolve(CipherApiResponse(id = "cipher"), vaultKey)
        assertEquals(vaultKey, resolved)
        resolved.clear()
        assertArrayEquals(testKey(1).encKey, vaultKey.encKey)
    }

    @Test
    fun resolvesAccountThenItemThenAttachmentKeys() {
        val cipher = CipherApiResponse(id = "cipher", key = wrap(itemKey, vaultKey))
        val encryptedFileKey = wrap(fileKey, itemKey)
        // The old attachment entry point used vaultKey here, which cannot unwrap this file key.
        assertNotNull(runCatching { BitwardenAttachmentCrypto.unwrapAttachmentKey(encryptedFileKey, vaultKey) }.exceptionOrNull())
        val resolvedItemKey = BitwardenAttachmentKeyResolver.resolve(cipher, vaultKey)
        val resolvedFileKey = BitwardenAttachmentCrypto.unwrapAttachmentKey(encryptedFileKey, resolvedItemKey)
        assertEquals(itemKey, resolvedItemKey)
        assertEquals(fileKey, resolvedFileKey)
        resolvedItemKey.clear()
        resolvedFileKey.clear()
        assertArrayEquals(testKey(1).macKey, vaultKey.macKey)
    }

    @Test
    fun rejectsStrippedMacOnItemAndAttachmentKeyEnvelopes() {
        val cipher = CipherApiResponse(id = "cipher", key = wrap(itemKey, vaultKey, authenticated = false))
        assertEquals(AttachmentError.UnsupportedEncryption(0), runCatching {
            BitwardenAttachmentKeyResolver.resolve(cipher, vaultKey)
        }.exceptionOrNull())
        val fileKeyFailure = runCatching {
            BitwardenAttachmentCrypto.unwrapAttachmentKey(wrap(fileKey, itemKey, authenticated = false), itemKey)
        }.exceptionOrNull()
        assertEquals(0, (fileKeyFailure as BitwardenAttachmentCrypto.UnsupportedFormatException).encryptionType)
    }

    @Test
    fun aWrongItemKeyFailsWithoutFallingBackToAccountKey() {
        val cipher = CipherApiResponse(id = "cipher", key = wrap(itemKey, testKey(99)))
        val failure = runCatching { BitwardenAttachmentKeyResolver.resolve(cipher, vaultKey) }.exceptionOrNull()
        assertSame(AttachmentError.CryptoError, failure)
    }

    @Test
    fun obtainsTheCurrentItemKeyFromTheCipherEndpoint() = runBlocking {
        MockWebServer().use { server ->
            val cipher = CipherApiResponse(id = "cipher", key = wrap(itemKey, vaultKey))
            server.enqueue(MockResponse().setBody(Json.encodeToString(cipher)))
            val resolved = BitwardenAttachmentKeyResolver.resolve(api(server), "synthetic-token", "cipher", vaultKey)
            assertEquals(itemKey, resolved)
            val request = server.takeRequest()
            assertEquals("/ciphers/cipher", request.path)
            assertEquals("Bearer synthetic-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun preservesAuthenticationStatusInsteadOfReportingStorageFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val failure = runCatching {
                BitwardenAttachmentKeyResolver.resolve(api(server), "expired-synthetic-token", "cipher", vaultKey)
            }.exceptionOrNull()
            assertEquals(401, (failure as AttachmentError.NetworkError).httpStatus)
        }
    }

    @Test
    fun refusesMetadataForAnotherCipher() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Json.encodeToString(CipherApiResponse(id = "different-cipher"))))
            val failure = runCatching {
                BitwardenAttachmentKeyResolver.resolve(api(server), "synthetic-token", "cipher", vaultKey)
            }.exceptionOrNull()
            assertSame(AttachmentError.InvalidRemoteData, failure)
        }
    }

    private fun api(server: MockWebServer): BitwardenVaultApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
        .build().create(BitwardenVaultApi::class.java)

    private fun testKey(start: Int) = SymmetricCryptoKey(
        ByteArray(32) { (it + start).toByte() }, ByteArray(32) { (it + start + 32).toByte() }
    )

    private fun wrap(key: SymmetricCryptoKey, parent: SymmetricCryptoKey, authenticated: Boolean = true): String {
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(parent.encKey, "AES"), IvParameterSpec(iv))
        }
        val encrypted = cipher.doFinal(key.encKey + key.macKey)
        val b64 = Base64.getEncoder()
        val payload = "${b64.encodeToString(iv)}|${b64.encodeToString(encrypted)}"
        if (!authenticated) return "0.$payload"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(parent.macKey, "HmacSHA256"))
            update(iv)
        }.doFinal(encrypted)
        return "2.$payload|${b64.encodeToString(mac)}"
    }
}
