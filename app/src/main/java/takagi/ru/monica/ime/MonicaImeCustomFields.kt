package takagi.ru.monica.ime

import takagi.ru.monica.keepass.KeePassFieldRegistry
import takagi.ru.monica.keepass.KeePassFieldRole

internal fun isImeCustomFieldName(name: String): Boolean {
    val title = name.trim()
    if (title.isEmpty() || title.startsWith("monica.", ignoreCase = true)) return false
    if (title.startsWith("Monica", ignoreCase = true) && KeePassFieldRegistry.isMonicaOwned(title)) return false
    // Ordinary user labels such as Email, PIN or Password remain valid custom fields.
    return KeePassFieldRegistry.roleOf(title) !in setOf(
        KeePassFieldRole.KEEPASS_TOTP, KeePassFieldRole.KEEPASS_PASSKEY, KeePassFieldRole.KEEPASS_PLUGIN
    )
}
