package takagi.ru.monica.credentialexchange

import kotlinx.serialization.json.*
import java.util.Base64
import java.util.UUID

/** CXF 1.0, August 14 2025. No Android, storage, or network side effects. */
object CxfCredentialCodec {
    const val MAX_DOCUMENT_CHARS = 32 * 1024 * 1024
    private const val MAX_ITEMS = 50_000
    private val json = Json { ignoreUnknownKeys = true }

    enum class SkipReason { UNSUPPORTED_CREDENTIAL, INVALID_CREDENTIAL, PASSKEY_EXTENSION, PRIVATE_KEY, NONZERO_COUNTER }

    data class Login(val username: String, val password: String) {
        override fun toString() = "Login(<redacted>)"
    }

    data class Passkey(
        val credentialId: String,
        val rpId: String,
        val username: String,
        val userDisplayName: String,
        val userHandle: String,
        val key: String,
        val algorithm: Int,
        val publicKey: String,
        val signCount: Long = 0,
    ) {
        override fun toString() = "Passkey(<redacted>)"
    }

    data class Item(
        val id: String,
        val title: String,
        val urls: List<String> = emptyList(),
        val logins: List<Login> = emptyList(),
        val passkeys: List<Passkey> = emptyList(),
        val notes: String = "",
        val createdAt: Long,
        val modifiedAt: Long = createdAt,
        val favorite: Boolean = false,
        val androidApps: JsonArray = JsonArray(emptyList()),
    ) {
        override fun toString() = "Item(<redacted>, logins=${logins.size}, passkeys=${passkeys.size})"
    }

    data class Decoded(val items: List<Item>, val skipped: Map<SkipReason, Int>) {
        val passwordCount get() = items.sumOf { it.logins.size }
        val passkeyCount get() = items.sumOf { it.passkeys.size }
        val skippedCount get() = skipped.values.sum()
    }

    class InvalidDocument : IllegalArgumentException("Invalid or unsupported CXF document")

    fun decode(document: String, nowMillis: Long = System.currentTimeMillis()): Decoded {
        if (document.length > MAX_DOCUMENT_CHARS) throw InvalidDocument()
        val root = try { json.parseToJsonElement(document) as? JsonObject } catch (_: Exception) { null }
            ?: throw InvalidDocument()
        val version = root["version"] as? JsonObject ?: throw InvalidDocument()
        if (version.unsignedNumber("major") != 1L ||
            version.unsignedNumber("minor")?.let { it in 0..255 } != true) throw InvalidDocument()
        if (root.textOrNull("exporterRpId") == null || root.textOrNull("exporterDisplayName") == null ||
            root.unsignedNumber("timestamp") == null) throw InvalidDocument()
        val accounts = root["accounts"] as? JsonArray ?: throw InvalidDocument()
        val items = mutableListOf<Item>()
        val skipped = mutableMapOf<SkipReason, Int>()
        fun skip(reason: SkipReason) { skipped[reason] = (skipped[reason] ?: 0) + 1 }
        var visited = 0
        for (accountElement in accounts) {
            val account = accountElement as? JsonObject ?: throw InvalidDocument()
            if (!account.hasEntityId() || account.textOrNull("username") == null ||
                account.textOrNull("email") == null || account["collections"] !is JsonArray) throw InvalidDocument()
            val accountItems = account["items"] as? JsonArray ?: throw InvalidDocument()
            for (element in accountItems) {
                if (++visited > MAX_ITEMS) throw InvalidDocument()
                val source = element as? JsonObject
                val credentials = source?.get("credentials") as? JsonArray
                if (source == null || credentials == null || !source.hasEntityId() || source.textOrNull("title") == null) {
                    skip(SkipReason.INVALID_CREDENTIAL)
                    continue
                }
                val logins = mutableListOf<Login>()
                val passkeys = mutableListOf<Passkey>()
                val notes = mutableListOf<String>()
                for (credentialElement in credentials) {
                    val credential = credentialElement as? JsonObject
                    if (credential == null) {
                        skip(SkipReason.INVALID_CREDENTIAL)
                        continue
                    }
                    when (credential.textOrNull("type")) {
                        "basic-auth" -> {
                            val username = credential.editableValue("username", "string")
                            val password = credential.editableValue("password", "concealed-string")
                            if (("username" in credential && username == null) ||
                                ("password" in credential && password == null) ||
                                (username == null && password == null)) skip(SkipReason.INVALID_CREDENTIAL)
                            else logins += Login(username.orEmpty(), password.orEmpty())
                        }
                        "note" -> credential.editableValue("content", "string")?.let(notes::add)
                        "passkey" -> {
                            // Losing an hmac-secret / PRF seed can make encrypted RP data unrecoverable.
                            val extensions = credential["fido2Extensions"] as? JsonObject
                            if (credential["fido2Extensions"] != null &&
                                (extensions == null || extensions.isNotEmpty())) {
                                skip(SkipReason.PASSKEY_EXTENSION)
                                continue
                            }
                            try {
                                val id = requireBase64Url(credential.requiredText("credentialId"), 1, 1023)
                                val handle = requireBase64Url(credential.requiredText("userHandle"), 1, 64)
                                val rpId = credential.requiredText("rpId")
                                require(rpId.isNotBlank() && rpId.length <= 253 &&
                                    !rpId.any { it.isWhitespace() || it == '/' || it == ':' || it == '\\' })
                                val key = requireBase64Url(credential.requiredText("key"), 16, 16_384)
                                val keyBytes = Base64.getUrlDecoder().decode(key)
                                val material = try { CxfPasskeyMaterial.decode(keyBytes) } finally { keyBytes.fill(0) }
                                if (material == null) {
                                    skip(SkipReason.PRIVATE_KEY)
                                    continue
                                }
                                passkeys += Passkey(
                                    credentialId = id, rpId = rpId,
                                    username = credential.requiredText("username"),
                                    userDisplayName = credential.requiredText("userDisplayName"),
                                    userHandle = handle, key = key,
                                    algorithm = material.algorithm, publicKey = material.cosePublicKey,
                                    signCount = 0,
                                )
                            } catch (_: Exception) {
                                skip(SkipReason.INVALID_CREDENTIAL)
                            }
                        }
                        else -> skip(SkipReason.UNSUPPORTED_CREDENTIAL)
                    }
                }
                if (logins.isNotEmpty() || passkeys.isNotEmpty()) {
                    val scope = source["scope"] as? JsonObject
                    val urls = (scope?.get("urls") as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                    }
                    items += Item(
                        id = "$visited", title = source.textOrNull("title").orEmpty(),
                        urls = urls, logins = logins, passkeys = passkeys,
                        notes = notes.joinToString("\n\n"),
                        createdAt = source.timeMillis("creationAt", nowMillis),
                        modifiedAt = source.timeMillis("modifiedAt", nowMillis),
                        favorite = (source["favorite"] as? JsonPrimitive)?.booleanOrNull == true,
                        androidApps = (scope?.get("androidApps") as? JsonArray) ?: JsonArray(emptyList()),
                    )
                }
            }
        }
        return Decoded(items, skipped)
    }

