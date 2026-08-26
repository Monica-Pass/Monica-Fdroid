package takagi.ru.monica.ime

import android.icu.text.Transliterator

internal val imeSortKeyTransliterator: Transliterator? by lazy(LazyThreadSafetyMode.NONE) {
    runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
}

internal fun normalizedImeSortKey(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "#"
    val source = if (trimmed.none { it.code > 0x7F }) {
        trimmed
    } else {
        val transliterator = imeSortKeyTransliterator ?: return trimmed
        runCatching { transliterator.transliterate(trimmed) }.getOrDefault(trimmed)
    }
    return buildString(source.length) {
        source.forEach { char ->
            when {
                char.isLetterOrDigit() -> append(char)
                char.isWhitespace() && isNotEmpty() && last() != ' ' -> append(' ')
            }
        }
    }.trim().ifEmpty { trimmed }
}

internal fun imeIndexLetter(sortKey: String): String {
    val first = sortKey.firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}
