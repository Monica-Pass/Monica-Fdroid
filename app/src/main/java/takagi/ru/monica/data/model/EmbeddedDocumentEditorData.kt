package takagi.ru.monica.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomFieldDraft

/** An editor projection, retaining the original JSON for fields this client cannot interpret. */
class EmbeddedDocumentEditorData(val snapshot: EmbeddedWalletContent.Snapshot) {
    private val json = Json { ignoreUnknownKeys = true }
    private val originalFieldContainer = snapshot.itemData["customFields"]
    private val originalFields = originalFieldContainer as? JsonArray ?: JsonArray(emptyList())
    private val supportedFields = originalFields.mapNotNull { raw ->
        val field = raw as? JsonObject ?: return@mapNotNull null
        val type = field["type"]
        if (type != null && (type !is JsonPrimitive || !type.isString || SecureCustomFieldType.entries.none { it.name == type.content })) {
            return@mapNotNull null
        }
        runCatching { json.decodeFromString<SecureCustomField>(raw.toString()) }.getOrNull()?.takeIf { it.isValid() }?.let { raw to it }
    }
    val data: DocumentData = requireNotNull(CardWalletDataCodec.parseDocumentData(JsonObject(
        snapshot.itemData + ("customFields" to JsonArray(supportedFields.map { it.first }))
    ).toString()))
    val customFields: List<CustomFieldDraft> = CardWalletDataCodec.customFieldsToDrafts(data.customFields)
    private val originalsById = customFields.mapIndexed { index, field -> field.id to supportedFields[index].first }.toMap()

    init { require(snapshot.kind == EmbeddedWalletContent.Kind.DOCUMENT) }

    /** Encode changed members, including empty values, without normalizing untouched members. */
    fun edited(title: String, notes: String, favorite: Boolean, current: DocumentData,
        fields: List<CustomFieldDraft>): EmbeddedWalletContent.Snapshot {
        val baseline = Json.parseToJsonElement(CardWalletDataCodec.encodeDocumentData(data)).jsonObject
        val updated = Json.parseToJsonElement(CardWalletDataCodec.encodeDocumentData(current)).jsonObject
        val patch = JsonObject(updated.filter { (key, value) -> key != "customFields" && baseline[key] != value })
        val result = snapshot.edited(title, notes, patch).withFavorite(favorite)
        if (fields == customFields) return result
        require(originalFieldContainer == null || originalFieldContainer is JsonArray) {
            "Unsupported document custom-field container must be preserved"
        }

        val supportedRaw = supportedFields.map { it.first }.toMutableList()
        val unknown = originalFields.filter { !supportedRaw.remove(it) }
        val replacements = fields.filter { it.isValid() }.map { field ->
            if (customFields.firstOrNull { it.id == field.id } == field) return@map originalsById.getValue(field.id)
            val encoded = Json.parseToJsonElement(CardWalletDataCodec.encodeDocumentData(
                data.copy(customFields = CardWalletDataCodec.draftsToCustomFields(listOf(field)))
            )).jsonObject.getValue("customFields").jsonArray.single().jsonObject
            // Draft ids remain stable when a user renames a field; keep its future metadata too.
            JsonObject((originalsById[field.id] as? JsonObject).orEmpty() + encoded)
        }
        return EmbeddedWalletContent.Snapshot.fromRaw(JsonObject(result.raw + ("data" to JsonObject(
            result.itemData + ("customFields" to JsonArray(replacements + unknown))
        ))))
    }
}
