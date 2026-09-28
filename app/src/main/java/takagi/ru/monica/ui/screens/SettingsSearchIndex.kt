package takagi.ru.monica.ui.screens

import java.text.Normalizer
import java.util.Locale

internal data class SettingsSearchEntry(
    val id: String,
    val title: String,
    val summary: String,
    val path: String,
    val route: String,
    val focusTitleRes: Int = 0,
    val keywords: String = "",
)

private fun normalizeSettingsQuery(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

internal fun searchSettings(entries: List<SettingsSearchEntry>, query: String): List<SettingsSearchEntry> {
    val normalized = normalizeSettingsQuery(query)
    if (normalized.isEmpty()) return emptyList()
    val words = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
    return entries.mapNotNull { entry ->
        val title = normalizeSettingsQuery(entry.title)
        val aliases = normalizeSettingsQuery(entry.keywords)
        val summary = normalizeSettingsQuery(entry.summary)
        val path = normalizeSettingsQuery(entry.path)
        val fields = listOf(title, aliases, summary, path)
        if (!words.all { word -> fields.any { word in it } }) return@mapNotNull null
        val rank = when {
            title == normalized -> 0
            title.startsWith(normalized) -> 1
            normalized in title -> 2
            words.all { it in title } -> 3
            words.all { it in title || it in aliases } -> 4
            words.all { it in title || it in summary || it in aliases } -> 5
            else -> 6
        }
        entry to rank
    }.sortedBy { it.second }.map { it.first }.distinctBy { it.id }
}
