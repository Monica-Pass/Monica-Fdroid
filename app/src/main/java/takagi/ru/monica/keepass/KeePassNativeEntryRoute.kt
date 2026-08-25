package takagi.ru.monica.keepass

import java.util.UUID

internal enum class KeePassNativeEntryRouteKind {
    PASSWORD,
    TOTP,
    NOTE,
    BANK_CARD,
    DOCUMENT,
    PASSKEY,
    GENERIC
}

internal object KeePassNativeEntryRoutePolicy {
    fun routeFor(kind: KeePassNativeEntryKind): KeePassNativeEntryRouteKind = when (kind) {
        KeePassNativeEntryKind.PASSWORD -> KeePassNativeEntryRouteKind.PASSWORD
        KeePassNativeEntryKind.TOTP -> KeePassNativeEntryRouteKind.TOTP
        KeePassNativeEntryKind.NOTE -> KeePassNativeEntryRouteKind.NOTE
        KeePassNativeEntryKind.BANK_CARD -> KeePassNativeEntryRouteKind.BANK_CARD
        KeePassNativeEntryKind.DOCUMENT -> KeePassNativeEntryRouteKind.DOCUMENT
        KeePassNativeEntryKind.PASSKEY -> KeePassNativeEntryRouteKind.PASSKEY
        KeePassNativeEntryKind.TEMPLATE,
        KeePassNativeEntryKind.UNKNOWN -> KeePassNativeEntryRouteKind.GENERIC
    }
}

internal sealed interface KeePassNativeResolvedRoute {
    data class Password(val id: Long) : KeePassNativeResolvedRoute
    data class Totp(val id: Long) : KeePassNativeResolvedRoute
    data class Note(val id: Long) : KeePassNativeResolvedRoute
    data class BankCard(val id: Long) : KeePassNativeResolvedRoute
    data class Document(val id: Long) : KeePassNativeResolvedRoute
    data class Passkey(val recordId: Long) : KeePassNativeResolvedRoute
    data object Generic : KeePassNativeResolvedRoute
}

internal data class KeePassNativeManagerRetainedState(
    val databaseId: Long,
    val currentGroupUuid: UUID? = null,
    val searchQuery: String = "",
    val searchOptions: KeePassNativeSearchOptions = KeePassNativeSearchOptions(query = ""),
    val searchCurrentFolderOnly: Boolean = false
)
