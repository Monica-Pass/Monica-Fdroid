package takagi.ru.monica.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.EmbeddedWalletContent
import java.io.Closeable

/** Read-only attachment access scoped to a copied item inside its parent password. */
class EmbeddedWalletAccess private constructor(
    private val context: Context,
    val snapshot: EmbeddedWalletContent.Snapshot,
    val owner: AttachmentOwner?,
    val bitwardenContext: AttachmentFacade.BitwardenContext?,
    val keepassContext: AttachmentFacade.KeePassContext?,
    private val nativeReader: (suspend (String) -> ByteArray)? = null,
) : Closeable {
    val isNative: Boolean get() = nativeReader != null
    override fun close() { bitwardenContext?.wrappingKey?.clear() }
    suspend fun copyTo(name: String, output: java.io.OutputStream) {
        require(snapshot.assets.any { it.name == name })
        if (nativeReader != null) {
            val bytes = nativeReader.invoke(name)
            try {
                val asset = snapshot.assets.single { it.name == name }
                takagi.ru.monica.data.NativeApiTokenAssets.validate(bytes, asset.size, asset.sha256)
                output.write(bytes)
            } finally { bytes.fill(0) }
            return
        }
        val facade = AttachmentContainer.facade(context)
        val attachment = requireNotNull(facade.list(requireNotNull(owner)).singleOrNull { it.fileName == name })
        facade.copyAttachmentTo(attachment.id, output, bitwardenContext, keepassContext)
    }
    suspend fun image(name: String): Bitmap? = try { withContext(Dispatchers.IO) {
        require(snapshot.assets.any { it.name == name })
        val bytes = if (nativeReader != null) nativeReader.invoke(name) else {
            val facade = AttachmentContainer.facade(context)
            val attachment = facade.list(requireNotNull(owner)).singleOrNull { it.fileName == name } ?: return@withContext null
            facade.readAttachmentBytes(attachment.id, 25 * 1024 * 1024, bitwardenContext, keepassContext)
        }
        try {
            if (nativeReader != null) {
                val asset = snapshot.assets.single { it.name == name }
                takagi.ru.monica.data.NativeApiTokenAssets.validate(bytes, asset.size, asset.sha256)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally { bytes.fill(0) }
    } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (_: Exception) { null }
    companion object {
        fun openNative(context: Context, snapshot: EmbeddedWalletContent.Snapshot,
            reader: suspend (String) -> ByteArray): EmbeddedWalletAccess =
            EmbeddedWalletAccess(context.applicationContext, snapshot, null, null, null, reader)

        suspend fun open(context: Context, entry: PasswordEntry, snapshot: EmbeddedWalletContent.Snapshot): EmbeddedWalletAccess {
            var access: EmbeddedWalletAccess? = null
            try { return withContext(Dispatchers.IO) {
                val owner = AttachmentOwner.password(entry.id)
                val facade = AttachmentContainer.facade(context)
                val kp = entry.keepassDatabaseId?.let { AttachmentFacade.KeePassContext(it, requireNotNull(entry.keepassEntryUuid)) }
                if (kp != null) AttachmentContainer.keepassReconciler(context).reconcile(owner, kp.databaseId, kp.entryUuid)
                val bw = entry.bitwardenVaultId?.let { vaultId ->
                    val vault = requireNotNull(PasswordDatabase.getDatabase(context).bitwardenVaultDao().getVaultById(vaultId))
                    BitwardenRepository.getInstance(context).getAttachmentBitwardenContext(vault, entry.bitwardenCipherId)
                }
                EmbeddedWalletAccess(context.applicationContext, snapshot, owner, bw, kp).also { access = it }
            } } catch (error: Throwable) { access?.close(); throw error }
        }
    }
}
