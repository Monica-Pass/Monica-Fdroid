package takagi.ru.monica.keepass

import takagi.ru.monica.data.PasswordEntry
import java.util.UUID

internal enum class KeePassPasswordMoveRoute {
    LEGACY_PROJECTION,
    NATIVE_RELOCATE,
    NATIVE_CROSS_DATABASE
}

internal fun resolveKeePassPasswordMoveRoute(
    entry: PasswordEntry,
    targetDatabaseId: Long
): KeePassPasswordMoveRoute {
    val sourceDatabaseId = entry.keepassDatabaseId
        ?: return KeePassPasswordMoveRoute.LEGACY_PROJECTION
    entry.keepassEntryUuid
        ?.takeIf { it.isNotBlank() }
        ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
        ?: return KeePassPasswordMoveRoute.LEGACY_PROJECTION
    return if (sourceDatabaseId == targetDatabaseId) {
        KeePassPasswordMoveRoute.NATIVE_RELOCATE
    } else {
        KeePassPasswordMoveRoute.NATIVE_CROSS_DATABASE
    }
}
