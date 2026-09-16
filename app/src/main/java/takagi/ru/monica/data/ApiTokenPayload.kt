package takagi.ru.monica.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** Native CLI payload. Keep unknown extensions when editing a supported schema. */
object ApiTokenPayload {
    const val NATIVE_TYPE = "api-token"
    const val SCHEMA = "monica.gateway.credential.v1"
    const val APP_SCHEMA = "monica.api-token.v1"
    const val MAX_BYTES = 16 * 1024

    fun decode(value: String): JsonObject? =
        value.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }?.let(::decodeDraft)

    /** Drafts remain editable when validation fails, including an oversized field. */
    fun decodeDraft(value: String): JsonObject? = runCatching {
        val fields = Json.parseToJsonElement(value) as? JsonObject ?: return null
        if (text(fields, "schema") !in setOf(SCHEMA, APP_SCHEMA)) return null
        if (listOf("provider", "api_base", "token").any {
                (fields[it] as? JsonPrimitive)?.isString != true
            }) return null
        if (fields.containsKey("note") && (fields["note"] as? JsonPrimitive)?.isString != true) return null
        fields
    }.getOrNull()

    fun text(fields: JsonObject?, key: String): String =
        (fields?.get(key) as? JsonPrimitive)?.content.orEmpty()

    fun update(value: String, key: String, text: String): String {
        // An unfinished edit may exceed validation limits. Keep its other fields
        // so correcting that input never silently discards extensions or secrets.
        val current = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
            ?.takeIf { ApiTokenPayload.text(it, "schema") in setOf(SCHEMA, APP_SCHEMA) } ?: return value
        val fields = current.toMutableMap()
        fields[key] = JsonPrimitive(text)
        return JsonObject(fields).toString()
    }

    fun empty(): JsonObject = JsonObject(mapOf(
        "schema" to JsonPrimitive(SCHEMA),
        "provider" to JsonPrimitive("gitlab"),
        "api_base" to JsonPrimitive("https://gitlab.com/api/v4/"),
        "note" to JsonPrimitive(""),
        "token" to JsonPrimitive("")
    ))

    fun isValid(value: String): Boolean {
        val fields = decode(value) ?: return false
        if (text(fields, "schema") == APP_SCHEMA) return isValidForStorage(value)
        val token = text(fields, "token")
        val uri = runCatching { URI(text(fields, "api_base")) }.getOrNull() ?: return false
        val provider = text(fields, "provider")
        val note = text(fields, "note")
        val validPath = when (provider) {
            "gitlab" -> uri.rawPath == "/api/v4/"
            "github" -> uri.rawPath in setOf("", "/", "/api/v3/")
            else -> false
        }
        return validPath && text(fields, "api_base").toByteArray(Charsets.UTF_8).size <= 2048 &&
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            token.length in 16..4096 && token.all { it.code in 33..126 } &&
            note.toByteArray(Charsets.UTF_8).size <= 1024 &&
            note.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } &&
            !note.contains(token) && !text(fields, "api_base").contains(token)
    }

    fun isValidName(value: String): Boolean = value.length in 1..64 &&
        value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    fun isValidStorageName(value: String): Boolean = value.isNotBlank() && value.length <= 256 &&
        value.none { it.isISOControl() }

    fun isValidForStorage(value: String): Boolean {
        val fields = decode(value) ?: return false
        val provider = text(fields, "provider")
        val token = text(fields, "token")
        if (provider.isBlank() || provider.length > 128 || provider.any { it.isISOControl() } || token.isBlank()) return false
        val endpoint = text(fields, "api_base")
        if (endpoint.isBlank()) return true
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        return endpoint.length <= 2048 && uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && (token.length < 16 || !endpoint.contains(token))
    }

    /** Retain gateway compatibility unless the user chooses general-purpose fields. */
    fun forStorage(value: String, title: String): String =
        if (text(decode(value), "schema") == SCHEMA && (!isValid(value) || !isValidName(title)))
            update(value, "schema", APP_SCHEMA) else value
}

data class NativeApiTokenSummary(
    val databaseId: Long,
    val entryId: String,
    val collectionId: String,
    val collectionTitle: String,
    val title: String,
    val ancestorCollectionIds: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val updatedAt: Long = 0L,
    val isRootCollection: Boolean = false,
) {
    val key: String = "native-token:$databaseId:$entryId"
    // Negative IDs are presentation-only and never enter Room's password table.
    val displayId: Long = java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))).long or Long.MIN_VALUE

    fun asPasswordCard(typeLabel: String): PasswordEntry = PasswordEntry(
        id = displayId, title = title, username = typeLabel, website = "", password = "",
        createdAt = java.util.Date(updatedAt), updatedAt = java.util.Date(updatedAt),
        isFavorite = isFavorite, mdbxDatabaseId = databaseId, mdbxFolderId = collectionId.takeUnless { isRootCollection },
        loginType = "API_TOKEN",
    )
}

// Deliberately no generated toString(): payloads must never appear in diagnostic output.
class NativeApiToken(val summary: NativeApiTokenSummary, val payload: String, val extras: NativeApiTokenExtras? = null)
