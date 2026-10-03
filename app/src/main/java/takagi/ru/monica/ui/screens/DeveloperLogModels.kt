package takagi.ru.monica.ui.screens

internal enum class DeveloperLogLevel { ERROR, WARN, INFO, DEBUG, VERBOSE, OTHER }
internal enum class DeveloperLogFilter { ALL, ERROR, WARNING }
internal enum class DeveloperLogSource { SYSTEM, AUTOFILL, BITWARDEN, FORENSICS, MDBX, SECURITY, STEAM, PASSKEY }

internal data class DeveloperLogEvent(
    val id: String,
    val source: DeveloperLogSource,
    val level: DeveloperLogLevel,
    val timestamp: String,
    val tag: String,
    val summary: String,
    val text: String,
)
internal data class DeveloperLogSnapshot(
    val report: String = "",
    val events: List<DeveloperLogEvent> = emptyList(),
    val collectedAt: Long = 0,
)

private val threadTime = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+\d+\s+([VDIWEAF])\s+([^:]+):\s?(.*)$""")
private val structured = Regex("""^(?:(\d{4}-\d{2}-\d{2}\s+)?(\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+)?\[(ERROR|WARN|WARNING|INFO|DEBUG|VERBOSE)\]\s*(?:\[([^]]+)\]\s*)?(.*)$""")
private val stackLine = Regex("""^(?:at\s|Caused by:|Suppressed:|\.\.\. \d+ more|Process:|java\.|javax\.|kotlin\.|android\.).*""")
private data class LogHeader(val time: String, val level: DeveloperLogLevel, val tag: String, val message: String, val stream: String)
private fun logHeader(line: String): LogHeader? {
    threadTime.matchEntire(line)?.let { m ->
        return LogHeader(m.groupValues[1], logLevel(m.groupValues[3]), m.groupValues[4].trim(), m.groupValues[5], m.groupValues[2] + ":" + m.groupValues[4].trim())
    }
    structured.matchEntire(line)?.let { m ->
        return LogHeader(m.groupValues[1] + m.groupValues[2], logLevel(m.groupValues[3]), m.groupValues[4], m.groupValues[5], m.groupValues[4])
    }
    return null
}
private fun logLevel(value: String) = when (value) {
    "E", "F", "A", "ERROR" -> DeveloperLogLevel.ERROR
    "W", "WARN", "WARNING" -> DeveloperLogLevel.WARN
    "I", "INFO" -> DeveloperLogLevel.INFO
    "D", "DEBUG" -> DeveloperLogLevel.DEBUG
    "V", "VERBOSE" -> DeveloperLogLevel.VERBOSE
    else -> DeveloperLogLevel.OTHER
}

/** Filter whole events, never isolated stack lines. The original report remains unchanged. */
internal fun parseDeveloperLogEvents(raw: String, source: DeveloperLogSource): List<DeveloperLogEvent> {
    val events = mutableListOf<DeveloperLogEvent>()
    var header: LogHeader? = null
    val body = StringBuilder()
    fun flush() {
        if (body.isBlank()) { body.clear(); return }
        val text = body.toString().trimEnd()
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val h = header
        events += DeveloperLogEvent("${source.name}:${events.size}", source,
            h?.level ?: if (first.startsWith("FATAL EXCEPTION")) DeveloperLogLevel.ERROR else DeveloperLogLevel.OTHER,
            h?.time.orEmpty(), h?.tag.orEmpty(), (h?.message ?: first).take(300), text)
        body.clear()
    }
    raw.lineSequence().forEach { line ->
        val next = logHeader(line)
        val continuation = next != null && header != null && next.stream == header?.stream &&
            next.level == header?.level && stackLine.matches(next.message.trimStart())
        if (next != null && !continuation) {
            flush(); header = next
        } else if (next == null && line.isNotBlank() && !line.first().isWhitespace() &&
            !stackLine.matches(line) && body.isNotEmpty()) {
            // Unstructured diagnostic events have no timestamp/level. Keep them neutral.
            flush(); header = null
        }
        if (line.isNotBlank() || body.isNotEmpty()) body.appendLine(line)
    }
    flush()
    return events
}

internal fun filterDeveloperLogEvents(
    events: List<DeveloperLogEvent>, query: String, filter: DeveloperLogFilter, source: DeveloperLogSource?,
): List<DeveloperLogEvent> {
    val needle = query.trim()
    return events.filter { event ->
        (source == null || event.source == source) &&
            (filter == DeveloperLogFilter.ALL ||
                filter == DeveloperLogFilter.ERROR && event.level == DeveloperLogLevel.ERROR ||
                filter == DeveloperLogFilter.WARNING && event.level == DeveloperLogLevel.WARN) &&
            (needle.isEmpty() || event.text.contains(needle, ignoreCase = true) ||
                event.source.name.contains(needle, ignoreCase = true))
    }
}

/** Only remove overlap between storage and memory; repeated events in either source are evidence. */
internal fun mergeDeveloperAutofillEvents(
    persisted: List<DeveloperLogEvent>, memory: List<DeveloperLogEvent>,
): List<DeveloperLogEvent> {
    val storedCounts = persisted.groupingBy { it.text }.eachCount().toMutableMap()
    val additional = memory.mapIndexedNotNull { index, event ->
        val remaining = storedCounts[event.text] ?: 0
        if (remaining > 0) {
            storedCounts[event.text] = remaining - 1
            null
        } else event.copy(id = "AUTOFILL:memory:$index")
    }
    return persisted + additional
}

/** Bounded lazy text layouts, preserving every character of each filtered event. */
internal data class DeveloperLogTextBlock(val id: String, val event: DeveloperLogEvent, val text: String)
internal fun developerLogTextBlocks(events: List<DeveloperLogEvent>): List<DeveloperLogTextBlock> = buildList {
    events.forEach { event ->
        var start = 0
        var index = 0
        while (start < event.text.length) {
            var end = minOf(start + 4000, event.text.length)
            if (end < event.text.length) {
                val newline = event.text.lastIndexOf('\n', end - 1)
                if (newline >= start) end = newline + 1
                else if (event.text[end - 1].isHighSurrogate()) end--
            }
            val text = event.text.substring(start, end)
            // The next lazy block supplies the line break visually; retain it in the model.
            add(DeveloperLogTextBlock(if (index == 0) event.id else "${event.id}:block:$index", event, text))
            start = end
            index++
        }
    }
}
