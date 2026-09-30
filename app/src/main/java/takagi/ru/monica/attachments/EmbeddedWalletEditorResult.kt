package takagi.ru.monica.attachments

import android.content.Context
import kotlinx.serialization.json.*
import takagi.ru.monica.attachments.ui.AttachmentPendingDraft
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.util.ImageManager
import java.io.File

data class EmbeddedWalletEditorResult(
    val snapshot: EmbeddedWalletContent.Snapshot,
    val imagePaths: List<String>,
    val attachments: List<AttachmentPendingDraft>,
    val cardFaceBytes: ByteArray? = null,
)

internal suspend fun prepareEmbeddedEdit(context: Context, result: EmbeddedWalletEditorResult,
    staged: EmbeddedWalletCopyService.Prepared?, saved: EmbeddedWalletAccess?): EmbeddedWalletCopyService.Prepared {
    val snapshot = result.snapshot
    val faceName = (snapshot.itemData["cardFace"] as? JsonObject)?.get("imageAttachmentName")?.jsonPrimitive?.contentOrNull
    val retained = snapshot.assets.filter { asset -> when(asset.role) {
        EmbeddedWalletContent.AssetRole.CARD_FACE -> result.cardFaceBytes == null && faceName == asset.name
        EmbeddedWalletContent.AssetRole.FRONT -> snapshot.kind == EmbeddedWalletContent.Kind.ADDRESS || result.imagePaths.getOrNull(0) == asset.name
        EmbeddedWalletContent.AssetRole.BACK -> snapshot.kind == EmbeddedWalletContent.Kind.ADDRESS || result.imagePaths.getOrNull(1) == asset.name
        EmbeddedWalletContent.AssetRole.INLINE_IMAGE -> snapshot.kind == EmbeddedWalletContent.Kind.ADDRESS || asset.name in result.imagePaths
        else -> true
    } }
    val names = retained.map { it.name }.toMutableList()
    val sources = retained.map { asset ->
        EmbeddedWalletAssetDraft.Source(asset.displayName, asset.mimeType, asset.role, asset.size, asset.sha256) { output ->
            if (staged != null) staged.assets.open(asset.name).use { it.copyTo(output) }
            else requireNotNull(saved).copyTo(asset.name, output)
        }
    }.toMutableList()
    val imageManager = ImageManager(context)
    result.imagePaths.forEachIndexed { index, path ->
        if (path.isBlank() || path in names) return@forEachIndexed
        names += path
        sources += EmbeddedWalletAssetDraft.Source("Image ${index + 1}", "image/jpeg",
            if (snapshot.kind == EmbeddedWalletContent.Kind.NOTE) EmbeddedWalletContent.AssetRole.INLINE_IMAGE
            else if (index == 0) EmbeddedWalletContent.AssetRole.FRONT else EmbeddedWalletContent.AssetRole.BACK) { output ->
            val bytes = requireNotNull(imageManager.readImageBytes(path))
            try { output.write(bytes) } finally { bytes.fill(0) }
        }
    }
    result.cardFaceBytes?.let { bytes ->
        names += requireNotNull(faceName)
        sources += EmbeddedWalletAssetDraft.Source(faceName, "image/jpeg", EmbeddedWalletContent.AssetRole.CARD_FACE) { it.write(bytes) }
    }
    result.attachments.forEach { attachment ->
        names += attachment.fileName
        sources += EmbeddedWalletAssetDraft.Source(attachment.fileName,
            context.contentResolver.getType(attachment.uri) ?: "application/octet-stream", EmbeddedWalletContent.AssetRole.ATTACHMENT,
            expectedSize = attachment.sizeBytes.takeIf { it >= 0 }) { output ->
            requireNotNull(context.contentResolver.openInputStream(attachment.uri)).use { it.copyTo(output) }
        }
    }
    val assets = EmbeddedWalletAssetDraft.prepare(File(context.cacheDir, "wallet-copy-drafts"), sources)
    try {
        var updated = snapshot.withAssets(assets.assets)
        if (faceName != null) {
            require(names.count { it == faceName } == 1)
            updated = updated.edited(updated.title, updated.notes, buildJsonObject {
                put("cardFace", buildJsonObject { put("imageAttachmentName", assets.assets[names.indexOf(faceName)].name) })
            })
        }
        if (snapshot.kind == EmbeddedWalletContent.Kind.NOTE) {
            val content = NoteContentCodec.decodeFromItem(snapshot.displayItem()).content
            val remapped = content.replace(Regex("monica-image://([^\\)\\s]+)")) { match ->
                val name = match.groupValues[1]
                require(names.count { it == name } == 1)
                "monica-image://${assets.assets[names.indexOf(name)].name}"
            }
            updated = updated.edited(updated.title, updated.notes, buildJsonObject { put("content", remapped) })
        }
        return EmbeddedWalletCopyService.Prepared(updated, assets)
    } catch (error: Throwable) { assets.close(); throw error }
}
