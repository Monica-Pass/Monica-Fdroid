package takagi.ru.monica.bitwarden.service

import java.io.IOException
import kotlinx.coroutines.CancellationException
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey

/** Resolve the current per-item key on the IO dispatcher, only when attachment bytes are needed. */
internal object BitwardenAttachmentKeyResolver {
    suspend fun resolve(
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        cipherId: String,
        vaultKey: SymmetricCryptoKey
    ): SymmetricCryptoKey {
        val response = try {
            vaultApi.getCipher("Bearer $accessToken", cipherId)
        } catch (error: IOException) {
            throw AttachmentError.NetworkError(null, error)
        }
        if (!response.isSuccessful) throw AttachmentError.NetworkError(response.code())
        val cipher = response.body()?.takeIf { it.id == cipherId }
            ?: throw AttachmentError.InvalidRemoteData
        return resolve(cipher, vaultKey)
    }

    /** Always return an owned key. A failed item-key unwrap must never fall back to the vault key. */
    fun resolve(cipher: CipherApiResponse, vaultKey: SymmetricCryptoKey): SymmetricCryptoKey {
        val encryptedKey = cipher.key?.takeIf(String::isNotBlank)
        return try {
            if (encryptedKey == null) {
                SymmetricCryptoKey(vaultKey.encKey.copyOf(), vaultKey.macKey.copyOf())
            } else {
                val parsed = BitwardenCrypto.parseCipherString(encryptedKey)
                if (parsed.type != 2) throw AttachmentError.UnsupportedEncryption(parsed.type)
                BitwardenCrypto.decryptSymmetricKey(encryptedKey, vaultKey)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AttachmentError) {
            throw error
        } catch (_: Exception) {
            throw AttachmentError.CryptoError
        }
    }
}
