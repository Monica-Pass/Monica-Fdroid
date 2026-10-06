package takagi.ru.monica.passkey

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/** Lossless conversion between WebAuthn bytes, UUID and Bitwarden's b64. carrier. */
object PasskeyCredentialIdCodec {
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val base64Pattern = Regex("[A-Za-z0-9_+/\\-]+={0,2}")

    fun normalize(credentialId: String?): String? {
        val raw = credentialId?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val bytes = decode(raw) ?: return raw
        return if (bytes.size == 16) {
            val buffer = ByteBuffer.wrap(bytes)
            UUID(buffer.long, buffer.long).toString()
        } else encode(bytes)
    }

    fun toWebAuthnId(credentialId: String?): String? {
        val raw = credentialId?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return decode(raw)?.let(::encode) ?: raw
    }

    fun toBitwardenCredentialId(credentialId: String?): String? {
        val normalized = normalize(credentialId) ?: return null
        if (uuidPattern.matches(normalized)) return normalized
        return decode(normalized)?.let { "b64." + encode(it) } ?: normalized
    }

    fun isValid(credentialId: String): Boolean = decode(credentialId) != null

    private fun decode(raw: String): ByteArray? {
        if (uuidPattern.matches(raw)) {
            val uuid = UUID.fromString(raw)
            return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits).array()
        }
        val encoded = if (raw.startsWith("b64.", ignoreCase = true)) raw.substring(4) else raw
        if (!base64Pattern.matches(encoded)) return null
        if (encoded.any { it == '+' || it == '/' } && encoded.any { it == '-' || it == '_' }) return null
        val unpadded = encoded.trimEnd('=')
        if (unpadded.length % 4 == 1) return null
        val padding = encoded.length - unpadded.length
        if (padding > 0 && (encoded.length % 4 != 0 || padding != (4 - unpadded.length % 4) % 4)) return null
        val canonical = unpadded.replace('+', '-').replace('/', '_')
        val decoded = runCatching { Base64.getUrlDecoder().decode(canonical) }.getOrNull() ?: return null
        // Reject discarded nonzero pad bits instead of silently changing a registered ID.
        return decoded.takeIf { it.isNotEmpty() && encode(it) == canonical }
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
