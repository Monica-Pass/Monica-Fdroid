package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import java.util.UUID

/**
 * Portable, independent wallet content inside a password's encrypted custom fields.
 * Asset names resolve against the PASSWORD owner. Source ids, paths and vault credentials
 * never become part of this format. Unknown JSON members survive edits by older clients.
 */
object EmbeddedWalletContent {
    const val VERSION = 1
    const val PREFIX = "monica.content.wallet."
    private val json = Json

    enum class Kind(val itemType: ItemType) {
        BANK_CARD(ItemType.BANK_CARD), DOCUMENT(ItemType.DOCUMENT), ADDRESS(ItemType.BILLING_ADDRESS), NOTE(ItemType.NOTE)
    }

    enum class AssetRole { CARD_FACE, FRONT, BACK, INLINE_IMAGE, ATTACHMENT }

    data class Asset(
        val name: String,
        val displayName: String,
        val mimeType: String,
        val role: AssetRole,
        val size: Long,
        val sha256: String,
    ) {
        init {
            require(name.matches(Regex("wallet-[a-zA-Z0-9-]+")))
            require(size >= 0)
            require(sha256.matches(Regex("[a-f0-9]{64}")))
        }

        fun toJson() = buildJsonObject {
            put("name", name); put("displayName", displayName); put("mimeType", mimeType)
            put("role", role.name); put("size", size); put("sha256", sha256)
        }
    }

    class Snapshot private constructor(val raw: JsonObject) {
        val kind get() = Kind.valueOf(raw.getValue("kind").jsonPrimitive.content)
        val id get() = raw.getValue("id").jsonPrimitive.content
        val title get() = raw.getValue("title").jsonPrimitive.content
        val notes get() = raw.getValue("notes").jsonPrimitive.content
        val itemData get() = raw.getValue("data").jsonObject
        val assets: List<Asset> get() = raw.getValue("assets").jsonArray.map { element ->
            val value = element.jsonObject
            Asset(value.getValue("name").jsonPrimitive.content,
                value.getValue("displayName").jsonPrimitive.content,
                value.getValue("mimeType").jsonPrimitive.content,
                AssetRole.valueOf(value.getValue("role").jsonPrimitive.content),
                value.getValue("size").jsonPrimitive.long,
                value.getValue("sha256").jsonPrimitive.content)
        }

        /** Supplied fields are patched; unsupported fields and envelope extensions are retained. */
        fun edited(title: String, notes: String, data: JsonObject): Snapshot = Snapshot(JsonObject(raw + mapOf(
            "title" to JsonPrimitive(title), "notes" to JsonPrimitive(notes),
            "data" to mergeObjects(itemData, data))))

        fun withFavorite(value: Boolean): Snapshot = Snapshot(JsonObject(raw + ("favorite" to JsonPrimitive(value))))

        fun withAssets(assets: List<Asset>): Snapshot {
            require(assets.map { it.name }.distinct().size == assets.size)
            return Snapshot(JsonObject(raw + ("assets" to JsonArray(assets.map(Asset::toJson)))))
        }

        /** Display-only item: never pass it to a standalone secure-item save/delete operation. */
        fun displayItem(): SecureItem = SecureItem(itemType = kind.itemType, title = title,
            notes = notes, itemData = itemData.toString(),
            isFavorite = raw["favorite"]?.jsonPrimitive?.booleanOrNull ?: false)

        fun encode(): String = raw.toString()

        companion object {
            internal fun fromRaw(raw: JsonObject): Snapshot {
                val result = Snapshot(raw)
                require(raw.getValue("version").jsonPrimitive.int == VERSION)
                require(result.id.isNotBlank())
                result.kind; result.title; result.notes; result.itemData
                require(result.assets.map { it.name }.distinct().size == result.assets.size)
                return result
            }
        }
    }

    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Available(val snapshot: Snapshot) : ReadResult
        /** Keep the original field verbatim; do not replace unsupported or damaged content. */
        data class Unavailable(val original: String) : ReadResult
    }

    fun fieldName(kind: Kind) = PREFIX + kind.name.lowercase()
    fun isMetadata(name: String) = name.startsWith(PREFIX)

    fun create(item: SecureItem, id: String = UUID.randomUUID().toString()): Snapshot {
        val kind = Kind.entries.single { it.itemType == item.itemType }
        return Snapshot.fromRaw(buildJsonObject {
            put("version", VERSION); put("id", id); put("kind", kind.name)
            put("title", item.title); put("notes", item.notes); put("favorite", item.isFavorite)
            put("data", json.parseToJsonElement(item.itemData).jsonObject)
            put("assets", JsonArray(emptyList()))
        })
    }

    fun read(value: String?): ReadResult {
        if (value == null) return ReadResult.Missing
        return runCatching { ReadResult.Available(Snapshot.fromRaw(json.parseToJsonElement(value).jsonObject)) }
            .getOrElse { ReadResult.Unavailable(value) }
    }

    fun put(fields: List<CustomFieldDraft>, snapshot: Snapshot): List<CustomFieldDraft> {
        val name = fieldName(snapshot.kind)
        val previous = fields.filter { it.title == name }
        require(previous.size <= 1) { "Duplicate wallet metadata must be resolved before editing" }
        require(previous.firstOrNull()?.let { read(it.value) !is ReadResult.Unavailable } != false) {
            "Unsupported wallet content must be preserved"
        }
        val field = previous.firstOrNull()?.copy(value = snapshot.encode(), isProtected = true)
            ?: CustomFieldDraft(id = CustomFieldDraft.nextTempId(fields.map { it.id }), title = name,
                value = snapshot.encode(), isProtected = true)
        return if (previous.isEmpty()) fields + field else fields.map { if (it.title == name) field else it }
    }

    private fun mergeObjects(original: JsonObject, patch: JsonObject): JsonObject = JsonObject(
        original + patch.mapValues { (key, value) ->
            val old = original[key]
            when {
                old is JsonObject && value is JsonObject -> mergeObjects(old, value)
                key == "customFields" && old is JsonArray && value is JsonArray -> mergeCustomFields(old, value)
                else -> value
            }
        })
    private fun mergeCustomFields(original: JsonArray, patch: JsonArray): JsonArray {
        val remaining = original.toMutableList()
        val updated = patch.map { field ->
            val label = (field as? JsonObject)?.get("label")
            val index = remaining.indexOfFirst { it is JsonObject && it["label"] == label }
            if (index >= 0 && field is JsonObject) {
                val previous = remaining.removeAt(index) as JsonObject
                mergeObjects(previous, field)
            } else field
        }
        // A field the current editor cannot decode was never offered for deletion.
        val unsupported = remaining.filter { field ->
            val objectField = field as? JsonObject
            val type = objectField?.get("type")?.jsonPrimitive?.contentOrNull
            objectField == null || objectField["label"] == null ||
                (type != null && SecureCustomFieldType.entries.none { it.name == type })
        }
        return JsonArray(updated + unsupported)
    }

}
