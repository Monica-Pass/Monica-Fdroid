package takagi.ru.monica.data.dedup

import java.util.Locale
import java.net.URI
import takagi.ru.monica.data.PasswordEntry

internal object DedupPasswordIdentity {
    fun key(entry: PasswordEntry): String {
        val site = normalizeWebsite(entry.website)
        val username = normalizeUsername(entry.username)
        val title = normalizeText(entry.title)
        val packageName = normalizeText(entry.appPackageName)
        val loginType = entry.loginType.uppercase(Locale.ROOT).ifBlank { "PASSWORD" }
        return when {
            site.isNotBlank() && username.isNotBlank() -> keyParts("site", loginType, site, username)
            packageName.isNotBlank() && username.isNotBlank() -> keyParts("app", loginType, packageName, username)
            title.isNotBlank() && username.isNotBlank() -> keyParts("title_user", loginType, title, username)
            else -> "entry|${entry.id}"
        }
    }

    fun normalizeText(value: String): String = value.trim().lowercase(Locale.ROOT)

    fun normalizeUsername(value: String): String =
        if (value.trim().count { it == '@' } == 1) normalizeText(value) else value

    private fun keyParts(vararg parts: String): String = parts.joinToString("|") { "${it.length}:$it" }

    fun normalizeWebsite(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return ""
        // DNS names are case-insensitive; paths, queries and arbitrary account names are not.
        return runCatching {
            val uri = URI(if (raw.contains("://")) raw else "https://$raw")
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return@runCatching raw
            buildString {
                uri.rawUserInfo?.let { append(it).append('@') }
                append(host)
                if (uri.port != -1) append(':').append(uri.port)
                append(uri.rawPath.orEmpty().let { if (it == "/") "" else it })
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            }
        }.getOrDefault(raw)
    }
}
