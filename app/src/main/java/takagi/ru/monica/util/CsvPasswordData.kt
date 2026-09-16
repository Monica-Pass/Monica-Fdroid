package takagi.ru.monica.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lossless intermediate data. Delimiters and whitespace are part of a credential. */
object CsvPasswordData {
    fun encode(username: String, password: String, website: String, email: String = "", phone: String = ""): String =
        buildJsonObject {
            put("username", username)
            put("password", password)
            put("website", website)
            put("email", email)
            put("phone", phone)
        }.toString()

    fun decode(data: String): Map<String, String> {
        if (data.trimStart().startsWith('{')) {
            val root = Json.parseToJsonElement(data) as? JsonObject
                ?: throw IllegalArgumentException("Invalid password fields")
            return root.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("Invalid password field")
            }
        }
        // Compatibility for old Monica CSV exports, which did not escape their delimiters.
        return data.split(';').mapNotNull { pair ->
            val separator = pair.indexOf(':')
            if (separator < 0) null else pair.substring(0, separator).trim() to pair.substring(separator + 1)
        }.toMap()
    }
}
