package takagi.ru.monica.attachments

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.api.AttachmentDownloadInfo
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.BitwardenAttachmentKeyResolver
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.PasswordViewModel

/** Real HTTP -> cipher/item/file keys -> encrypted storage -> Room -> password copy/move. */
@RunWith(AndroidJUnit4::class)
class BitwardenAttachmentTransferTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = PasswordDatabase.getDatabase(context)
    private val facade get() = AttachmentContainer.facade(context)
    private val attachmentRepository get() = AttachmentContainer.repository(context)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun officialAttachmentCanBePreviewedAndCopiedWithoutChangingTheSource() = runBlocking {
        scenario {
            val source = insertEntry(remote = true)
            val target = insertEntry(remote = false)
            val bytes = ByteArray(65_711) { (it * 31).toByte() }
            val attachmentId = addAttachment(source.id, "attachment", bytes)
            assertArrayEquals(bytes, facade.readAttachmentBytes(attachmentId, 100_000, bwContext))
            assertEquals(1, facade.cloneAttachmentsToNewParent(source.id, target.id))
            val original = facade.listByPassword(source.id).single()
            val copy = facade.listByPassword(target.id).single()
            assertEquals(AttachmentSource.BITWARDEN, original.sourceEnum)
            assertEquals(AttachmentSource.LOCAL, copy.sourceEnum)
            assertNotEquals(original.localPath, copy.localPath)
            assertNull(copy.bitwardenAttachmentId)
            assertArrayEquals(bytes, facade.readAttachmentBytes(copy.id, 100_000))
            assertArrayEquals(bytes, facade.readAttachmentBytes(original.id, 100_000))
        }
    }

    @Test
    fun corruptedSecondAttachmentRollsBackPartialCopiesAndPreservesTheSource() = runBlocking {
        scenario {
            val source = insertEntry(remote = true)
            val target = insertEntry(remote = false)
            val good = addAttachment(source.id, "good", "synthetic good attachment".toByteArray())
            val bad = addAttachment(source.id, "bad", ByteArray(50_000) { it.toByte() }, damaged = true)
            facade.ensureDownloaded(good, bwContext)
            val failure = runCatching {
                facade.cloneAttachmentsToNewOwner(
                    AttachmentOwner.password(source.id), AttachmentOwner.password(target.id), bwContext
                )
            }.exceptionOrNull()
            assertSame(AttachmentError.CryptoError, failure)
            assertTrue(facade.listByPassword(target.id).isEmpty())
            assertEquals(2, facade.listByPassword(source.id).size)
            assertEquals(AttachmentDownloadState.FAILED, facade.getById(bad)?.downloadStateEnum)
            assertNotNull(database.passwordEntryDao().getPasswordEntryById(source.id))
            assertNull(database.bitwardenPendingOperationDao().findActiveDeleteByCipher(vaultId, cipherId))
        }
    }

    @Test
    fun failedMoveRemovesTheIncompleteLocalCopyAndKeepsTheRemoteSource() = runBlocking {
        scenario {
            val source = insertEntry(remote = true)
            val attachmentId = addAttachment(source.id, "unreadable", ByteArray(128), damaged = true)
            assertSame(AttachmentError.CryptoError, runCatching {
                facade.ensureAttachmentsReadyForTransfer(source.id, bwContext)
            }.exceptionOrNull())
            // Also check the ViewModel's last line of defense if called after a failed preflight.
            val moved = viewModel().moveBitwardenPasswordToMonicaLocal(source, null)
            assertTrue(moved.isFailure)
            assertEquals(listOf(source.id), repository.getAllPasswordEntries().first().filter { it.title.startsWith(prefix) }.map { it.id })
            assertNotNull(facade.getById(attachmentId))
            assertNull(database.bitwardenPendingOperationDao().findActiveDeleteByCipher(vaultId, cipherId))
        }
    }

    @Test
    fun successfulMoveKeepsAttachmentBytesBeforeQueuingSourceDeletion() = runBlocking {
        scenario {
            val source = insertEntry(remote = true)
            val bytes = "synthetic attachment retained after move".toByteArray()
            addAttachment(source.id, "attachment", bytes)
            assertEquals(1, facade.ensureAttachmentsReadyForTransfer(source.id, bwContext))
            val targetId = viewModel().moveBitwardenPasswordToMonicaLocal(source, null).getOrThrow()
            val copied = facade.listByPassword(targetId).single()
            assertEquals(AttachmentSource.LOCAL, copied.sourceEnum)
            assertArrayEquals(bytes, facade.readAttachmentBytes(copied.id, 1_024))
            assertNull(database.passwordEntryDao().getPasswordEntryById(source.id))
            assertNotNull(database.bitwardenPendingOperationDao().findActiveDeleteByCipher(vaultId, cipherId))
        }
    }

    @Test
    fun cancellationReturnsDownloadToPendingInsteadOfLeavingItStuck() = runBlocking {
        scenario {
            val source = insertEntry(remote = true)
            val id = addAttachment(source.id, "cancelled", ByteArray(16))
            val cancelled = bwContext.copy(wrappingKeyProvider = { throw CancellationException("synthetic cancellation") })
            assertTrue(runCatching { facade.ensureDownloaded(id, cancelled) }.exceptionOrNull() is CancellationException)
            assertEquals(AttachmentDownloadState.PENDING, facade.getById(id)?.downloadStateEnum)
            assertNotNull(database.passwordEntryDao().getPasswordEntryById(source.id))
        }
    }

    private suspend fun scenario(block: suspend Fixture.() -> Unit) {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                fixture.initialize()
                fixture.block()
            } finally {
                fixture.cleanup()
            }
        }
    }

    private inner class Fixture(private val server: MockWebServer) {
        val prefix = "attachment-regression-${UUID.randomUUID()}"
        val cipherId = "$prefix-cipher"
        val repository = PasswordRepository(database.passwordEntryDao())
        var vaultId = 0L
        private val ownerIds = mutableSetOf<Long>()
        private var model: PasswordViewModel? = null
        private val vaultKey = key(1)
        private val itemKey = key(65)
        private val fileKey = key(129)
        private val metadata = ConcurrentHashMap<String, AttachmentDownloadInfo>()
        private val payloads = ConcurrentHashMap<String, ByteArray>()
        private val client = OkHttpClient()
        private val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(BitwardenVaultApi::class.java)
        val bwContext = AttachmentFacade.BitwardenContext(
            api, client, "synthetic-attachment-token", cipherId, vaultKey, true,
            wrappingKeyProvider = { BitwardenAttachmentKeyResolver.resolve(api, "synthetic-attachment-token", cipherId, vaultKey) }
        )

        suspend fun initialize() {
            val endpoint = server.url("/").toString()
            vaultId = database.bitwardenVaultDao().insert(BitwardenVault(
                email = "$prefix@example.invalid", accountKey = prefix,
                serverUrl = endpoint, identityUrl = endpoint, apiUrl = endpoint
            ))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path == "/ciphers/$cipherId" -> MockResponse().setBody(json.encodeToString(CipherApiResponse(
                            id = cipherId, key = BitwardenCrypto.encrypt(itemKey.encKey + itemKey.macKey, vaultKey)
                        )))
                        path.startsWith("/ciphers/$cipherId/attachment/") -> metadata[path.substringAfterLast('/')]?.let {
                            MockResponse().setBody(json.encodeToString(it))
                        } ?: MockResponse().setResponseCode(404)
                        path.startsWith("/payload/") -> payloads[path.substringAfterLast('/')]?.let {
                            // Signed blob URLs must not receive the account bearer token.
                            if (request.getHeader("Authorization") != null) MockResponse().setResponseCode(403)
                            else MockResponse().setBody(Buffer().write(it))
                        } ?: MockResponse().setResponseCode(404)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }

        suspend fun insertEntry(remote: Boolean): PasswordEntry {
            val row = PasswordEntry(
                title = "$prefix-${if (remote) "source" else "target"}", website = "https://example.invalid",
                username = "synthetic-account", password = SecurityManager(context).encryptData("synthetic-password"),
                bitwardenVaultId = vaultId.takeIf { remote }, bitwardenCipherId = cipherId.takeIf { remote }
            )
            val id = database.passwordEntryDao().insertPasswordEntry(row)
            ownerIds += id
            return row.copy(id = id)
        }

        suspend fun addAttachment(ownerId: Long, id: String, bytes: ByteArray, damaged: Boolean = false): Long {
            val encryptedKey = BitwardenCrypto.encrypt(fileKey.encKey + fileKey.macKey, itemKey)
            val url = server.url("/payload/$id").toString()
            metadata[id] = AttachmentDownloadInfo(id = id, url = url, key = encryptedKey)
            payloads[id] = officialPayload(bytes, fileKey).also {
                if (damaged) it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            return attachmentRepository.insert(Attachment(
                parentPasswordId = ownerId, source = AttachmentSource.BITWARDEN.name,
                fileName = "synthetic-$id.txt", mimeType = "text/plain", sizeBytes = bytes.size.toLong(),
                bitwardenAttachmentId = id, bitwardenFileKeyEnc = encryptedKey, bitwardenUrl = url,
                downloadState = AttachmentDownloadState.PENDING.name,
                createdAt = metadata.size.toLong(), updatedAt = 1
            ))
        }

        fun viewModel(): PasswordViewModel = model ?: PasswordViewModel(
            repository, SecurityManager(context), context = context
        ).also { model = it }

        suspend fun cleanup() {
            model?.viewModelScope?.cancel()
            ownerIds += repository.getAllPasswordEntries().first().filter { it.title.startsWith(prefix) }.map { it.id }
            ownerIds.forEach { id ->
                facade.purgeByPassword(id)
                database.passwordEntryDao().deletePasswordEntryById(id)
            }
            if (vaultId > 0) {
                database.bitwardenPendingOperationDao().deleteByVault(vaultId)
                database.bitwardenSyncRawEntryRecordDao().deleteByVault(vaultId)
                database.openHelper.writableDatabase.execSQL("DELETE FROM bitwarden_vaults WHERE id = ?", arrayOf(vaultId))
            }
            vaultKey.clear()
            itemKey.clear()
            fileKey.clear()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun key(start: Int) = SymmetricCryptoKey(ByteArray(32) { (it + start).toByte() }, ByteArray(32) { (it + start + 32).toByte() })

    private fun officialPayload(plaintext: ByteArray, key: SymmetricCryptoKey): ByteArray {
        val iv = ByteArray(16) { it.toByte() }
        val ciphertext = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
            doFinal(plaintext)
        }
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.macKey, "HmacSHA256"))
            update(iv)
            doFinal(ciphertext)
        }
        return byteArrayOf(2) + iv + mac + ciphertext
    }
}
