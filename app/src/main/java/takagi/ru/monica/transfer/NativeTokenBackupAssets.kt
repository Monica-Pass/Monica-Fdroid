package takagi.ru.monica.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import takagi.ru.monica.data.ApiTokenMetadata
import takagi.ru.monica.data.NativeApiTokenAssets
import takagi.ru.monica.data.NativeApiTokenUpload
import takagi.ru.monica.data.model.EmbeddedWalletContent

/** Inline bytes stay in the encrypted ZIP; no archive paths or Room ownership are trusted. */
@Serializable
data class NativeTokenBackupAttachment(val fileName: String, val mimeType: String, val size: Long,
    val sha256: String, val contentBase64: String) {
    override fun toString() = "NativeTokenBackupAttachment(redacted)"

    fun upload(): NativeApiTokenUpload = NativeApiTokenUpload(fileName, mimeType, size, sha256) {
        require(size in 0..NativeApiTokenAssets.MAX_BYTES)
        require(contentBase64.length.toLong() <= ((size + 2) / 3) * 4)
        val bytes = java.util.Base64.getDecoder().decode(contentBase64)
        try { NativeApiTokenAssets.validate(bytes, size, sha256) }
        catch (error: Throwable) { bytes.fill(0); throw error }
        object : java.io.ByteArrayInputStream(bytes) { override fun close() { bytes.fill(0); super.close() } }
    }
}

object NativeTokenBackupAssets {
    const val MAX_JSON_BYTES = 96L * 1024 * 1024

    fun validate(tokens: List<NativeTokenBackup>) {
        var total = 0L
        tokens.forEach { token ->
            require(token.attachmentCount == token.attachments.size) { "Native backup attachments are incomplete" }
            require(token.attachments.size <= NativeApiTokenAssets.MAX_COUNT)
            token.attachments.forEach { asset ->
                require(asset.size in 0..NativeApiTokenAssets.MAX_BYTES)
                require(asset.contentBase64.length.toLong() <= ((asset.size + 2) / 3) * 4)
                require(asset.sha256.matches(Regex("[a-fA-F0-9]{64}")))
                require(asset.size <= NativeApiTokenAssets.MAX_BYTES - total) { "Native backup files exceed 64 MiB" }
                total += asset.size
            }
            ApiTokenMetadata.customFields(token.metadata).filter { EmbeddedWalletContent.isMetadata(it.title) }.forEach fieldLoop@{ field ->
                val snapshot = (EmbeddedWalletContent.read(field.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot
                    ?: return@fieldLoop // Future snapshot formats remain opaque and their files are retained.
                snapshot.assets.forEach { asset ->
                    val attachment = token.attachments.singleOrNull { it.fileName == asset.name }
                        ?: error("Native backup is missing a copied wallet attachment or has ambiguous names")
                    require(attachment.size == asset.size && attachment.sha256.equals(asset.sha256, ignoreCase = true)) {
                        "Native backup copied wallet attachment does not match its snapshot"
                    }
                }
            }
        }
    }

    /** Old database archives have no count; new ones must preserve the complete token file. */
    fun expectedTokenCount(manifest: String): Int? {
        val data = Json.parseToJsonElement(manifest).jsonObject
        if (!data.containsKey("nativeTokenCount")) return null
        val count = data["nativeTokenCount"] as? JsonPrimitive
        require(count != null && !count.isString && count.intOrNull != null && count.int >= 0) {
            "Native backup token count is invalid"
        }
        return count.int
    }

    fun validateArchive(expectedCount: Int?, tokenFileCount: Int, tokens: List<NativeTokenBackup>) {
        if (expectedCount == null) return
        require(tokenFileCount in 0..1 && (expectedCount == 0 || tokenFileCount == 1)) {
            "Native backup token file is missing or duplicated"
        }
        require(tokens.size == expectedCount) { "Native backup token list is incomplete" }
    }

    fun decode(content: String): List<NativeTokenBackup> {
        require(content.length <= MAX_JSON_BYTES)
        return Json { ignoreUnknownKeys = true }.decodeFromString<List<NativeTokenBackup>>(content).also(::validate)
    }
}
