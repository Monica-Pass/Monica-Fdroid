package takagi.ru.monica.utils

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Resolves a user-facing name for a KeePass document selected through the
 * Storage Access Framework.
 *
 * DocumentsProvider implementations are allowed to expose an opaque URI
 * segment (for example, `document:1000097490`) instead of a file name.  That
 * segment must never be persisted as the database name when DISPLAY_NAME is
 * available.
 */
object KeePassFileNameResolver {
    const val DEFAULT_DATABASE_NAME = "KeePass Database"

    /** Query the provider's real display name, if it exposes one. */
    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
            }
        }.getOrNull()?.trim()?.takeIf(::isUsableCandidate)
    }

    /**
     * Returns a database name without the KDB/KDBX extension, or null when
     * all candidates are missing/opaque provider identifiers.
     */
    fun databaseNameFromCandidates(
        displayName: String?,
        uriLastPathSegment: String?
    ): String? {
        val candidate = usableCandidate(displayName) ?: usableCandidate(uriLastPathSegment)
            ?: return null
        return stripDatabaseExtension(candidate).takeIf { it.isNotBlank() }
    }

    /** Returns a file name (including its extension) for UI display. */
    fun displayFileNameFromCandidates(
        displayName: String?,
        uriLastPathSegment: String?
    ): String {
        return usableCandidate(displayName)
            ?: usableCandidate(uriLastPathSegment)
            ?: DEFAULT_DATABASE_NAME
    }

    /**
     * Keeps a user-entered name, but replaces an empty/opaque initial value
     * with the provider's real file name.
     */
    fun chooseImportedDatabaseName(
        requestedName: String?,
        displayName: String?,
        uriLastPathSegment: String?
    ): String {
        val requested = requestedName?.trim()
        val isOpaqueFallback = requested.equals(DEFAULT_DATABASE_NAME, ignoreCase = true) &&
            isProviderIdentifier(uriLastPathSegment.orEmpty())
        if (!requested.isNullOrBlank() && !isProviderIdentifier(requested) && !isOpaqueFallback) {
            return requested
        }
        return databaseNameFromCandidates(displayName, uriLastPathSegment)
            ?: DEFAULT_DATABASE_NAME
    }

    /** True for opaque SAF/DocumentProvider identifiers, not real file names. */
    fun isProviderIdentifier(value: String): Boolean {
        val candidate = value.trim()
        if (candidate.isBlank()) return false
        val lower = candidate.lowercase()
        return lower.startsWith("content://") ||
            lower.startsWith("document:") ||
            lower.startsWith("raw:") ||
            lower.startsWith("tree:")
    }

    private fun usableCandidate(value: String?): String? {
        val candidate = value
            ?.trim()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return candidate.takeUnless(::isProviderIdentifier)
    }

    private fun stripDatabaseExtension(value: String): String {
        return when {
            value.endsWith(".kdbx", ignoreCase = true) -> value.dropLast(".kdbx".length)
            value.endsWith(".kdb", ignoreCase = true) -> value.dropLast(".kdb".length)
            else -> value
        }
    }

    private fun isUsableCandidate(value: String): Boolean =
        value.isNotBlank() && !isProviderIdentifier(value)
}
