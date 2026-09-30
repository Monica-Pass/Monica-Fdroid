package takagi.ru.monica.attachments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.model.EmbeddedWalletContent
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * An all-or-nothing, encrypted staging area for a copied card's assets. No source references
 * remain after preparation. The caller owns the draft until parent save succeeds or is cancelled.
 * Each save target can open the same staged asset without consuming another target's copy.
 */
class EmbeddedWalletAssetDraft private constructor(
    private val directory: File,
    private val key: ByteArray,
    val assets: List<EmbeddedWalletContent.Asset>,
) : Closeable {
    data class Source(
        val displayName: String,
        val mimeType: String,
        val role: EmbeddedWalletContent.AssetRole,
        val expectedSize: Long? = null,
        val expectedSha256: String? = null,
        val writeTo: suspend (OutputStream) -> Unit,
    )

    class CopyFailed(val displayName: String, cause: Throwable) : Exception("Unable to copy wallet asset", cause)

    private var closed = false

    /** Internal streams contain sensitive plaintext; always close after upload or preview. */
    @Synchronized
    fun open(name: String): InputStream {
        check(!closed) { "Wallet draft is closed" }
        require(assets.any { it.name == name })
        val input = File(directory, name).inputStream()
        try {
            val nonce = ByteArray(12)
            var offset = 0
            while (offset < nonce.size) {
                val count = input.read(nonce, offset, nonce.size - offset)
                check(count > 0) { "Truncated wallet draft" }
                offset += count
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return CipherInputStream(input, cipher)
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
        // Only delete files created by this instance; no path supplied by imported metadata.
        assets.forEach { File(directory, it.name).delete() }
        directory.delete()
    }

    companion object {
        suspend fun prepare(cacheRoot: File, sources: List<Source>): EmbeddedWalletAssetDraft {
            var completed: EmbeddedWalletAssetDraft? = null
            try {
                return withContext(Dispatchers.IO) {
            require(cacheRoot.isDirectory || cacheRoot.mkdirs())
            val directory = File(cacheRoot, UUID.randomUUID().toString())
            check(directory.mkdir())
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            val created = mutableListOf<File>()
            val assets = mutableListOf<EmbeddedWalletContent.Asset>()
            try {
                for (source in sources) {
                    coroutineContext.ensureActive()
                    try {
                        val name = "wallet-${UUID.randomUUID()}"
                        val file = File(directory, name).also(created::add)
                        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        file.outputStream().use { output ->
                            output.write(nonce)
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
                            CipherOutputStream(output, cipher).use { encrypted ->
                                val hashed = DigestOutputStream(encrypted, digest)
                                val counted = object : OutputStream() {
                                    override fun write(value: Int) { hashed.write(value); size++ }
                                    override fun write(bytes: ByteArray, off: Int, len: Int) {
                                        hashed.write(bytes, off, len); size += len
                                    }
                                    override fun flush() = hashed.flush()
                                }
                                source.writeTo(counted)
                                counted.flush()
                            }
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        require(source.expectedSize == null || source.expectedSize == size) { "Asset size mismatch" }
                        require(source.expectedSha256.isNullOrBlank() || source.expectedSha256.equals(hash, ignoreCase = true)) {
                            "Asset hash mismatch"
                        }
                        assets += EmbeddedWalletContent.Asset(name, source.displayName, source.mimeType,
                            source.role, size, hash)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        throw CopyFailed(source.displayName, error)
                    }
                }
                coroutineContext.ensureActive()
                EmbeddedWalletAssetDraft(directory, key, assets).also { completed = it }
            } catch (error: Throwable) {
                key.fill(0)
                created.forEach(File::delete)
                directory.delete()
                throw error
            }
                }
            } catch (error: Throwable) {
                // withContext may discard a successful result if the caller is cancelled while
                // dispatching back. The encrypted files still need cleanup in that case.
                completed?.close()
                throw error
            }
        }
    }
}