    fun encode(
        items: List<Item>,
        accountName: String,
        requestedTypes: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = buildJsonObject {
        putJsonObject("version") { put("major", 1); put("minor", 0) }
        put("exporterRpId", "monica-pass.github.io")
        put("exporterDisplayName", "Monica")
        put("timestamp", nowMillis / 1000)
        putJsonArray("accounts") {
            addJsonObject {
                put("id", opaqueId("monica"))
                put("username", accountName)
                put("email", "")
                putJsonArray("collections") {}
                putJsonArray("items") {
                    for (item in items) {
                        val credentials = buildJsonArray {
                            if ("basic-auth" in requestedTypes) item.logins.forEach { login ->
                                addJsonObject {
                                    put("type", "basic-auth")
                                    put("username", editableField("string", login.username))
                                    put("password", editableField("concealed-string", login.password))
                                }
                            }
                            if ("passkey" in requestedTypes) item.passkeys.filter { it.signCount == 0L }.forEach { key ->
                                addJsonObject {
                                    put("type", "passkey")
                                    put("credentialId", requireBase64Url(key.credentialId, 1, 1023))
                                    put("rpId", key.rpId)
                                    put("username", key.username)
                                    put("userDisplayName", key.userDisplayName)
                                    put("userHandle", requireBase64Url(key.userHandle, 1, 64))
                                    put("key", requireBase64Url(key.key, 16, 16_384))
                                }
                            }
                            if (item.notes.isNotEmpty() && "note" in requestedTypes) addJsonObject {
                                put("type", "note")
                                put("content", editableField("string", item.notes))
                            }
                        }
                        if (credentials.isEmpty()) continue
                        addJsonObject {
                            put("id", opaqueId(UUID.randomUUID().toString()))
                            put("title", item.title)
                            put("creationAt", item.createdAt.coerceAtLeast(0) / 1000)
                            put("modifiedAt", item.modifiedAt.coerceAtLeast(0) / 1000)
                            put("favorite", item.favorite)
                            putJsonObject("scope") {
                                putJsonArray("urls") { item.urls.filter { it.isNotBlank() }.forEach { add(it) } }
                                put("androidApps", item.androidApps)
                            }
                            put("credentials", credentials)
                        }
                    }
                }
            }
        }
    }.toString()

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun requireBase64Url(value: String, minBytes: Int, maxBytes: Int): String {
        require(value.length <= ((maxBytes + 2) / 3) * 4 && value.matches(Regex("[A-Za-z0-9_-]+={0,2}")))
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size in minBytes..maxBytes)
        return base64Url(bytes)
    }

    private fun JsonObject.textOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.requiredText(name: String): String = textOrNull(name) ?: throw InvalidDocument()

    private fun JsonObject.unsignedNumber(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }

    private fun JsonObject.hasEntityId(): Boolean = runCatching {
        requireBase64Url(requiredText("id"), 1, 64)
    }.isSuccess

    private fun JsonObject.editableValue(name: String, type: String): String? {
        val field = this[name] as? JsonObject ?: return null
        if (field.textOrNull("fieldType") != type) return null
        return field.textOrNull("value")
    }

    private fun JsonObject.timeMillis(name: String, default: Long): Long {
        val seconds = unsignedNumber(name) ?: return default
        return if (seconds in 0..Long.MAX_VALUE / 1000) seconds * 1000 else default
    }

    private fun editableField(type: String, value: String) = buildJsonObject {
        put("fieldType", type)
        put("value", value)
    }

    private fun opaqueId(value: String) = base64Url(value.toByteArray(Charsets.UTF_8))
}
