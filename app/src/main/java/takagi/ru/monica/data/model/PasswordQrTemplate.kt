package takagi.ru.monica.data.model

import java.util.Base64
import takagi.ru.monica.data.PasswordEntry

/** No scripting, recursive expansion or saved resolved secrets. Names travel across databases. */
object PasswordQrTemplate {
    const val WIFI = "WIFI:T:WPA;S:%ACCOUNT%;P:%PASSWORD%;H:false;;"
    val keys = listOf("ACCOUNT", "PASSWORD", "TITLE", "URL", "EMAIL", "PHONE", "NOTES")
    data class Values(val fields: Map<String, String?>, val custom: List<Pair<String, String?>> = emptyList())
    class Invalid(val field: String) : IllegalArgumentException("Unavailable template field")
    fun values(entry: PasswordEntry, password: String?, custom: List<Pair<String, String?>> = emptyList()) = Values(
        mapOf("ACCOUNT" to entry.username, "PASSWORD" to password, "TITLE" to entry.title, "URL" to entry.website,
            "EMAIL" to entry.email, "PHONE" to entry.phone, "NOTES" to entry.notes), custom)
    fun customToken(name: String) = "%FIELD:${Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))}%"
    fun isTemplate(block: PasswordContentBlocks.Block) = block.value("mode") == "template"
    fun supported(block: PasswordContentBlocks.Block): Boolean = when (block.value("mode")) {
        "", "literal" -> true
        "template" -> block.value("templateVersion") == "1"
        else -> false
    }
    fun render(template: String, values: Values): String {
        require(template.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        val wifi = template.startsWith("WIFI:", ignoreCase = true)
        val result = StringBuilder()
        var offset = 0
        // %% represents a literal percent; values are inserted once, never parsed as templates.
        val tokens = Regex("%%|%([A-Z][A-Z0-9_]*)(?::([^%]*))?%")
        for (match in tokens.findAll(template)) {
            result.append(template, offset, match.range.first)
            if (match.value == "%%") result.append('%') else {
                val key = match.groupValues[1]
                val value = if (key == "FIELD") {
                    val name = runCatching {
                        val bytes = Base64.getUrlDecoder().decode(match.groupValues[2])
                        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }.getOrElse { throw Invalid("FIELD") }
                    values.custom.filter { it.first == name }.singleOrNull()?.second ?: throw Invalid(name)
                } else {
                    if (match.groupValues[2].isNotEmpty()) throw Invalid(key)
                    values.fields[key] ?: throw Invalid(key)
                }
                result.append(if (wifi) escapeWifi(value) else value)
            }
            require(result.length <= 256 * 1024) { "Rendered template too large" }
            offset = match.range.last + 1
        }
        result.append(template, offset, template.length)
        require(result.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        return result.toString()
    }
    fun escapeWifi(value: String) = buildString { value.forEach { c ->
        if (c in "\\;,:\"") append('\\')
        append(c)
    } }
    fun resolve(block: PasswordContentBlocks.Block, values: Values): String {
        require(supported(block))
        return if (isTemplate(block)) render(block.value("content"), values) else block.value("content")
    }
}
