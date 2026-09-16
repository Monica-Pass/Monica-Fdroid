package takagi.ru.monica.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi

class BitwardenCipherDeletionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun movingToTrashKeepsTheRemoteCipherRestorable() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = CipherEndpoint()
            val api = api(server)

            assertTrue(api.deleteCipher(AUTHORIZATION, CIPHER_ID).isSuccessful)
            val trashed = api.getCipher(AUTHORIZATION, CIPHER_ID)
            assertTrue("Moving to trash must not permanently delete the remote cipher", trashed.isSuccessful)
            assertNotNull(trashed.body()?.deletedDate)

            val restored = api.restoreCipher(AUTHORIZATION, CIPHER_ID)
            assertTrue(restored.isSuccessful)
            assertNull(restored.body()?.deletedDate)
            assertEquals(CIPHER_ID, api.getCipher(AUTHORIZATION, CIPHER_ID).body()?.id)
        }
    }

    @Test
    fun permanentlyDeletingATrashedCipherUsesTheSupportedEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = CipherEndpoint(initiallyTrashed = true)
            val api = api(server)

            val deleted = api.permanentDeleteCipher(AUTHORIZATION, CIPHER_ID)
            assertTrue("Permanent deletion must succeed, received HTTP ${deleted.code()}", deleted.isSuccessful)
            assertEquals(404, api.getCipher(AUTHORIZATION, CIPHER_ID).code())
        }
    }

    // Contract shared by Bitwarden CiphersController and Vaultwarden's cipher routes:
    // PUT /ciphers/{id}/delete trashes; PUT /restore restores; DELETE /ciphers/{id} purges.
    private class CipherEndpoint(initiallyTrashed: Boolean = false) : Dispatcher() {
        private var exists = true
        private var trashed = initiallyTrashed

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.getHeader("Authorization") != AUTHORIZATION) {
                return MockResponse().setResponseCode(401)
            }
            if (!exists) return MockResponse().setResponseCode(404)
            return when (request.method to request.path) {
                "PUT" to "/api/ciphers/$CIPHER_ID/delete" -> {
                    trashed = true
                    MockResponse().setResponseCode(204)
                }
                "DELETE" to "/api/ciphers/$CIPHER_ID" -> {
                    exists = false
                    MockResponse().setResponseCode(204)
                }
                "PUT" to "/api/ciphers/$CIPHER_ID/restore" -> {
                    trashed = false
                    cipher()
                }
                "GET" to "/api/ciphers/$CIPHER_ID" -> cipher()
                else -> MockResponse().setResponseCode(405)
            }
        }

        private fun cipher(): MockResponse = MockResponse().setBody(
            """{"Id":"$CIPHER_ID","DeletedDate":${if (trashed) "\"2026-09-13T10:00:00Z\"" else "null"}}"""
        )
    }

    private fun api(server: MockWebServer): BitwardenVaultApi = Retrofit.Builder()
        .baseUrl(server.url("/api/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(BitwardenVaultApi::class.java)

    private companion object {
        const val CIPHER_ID = "synthetic-trash-cipher"
        const val AUTHORIZATION = "Bearer synthetic-test-token"
    }
}
