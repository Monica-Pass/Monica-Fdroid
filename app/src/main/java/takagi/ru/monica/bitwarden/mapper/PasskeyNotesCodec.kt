package takagi.ru.monica.bitwarden.mapper

/** New native FIDO2 ciphers carry notes verbatim. Only recognize our complete old footer. */
internal object PasskeyNotesCodec {
    private val fields = listOf("credentialId", "rpId", "rpName", "userId", "userDisplayName",
        "publicKeyAlgorithm", "signCount", "createdAt", "lastUsedAt")

    fun decode(notes: String?): String {
        val raw = notes ?: return ""
        for (newline in listOf("\n", "\r\n")) {
            val header = newline + "🔐 This is a Passkey entry synced from Monica" + newline +
                "ℹ️ Private key availability depends on client capability." + newline + newline +
                "---" + newline + "[Monica Passkey Metadata]" + newline
            val start = raw.lastIndexOf(header)
            if (start < 0) continue
            val tail = raw.substring(start + header.length).removeSuffix(newline).split(newline)
            if (tail.size != fields.size || fields.indices.any { !tail[it].startsWith(fields[it] + ": ") }) continue
            if (tail[0].substringAfter(": ").isBlank() || tail[1].substringAfter(": ").isBlank()) continue
            if ((5..8).any { tail[it].substringAfter(": ").toLongOrNull() == null }) continue
            return raw.substring(0, start).removeSuffix(newline)
        }
        return raw
    }
}
