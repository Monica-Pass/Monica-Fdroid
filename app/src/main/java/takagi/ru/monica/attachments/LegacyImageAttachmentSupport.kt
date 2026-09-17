package takagi.ru.monica.attachments

import android.content.Context
import android.graphics.BitmapFactory
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments
import takagi.ru.monica.util.ImageManager

/** Keep legacy front/back images independent when copying and self-contained in an MDBX file. */
internal class LegacyImageAttachmentSupport(context: Context) {
    private val imageManager = ImageManager(context)

    data class Copy(val imagePaths: String, val created: List<String>)

    fun representedFileNames(item: SecureItem): Set<String> = runCatching {
        paths(item.imagePaths).mapIndexedNotNull { index, path ->
            if (path.isBlank()) null else fileName(item, index)
        }.toSet()
    }.getOrDefault(emptySet())

    suspend fun contentHash(path: String): String = withBytes(path) { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    suspend fun copy(imagePaths: String): Copy {
        val created = mutableListOf<String>()
        try {
            val copied = paths(imagePaths).map { path ->
                if (path.isBlank()) "" else withBytes(path) { bytes ->
                    checkNotNull(imageManager.saveImageBytes(bytes)).also { created += it }
                }
            }
            return Copy(if (copied.isEmpty()) "" else Json.encodeToString(copied), created)
        } catch (error: Exception) {
            withContext(NonCancellable) { imageManager.deleteImages(created) }
            throw error
        }
    }

    suspend fun persistInMdbx(item: SecureItem, facade: AttachmentFacade) {
        if (item.mdbxDatabaseId == null) return
        for ((index, path) in paths(item.imagePaths).withIndex()) {
            if (path.isBlank()) continue
            withBytes(path) { bytes ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                    owner = AttachmentOwner.secureItem(item.id), source = AttachmentSource.LOCAL,
                    fileName = fileName(item, index), mimeType = options.outMimeType ?: "application/octet-stream", bytes = bytes,
                    isPlusActivated = true
                ))
            }
        }
        facade.mirrorAttachmentsForOwner(AttachmentOwner.secureItem(item.id))
    }

    /** Called after an MDBX owner and its attachment rows have been hydrated. No metadata mutation. */
    suspend fun restoreMissing(item: SecureItem, facade: AttachmentFacade) {
        val missing = paths(item.imagePaths).withIndex().filter { it.value.isNotBlank() && !imageManager.imageExists(it.value) }
        if (missing.isEmpty()) return
        val attachments = facade.list(AttachmentOwner.secureItem(item.id))
        for ((index, path) in missing) {
            val attachment = attachments.firstOrNull { it.fileName == fileName(item, index) } ?: continue
            val bytes = facade.readAttachmentBytes(attachment.id, 25 * 1024 * 1024)
            try { check(imageManager.restoreMissingImage(path, bytes)) } finally { bytes.fill(0) }
        }
    }

    suspend fun rollback(copy: Copy) = imageManager.deleteImages(copy.created)

    private suspend fun <T> withBytes(path: String, block: suspend (ByteArray) -> T): T {
        val bytes = checkNotNull(imageManager.readImageBytes(path)) { "Source image is unavailable" }
        try { return block(bytes) } finally { bytes.fill(0) }
    }

    private fun fileName(item: SecureItem, index: Int): String =
        KeePassSecureItemPhotoAttachments.managedFileNames(item.itemType).elementAtOrNull(index)
            ?: "Monica_Image_${index + 1}.jpg"

    companion object {
        fun paths(value: String): List<String> = if (value.isBlank()) emptyList() else Json.decodeFromString(value)
    }
}
