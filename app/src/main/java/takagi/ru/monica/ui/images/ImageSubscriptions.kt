package takagi.ru.monica.ui.images

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import takagi.ru.monica.ui.cardwallet.CardFaceImageProcessor

@Serializable enum class ImageSourceKind { ICON, CARD }
@Serializable data class SubscribedImage(val name: String, val url: String)
@Serializable data class ImageSubscription(
    val id: String = UUID.randomUUID().toString(), val name: String, val url: String,
    val kind: ImageSourceKind, val images: List<SubscribedImage>, val updatedAt: Long = System.currentTimeMillis()
)

/** Public image feeds only. Never attach vault credentials or send search text to a source. */
internal object ImageFeedParser {
    const val MAX_ITEMS = 3000
    fun url(value: String, base: String? = null): String {
        val parsed = (base?.toHttpUrlOrNull()?.resolve(value) ?: value.toHttpUrlOrNull())
            ?: error("Invalid URL")
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty()) { "HTTPS required" }
        return parsed.newBuilder().fragment(null).build().toString()
    }
    fun parse(text: String, base: String): Pair<String, List<SubscribedImage>> {
        val root = Json.parseToJsonElement(text)
        val obj = root as? JsonObject
        val entries = (root as? JsonArray) ?: listOf("icons", "images", "items").firstNotNullOfOrNull { obj?.get(it) as? JsonArray }
            ?: error("Expected an image list")
        require(entries.size in 1..MAX_ITEMS)
        fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        val images = entries.map { item ->
            val row = item as? JsonObject
            val value = (item as? JsonPrimitive)?.contentOrNull
                ?: row?.string("url") ?: row?.string("src") ?: error("Missing image URL")
            val resolved = url(value, base)
            SubscribedImage((row?.string("name") ?: row?.string("title") ?: resolved.toHttpUrlOrNull()!!.pathSegments.last()).take(160), resolved)
        }.distinctBy { it.url }
        return (obj?.string("name") ?: obj?.string("title") ?: "").take(160) to images
    }
}

internal class ImageSubscriptionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "image-subscriptions.json"))
    private val json = Json { ignoreUnknownKeys = true }
    companion object { private val lock = Mutex() }
    suspend fun read(): List<ImageSubscription> = withContext(Dispatchers.IO) { lock.withLock { readLocked() } }
    private fun readLocked(): List<ImageSubscription> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        return json.decodeFromString(file.openRead().bufferedReader().use { it.readText() })
    }
    suspend fun save(source: ImageSubscription) = mutate { current ->
        val existing = current.firstOrNull { it.id == source.id || (it.url == source.url && it.kind == source.kind) }
        if (existing == null) { require(current.size < 50); current + source }
        else current.map { if (it.id == existing.id) source.copy(id = existing.id) else it }
    }
    suspend fun remove(id: String) = mutate { it.filterNot { source -> source.id == id } }
    private suspend fun mutate(change: (List<ImageSubscription>) -> List<ImageSubscription>) = withContext(Dispatchers.IO) {
        lock.withLock {
            // A malformed stored catalog is not an empty catalog: propagate, never overwrite it.
            val data = json.encodeToString(change(readLocked())).toByteArray()
            val stream = file.startWrite()
            try { stream.write(data); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }
}

internal class ImageSourceClient(private val context: Context, private val httpClient: OkHttpClient = client) {
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        private const val MAX_BYTES = 12L * 1024 * 1024
        private val cacheLock = Mutex()
    }
    private val cacheDir get() = File(context.cacheDir, "subscribed-images").apply { mkdirs() }
    private fun cacheFile(url: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$key.image")
    }
    private data class Download(val bytes: ByteArray, val type: String, val finalUrl: String)
    private fun download(input: String): Download {
        var target = ImageFeedParser.url(input)
        repeat(6) {
            httpClient.newCall(Request.Builder().url(target).header("Accept", "application/json,image/png,image/jpeg,image/webp").build()).execute().use { response ->
                if (response.code in 300..399) {
                    target = ImageFeedParser.url(response.header("Location") ?: error("Invalid redirect"), target)
                } else {
                    check(response.isSuccessful) { "Source unavailable" }
                    val body = response.body ?: error("Empty response")
                    val type = body.contentType()?.toString().orEmpty()
                    val max = if (type.startsWith("image/")) MAX_BYTES else 2L * 1024 * 1024
                    require(body.contentLength() <= max)
                    val bytes = body.byteStream().use { CardFaceImageProcessor.readBoundedBytes(it, max) } ?: error("Response too large")
                    return Download(bytes, type, target)
                }
            }
        }
        error("Too many redirects")
    }
    suspend fun load(url: String, name: String, kind: ImageSourceKind, id: String = UUID.randomUUID().toString()): ImageSubscription = withContext(Dispatchers.IO) {
        val normalized = ImageFeedParser.url(url.trim())
        val result = download(normalized)
        val parsed = if (result.type.startsWith("image/") || isRaster(result.bytes)) {
            // Validate the image before accepting a direct-image subscription.
            val bitmap = decode(result.bytes)
            bitmap.recycle()
            "" to listOf(SubscribedImage(name.ifBlank { Uri.parse(normalized).lastPathSegment ?: "Image" }, normalized))
        } else ImageFeedParser.parse(result.bytes.toString(Charsets.UTF_8), result.finalUrl)
        ImageSubscription(id, name.trim().ifBlank { parsed.first.ifBlank { Uri.parse(normalized).host ?: "Images" } }.take(160), normalized, kind, parsed.second)
    }
    private fun isRaster(bytes: ByteArray) = bytes.size > 12 && (
        (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) ||
        (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) ||
        bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP")
    private suspend fun decode(bytes: ByteArray): Bitmap {
        val temporary = File.createTempFile("decode-", ".image", cacheDir)
        try {
            temporary.writeBytes(bytes)
            return CardFaceImageProcessor.decode(context, Uri.fromFile(temporary)).getOrThrow()
        } finally { temporary.delete() }
    }
    suspend fun image(url: String): Bitmap = withContext(Dispatchers.IO) {
        val normalized = ImageFeedParser.url(url)
        val file = cacheFile(normalized)
        val cached = cacheLock.withLock { if (file.exists()) file.readBytes() else null }
        if (cached != null) {
            try { return@withContext decode(cached) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { cacheLock.withLock { file.delete() } }
        }
        val bytes = download(normalized).bytes
        val bitmap = decode(bytes)
        cacheLock.withLock {
            // Only evict disposable source cache. Imported icons / card attachments live elsewhere.
            val files = cacheDir.listFiles()?.filter { it.extension == "image" && !it.name.startsWith("decode-") }.orEmpty().sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            for (old in files) { if (total + bytes.size <= 48L * 1024 * 1024) break; total -= old.length(); old.delete() }
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); bitmap.recycle(); throw error }
        }
        bitmap
    }
}
