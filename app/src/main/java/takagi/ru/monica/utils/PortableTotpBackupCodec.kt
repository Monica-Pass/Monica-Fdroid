package takagi.ru.monica.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Removes installation-bound encryption from both the TOTP payload and its nested secret fields. */
internal object PortableTotpBackupCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val sensitiveFields = setOf(
        "secret",
        "pin",
        "steamSharedSecretBase64",
        "steamRevocationCode",
        "steamIdentitySecret",
        "steamRawJson"
    )

    fun encode(
        storedItemData: String,
        entryTitle: String,
        decryptIfNeeded: (String) -> String
    ): String {
        val portablePayload = PortableSecretExportPolicy.resolve(
            storedValue = storedItemData,
            entryTitle = entryTitle,
            decryptIfNeeded = decryptIfNeeded
        )
        if (!portablePayload.trimStart().startsWith('{')) return portablePayload

        val root = try {
            json.parseToJsonElement(portablePayload) as? JsonObject
                ?: throw IllegalArgumentException("TOTP payload is not a JSON object")
        } catch (error: Throwable) {
            throw PortableSecretExportException(entryTitle, error)
        }

        val portableFields = root.mapValues { (name, element) ->
            if (name !in sensitiveFields) return@mapValues element
            val storedValue = runCatching { element.jsonPrimitive.contentOrNull }
                .getOrNull()
                ?: return@mapValues element
            JsonPrimitive(
                PortableSecretExportPolicy.resolve(
                    storedValue = storedValue,
                    entryTitle = entryTitle,
                    decryptIfNeeded = decryptIfNeeded
                )
            )
        }
        return JsonObject(portableFields).toString()
    }
}
