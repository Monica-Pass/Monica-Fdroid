package takagi.ru.monica.data.model

import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.utils.GpgKeyGenerator
import java.util.Base64

/** The secret uses PasswordEntry.password (encrypted at rest); only public data uses custom fields.
 * Chunking keeps RSA certificates below Bitwarden's per-field length limit.
 * Existing backup, KeePass and MDBX field transports preserve this representation.
 */
object GpgEntryFields {
    const val TYPE = "GPG_KEY"
    const val MARKER = "monica_gpg_type"
    const val PUBLIC_PREFIX = "monica_gpg_public_"
    const val FINGERPRINT = "monica_gpg_fingerprint"
    const val USER_ID = "monica_gpg_user_id"
    private const val ENCODING = "monica_gpg_encoding"
    fun isGpg(fields: Map<String, String>) = fields[MARKER] == TYPE
    fun owns(name: String) = name == MARKER || name == FINGERPRINT || name == USER_ID || name == ENCODING || name.startsWith(PUBLIC_PREFIX)
    fun encode(key: GpgKeyGenerator.Key): List<CustomFieldDraft> = buildList {
        add(CustomFieldDraft(title = MARKER, value = TYPE))
        add(CustomFieldDraft(title = FINGERPRINT, value = key.fingerprint))
        add(CustomFieldDraft(title = USER_ID, value = key.userId))
        // Storage adapters trim field edges. Encoding prevents a chunk boundary from losing
        // an armor line break (including the line break before the CRC).
        add(CustomFieldDraft(title = ENCODING, value = "base64"))
        Base64.getEncoder().encodeToString(key.publicKey.toByteArray(Charsets.UTF_8)).chunked(2000).forEachIndexed { i, part ->
            add(CustomFieldDraft(title = PUBLIC_PREFIX + i.toString().padStart(4, '0'), value = part))
        }
    }
    fun publicKey(fields: Map<String, String>): String {
        val chunks = fields.filterKeys { it.startsWith(PUBLIC_PREFIX) }.toSortedMap()
        val maxEncodedSize = (GpgKeyGenerator.MAX_IMPORT_BYTES + 2) / 3 * 4
        require(chunks.isNotEmpty() && chunks.size <= (maxEncodedSize + 1999) / 2000)
        chunks.keys.forEachIndexed { index, name -> require(name == PUBLIC_PREFIX + index.toString().padStart(4, '0')) }
        val stored = chunks.values.joinToString("")
        require(stored.length <= maxEncodedSize)
        val bytes = when (fields[ENCODING]) {
            "base64" -> Base64.getDecoder().decode(stored)
            null -> stored.toByteArray(Charsets.UTF_8) // Earlier unencoded field representation.
            else -> error("Unsupported GPG field encoding")
        }
        require(bytes.size <= GpgKeyGenerator.MAX_IMPORT_BYTES)
        return bytes.toString(Charsets.UTF_8)
    }
}
