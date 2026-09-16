package takagi.ru.monica.util

import java.io.BufferedReader

/** RFC 4180 records; preserve CR/LF, spaces and escaped quotes inside fields. */
internal object CsvRecords {
    private const val MAX_RECORD_CHARS = 4 * 1024 * 1024

    fun read(reader: BufferedReader): String? {
        val record = StringBuilder()
        var quoted = false
        while (true) {
            val next = reader.read()
            if (next < 0) {
                require(!quoted) { "Unterminated CSV field" }
                return record.toString().takeIf { it.isNotEmpty() }
            }
            val char = next.toChar()
            if (char == '"') quoted = !quoted
            if (!quoted && (char == '\r' || char == '\n')) {
                if (char == '\r') {
                    reader.mark(1)
                    if (reader.read() != '\n'.code) reader.reset()
                }
                return record.toString()
            }
            require(record.length < MAX_RECORD_CHARS) { "CSV record is too large" }
            record.append(char)
        }
    }

    fun fields(record: String): List<String> {
        val fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        while (index < record.length) {
            val char = record[index]
            when {
                quoted && char == '"' && record.getOrNull(index + 1) == '"' -> {
                    value.append('"')
                    index++
                }
                quoted && char == '"' -> { quoted = false; closedQuote = true }
                quoted -> value.append(char)
                char == ',' -> { fields += value.toString(); value.clear(); closedQuote = false }
                char == '"' && value.isEmpty() && !closedQuote -> quoted = true
                else -> {
                    require(!closedQuote && char != '"' && char != '\r' && char != '\n') { "Malformed CSV field" }
                    value.append(char)
                }
            }
            index++
        }
        require(!quoted) { "Unterminated CSV field" }
        fields += value.toString()
        return fields
    }
}
