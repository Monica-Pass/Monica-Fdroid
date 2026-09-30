package takagi.ru.monica.data.model

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomFieldDraft

/** Independent content, carried by the existing encrypted custom-field transports. */
object PasswordContentBlocks {
    const val PREFIX = "monica.content.block."
    private const val CHUNK_SIZE = 1600 // Below Bitwarden's per-field limit, without splitting Unicode.
    private const val MAX_BYTES = 256 * 1024
    enum class Kind { API_KEY, API_TOKEN, SSH_KEY, GPG_KEY, QR_CODE }

    data class Block(val id: String, val raw: JsonObject) {
        val kind: Kind get() = Kind.valueOf(raw.getValue("kind").jsonPrimitive.content)
        val title: String get() = raw.getValue("title").jsonPrimitive.content
        val data: JsonObject get() = raw.getValue("data").jsonObject
        fun value(key: String): String = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun edited(title: String, patch: Map<String, String>): Block = copy(raw = JsonObject(raw + mapOf(
            "title" to JsonPrimitive(title), "data" to JsonObject(data + patch.mapValues { JsonPrimitive(it.value) }))))
    }
    data class Stored(val token: String, val block: Block?)
    fun token(id: String) = "BLOCK:$id"
    fun owns(title: String) = title.startsWith(PREFIX)
    fun create(kind: Kind): Block {
        val id = UUID.randomUUID().toString()
        return Block(id, buildJsonObject {
            put("version", 1); put("id", id); put("kind", kind.name); put("title", "")
            put("data", buildJsonObject { })
        })
    }

    /** Damaged, duplicate and future blocks remain visible/read-only, never silently replaced. */
    fun read(fields: List<CustomFieldDraft>): List<Stored> = fields.filter { owns(it.title) }
        .map { it.title.removePrefix(PREFIX).substringBefore('.') }.distinct().map { id ->
            Stored(token(id), runCatching {
                require(UUID.fromString(id).toString() == id)
                val header = Json.parseToJsonElement(fields.single { it.title == PREFIX + id }.value).jsonObject
                require(header["version"]?.jsonPrimitive?.int == 1)
                require(header["encoding"]?.jsonPrimitive?.content == "base64")
                val count = header.getValue("parts").jsonPrimitive.int
                require(count in 1..220)
                val encoded = (0 until count).joinToString("") { index ->
                    fields.single { it.title == chunkName(id, index) }.value.also { require(it.length <= CHUNK_SIZE) }
                }
                require(fields.count { it.title.startsWith("$PREFIX$id.") } == count)
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.size <= MAX_BYTES && digest(bytes) == header.getValue("sha256").jsonPrimitive.content)
                val raw = Json.parseToJsonElement(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()).jsonObject
                require(raw["version"]?.jsonPrimitive?.int == 1 && raw["id"]?.jsonPrimitive?.content == id)
                require(raw["title"]?.jsonPrimitive?.isString == true)
                Block(id, raw).also { block ->
                    block.kind; block.data
                    // A future shape for a known editor field must not be overwritten as an empty string.
                    editableKeys(block.kind).forEach { key ->
                        require(block.data[key] == null || (block.data[key] is JsonPrimitive && block.data[key]!!.jsonPrimitive.isString))
                    }
                    if (block.kind == Kind.QR_CODE) {
                        listOf("mode", "templateVersion").forEach { key ->
                            require(block.data[key] == null || (block.data[key] is JsonPrimitive && block.data[key]!!.jsonPrimitive.isString))
                        }
                        require(PasswordQrTemplate.supported(block))
                    }
                }
            }.getOrNull())
        }

    fun put(fields: List<CustomFieldDraft>, block: Block): List<CustomFieldDraft> {
        val existing = read(fields).firstOrNull { it.token == token(block.id) }
        require(existing == null || existing.block != null) { "Unreadable content must be preserved" }
        val bytes = block.raw.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Content exceeds 256 KiB" }
        val chunks = Base64.getEncoder().encodeToString(bytes).chunked(CHUNK_SIZE)
        val headerName = PREFIX + block.id
        val oldHeader = fields.firstOrNull { it.title == headerName }?.let { Json.parseToJsonElement(it.value).jsonObject }.orEmpty()
        val header = JsonObject(oldHeader + mapOf("version" to JsonPrimitive(1), "encoding" to JsonPrimitive("base64"),
            "parts" to JsonPrimitive(chunks.size), "sha256" to JsonPrimitive(digest(bytes))))
        val replacement = listOf(headerName to header.toString()) + chunks.mapIndexed { i, text -> chunkName(block.id, i) to text }
        val result = fields.filterNot { it.title == headerName || it.title.startsWith("$headerName.") }.toMutableList()
        replacement.forEach { (title, value) ->
            val old = fields.firstOrNull { it.title == title }
            result += old?.copy(value = value, isProtected = true) ?: CustomFieldDraft(
                id = CustomFieldDraft.nextTempId(result.map { it.id }), title = title, value = value, isProtected = true)
        }
        require(read(result).first { it.token == token(block.id) }.block != null)
        return result
    }
    fun remove(fields: List<CustomFieldDraft>, token: String): List<CustomFieldDraft> {
        val stored = read(fields).single { it.token == token }
        require(stored.block != null) { "Unreadable content must be preserved" }
        val name = PREFIX + stored.block.id
        return fields.filterNot { it.title == name || it.title.startsWith("$name.") }.map {
            if (it.title == EntryContentFields.ORDER) it.copy(value = it.value.split(',').filterNot { key -> key == token }.joinToString(",")) else it
        }
    }
    fun editableKeys(kind: Kind): List<String> = when (kind) {
        Kind.API_KEY -> listOf("key", "url", "notes")
        Kind.API_TOKEN -> listOf("provider", "api_base", "token", "notes")
        Kind.SSH_KEY -> listOf("algorithm", "keySize", "format", "publicKeyOpenSsh", "privateKeyOpenSsh", "fingerprintSha256", "comment", "notes")
        Kind.GPG_KEY -> listOf("publicKey", "privateKey", "fingerprint", "userId", "notes")
        Kind.QR_CODE -> listOf("content", "notes")
    }
    private fun chunkName(id: String, index: Int) = "$PREFIX$id.${index.toString().padStart(4, '0')}"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
