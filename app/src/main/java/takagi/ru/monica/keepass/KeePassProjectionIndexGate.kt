package takagi.ru.monica.keepass

internal enum class KeePassProjectionKind {
    PASSWORD,
    TOTP,
    NOTE,
    BANK_CARD,
    DOCUMENT,
    PASSKEY
}

internal data class KeePassProjectionRefreshDecision(
    val revisionToken: String,
    val needsRefresh: Boolean
)

internal class KeePassProjectionIndexGate {
    private data class IndexedRevision(
        val revisionToken: String,
        val kinds: Set<KeePassProjectionKind>
    )

    private val indexedRevisions = mutableMapOf<Long, IndexedRevision>()

    @Synchronized
    fun needsRefresh(
        databaseId: Long,
        revisionToken: String,
        kind: KeePassProjectionKind
    ): Boolean {
        val indexed = indexedRevisions[databaseId] ?: return true
        return indexed.revisionToken != revisionToken || kind !in indexed.kinds
    }

    @Synchronized
    fun markIndexed(
        databaseId: Long,
        revisionToken: String,
        kinds: Set<KeePassProjectionKind>
    ) {
        if (kinds.isEmpty()) return
        val previous = indexedRevisions[databaseId]
        indexedRevisions[databaseId] = if (previous?.revisionToken == revisionToken) {
            previous.copy(kinds = previous.kinds + kinds)
        } else {
            IndexedRevision(revisionToken = revisionToken, kinds = kinds.toSet())
        }
    }

    @Synchronized
    fun invalidate(databaseId: Long) {
        indexedRevisions.remove(databaseId)
    }

    @Synchronized
    fun clear() {
        indexedRevisions.clear()
    }
}
