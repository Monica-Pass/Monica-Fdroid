package takagi.ru.monica.data.dedup

import java.util.Locale
import takagi.ru.monica.data.PasswordEntry

internal object DedupPasswordIdentity {
    fun key(entry: PasswordEntry): String {
        val site = normalizeWebsite(entry.website)
        val username = normalizeText(entry.username)
        val title = normalizeText(entry.title)
        val packageName = normalizeText(entry.appPackageName)
        val loginType = entry.loginType.uppercase(Locale.ROOT).ifBlank { "PASSWORD" }
        return when {
            site.isNotBlank() && username.isNotBlank() -> "site|$loginType|$site|$username"
            packageName.isNotBlank() && username.isNotBlank() -> "app|$loginType|$packageName|$username"
            title.isNotBlank() && username.isNotBlank() -> "title_user|$loginType|$title|$username"
            else -> "entry|${entry.id}"
        }
    }

    fun normalizeText(value: String): String = value.trim().lowercase(Locale.ROOT)

    fun normalizeWebsite(value: String): String {
        val raw = value.trim().lowercase(Locale.ROOT)
        if (raw.isBlank()) return ""
        return raw
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }
}
