package takagi.ru.monica.bitwarden.service

import takagi.ru.monica.bitwarden.api.CipherAttachmentApiData
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey

internal object BitwardenAttachmentMetadataDecoder {
    private val cipherStringPattern =
        Regex("^[0-9]+\\.[A-Za-z0-9+/_=-]+\\|[A-Za-z0-9+/_=-]+(?:\\|[A-Za-z0-9+/_=-]+)?$")

    fun decodeForStorage(
        attachments: List<CipherAttachmentApiData>,
        effectiveKey: SymmetricCryptoKey
    ): List<CipherAttachmentApiData> = attachments.map { attachment ->
        attachment.copy(
            fileName = decodeFileName(attachment.fileName, effectiveKey)
        )
    }

    private fun decodeFileName(
        value: String?,
        effectiveKey: SymmetricCryptoKey
    ): String? {
        val candidate = value?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            BitwardenCrypto.decryptToString(candidate, effectiveKey)
        }.getOrElse {
            if (cipherStringPattern.matches(candidate)) null else candidate
        }
    }
}
