package takagi.ru.monica.steam.data

import java.util.Locale

/** Stable contract used to discover Steam authenticators stored in external vault entries. */
object SteamExternalMaFileContract {
    const val MARKER_FIELD = "Monica.Type"
    const val MARKER_VALUE = "steam_mafile_v1"
    const val PENDING_MARKER_VALUE = "steam_mafile_pending_v1"
    const val MIME_TYPE = "application/json"
    const val MAX_MAFILE_BYTES = 1024 * 1024

    fun isMarked(fields: Iterable<Pair<String, String>>): Boolean {
        return fields.any { (name, value) ->
            name.trim().equals(MARKER_FIELD, ignoreCase = true) &&
                value.trim().equals(MARKER_VALUE, ignoreCase = true)
        }
    }

    fun isMaFile(fileName: String): Boolean =
        fileName.trim().lowercase(Locale.ROOT).endsWith(".mafile")

    fun isValidMaFileSize(sizeBytes: Int): Boolean =
        sizeBytes in 1..MAX_MAFILE_BYTES

    fun selectMaFile(fileNames: Iterable<String>): String? {
        return fileNames.filter(::isMaFile).distinct().singleOrNull()
    }

    fun isPending(fields: Iterable<Pair<String, String>>): Boolean {
        return fields.any { (name, value) ->
            name.trim().equals(MARKER_FIELD, ignoreCase = true) &&
                value.trim().equals(PENDING_MARKER_VALUE, ignoreCase = true)
        }
    }

    /**
     * Returns every attachment that is safe to inspect for an explicitly marked Steam entry.
     * Real maFile names are tried first; legacy Bitwarden ciphertext names remain as a
     * content-validated fallback for entries created before filename decryption was fixed.
     */
    fun candidateFileNames(fileNames: Iterable<String>): List<String> {
        val distinctNames = fileNames.distinct()
        val namedMaFiles = distinctNames.filter(::isMaFile)
        return namedMaFiles + distinctNames.filterNot(::isMaFile)
    }

    fun attachmentFileName(account: SteamAccount): String {
        val base = account.accountName
            .ifBlank { account.visibleSteamId }
            .ifBlank { "steam" }
            .replace(Regex("""[\\/:*?\"<>|\p{Cntrl}]"""), "_")
            .trim()
            .take(80)
            .ifBlank { "steam" }
        return "$base.maFile"
    }
}
