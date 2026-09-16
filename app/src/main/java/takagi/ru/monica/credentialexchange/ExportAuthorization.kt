package takagi.ru.monica.credentialexchange

/** One fresh authentication authorizes one explicitly selected source for a short foreground session. */
internal class ExportAuthorization {
    private var source: String? = null
    private var authenticatedAt: Long = 0

    fun grant(sourceKey: String, elapsedRealtime: Long) {
        source = sourceKey
        authenticatedAt = elapsedRealtime
    }

    fun clear() { source = null; authenticatedAt = 0 }

    fun consume(sourceKey: String, elapsedRealtime: Long): Boolean {
        val allowed = source == sourceKey && elapsedRealtime - authenticatedAt in 0..120_000
        clear()
        return allowed
    }
}
