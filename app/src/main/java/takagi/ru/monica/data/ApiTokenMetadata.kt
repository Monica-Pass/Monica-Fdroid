package takagi.ru.monica.data

import kotlinx.serialization.json.*

/** App fields stay outside the gateway payload, which older CLI clients parse strictly. */
object ApiTokenMetadata {
    const val MAX_BYTES = 64 * 1024
    private const val SCHEMA = "monica.api-token.fields.v1"

    fun empty(): String = "{\"schema\":\"$SCHEMA\"}"

    fun decode(value: String): JsonObject? =
        value.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }?.let(::decodeDraft)

    fun decodeDraft(value: String): JsonObject? = runCatching {
        val fields = Json.parseToJsonElement(value) as? JsonObject ?: return null
        if (fields["schema"]?.jsonPrimitive?.content != SCHEMA) return null
        fields
    }.getOrNull()

    fun notes(value: String, fallback: String): String =
        (decodeDraft(value)?.get("notes") as? JsonPrimitive)?.content ?: fallback

    fun customFields(value: String): List<CustomFieldDraft> =
        (decodeDraft(value)?.get("custom_fields") as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val field = element as? JsonObject ?: return@mapIndexedNotNull null
            CustomFieldDraft(
                id = (field["id"] as? JsonPrimitive)?.longOrNull ?: -(index + 1L),
                title = (field["title"] as? JsonPrimitive)?.content.orEmpty(),
                value = (field["value"] as? JsonPrimitive)?.content.orEmpty(),
                isProtected = (field["protected"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

    fun withNotes(value: String, notes: String): String = update(value, "notes", JsonPrimitive(notes))

    fun withCustomFields(value: String, fields: List<CustomFieldDraft>): String {
        val original = (decodeDraft(value)?.get("custom_fields") as? JsonArray).orEmpty()
            .mapIndexedNotNull { index, element -> (element as? JsonObject)?.let {
                ((it["id"] as? JsonPrimitive)?.longOrNull ?: -(index + 1L)) to it
            } }.toMap()
        return update(value, "custom_fields", JsonArray(fields.map { field ->
            JsonObject(original[field.id].orEmpty() + mapOf(
                "id" to JsonPrimitive(field.id), "title" to JsonPrimitive(field.title),
                "value" to JsonPrimitive(field.value), "protected" to JsonPrimitive(field.isProtected),
            ))
        }))
    }

    private fun update(value: String, key: String, content: JsonElement): String {
        val fields = decodeDraft(value) ?: return value
        return JsonObject(fields + (key to content)).toString()
    }

    fun isValid(value: String): Boolean {
        val decoded = decode(value) ?: return false
        val fields = decoded["custom_fields"] as? JsonArray
        if (decoded.containsKey("custom_fields") && fields == null) return false
        if ((fields?.size ?: 0) > 128) return false
        val ids = fields.orEmpty().mapIndexed { index, element ->
            ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull ?: -(index + 1L)
        }
        if (ids.distinct().size != ids.size) return false
        if (decoded.containsKey("notes") && (decoded["notes"] as? JsonPrimitive)?.isString != true) return false
        return fields.orEmpty().all { element ->
            val field = element as? JsonObject ?: return@all false
            (field["title"] as? JsonPrimitive)?.isString == true &&
                (field["value"] as? JsonPrimitive)?.isString == true &&
                (field["protected"] as? JsonPrimitive)?.booleanOrNull != null
        }
    }
}

/** No generated toString: custom field values can contain secrets. */
class NativeApiTokenExtras(val labelId: String, val assignmentId: String, val payload: String)
