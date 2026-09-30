package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.utils.GpgKeyGenerator

/** Core fields only. Common content continues through the password editor's existing save pipeline. */
data class TemplateCredentialDraft(val type: String, val values: Map<String, String> = emptyMap(),
    val originalWifi: String = "", val originalSsh: String = "") {
    override fun toString() = "TemplateCredentialDraft($type, redacted)"
    fun value(key: String) = values[key].orEmpty()
    fun change(key: String, value: String) = copy(values = values + (key to value))
    fun secret(fallback: String): String = when (type) {
        "API_KEY" -> value("key")
        "GPG_KEY" -> value("privateKey")
        "WIFI" -> value("password")
        else -> fallback // Imported SSH entries may also have an independent login password.
    }
    fun fields(existing: List<CustomFieldDraft>): List<CustomFieldDraft> = when (type) {
        "API_KEY" -> existing.filterNot { ApiKeyEntryFields.owns(it.title) } + ApiKeyEntryFields.encode(value("url"))
        "GPG_KEY" -> existing.filterNot { GpgEntryFields.owns(it.title) } + GpgEntryFields.encode(
            GpgKeyGenerator.Key(value("publicKey"), value("privateKey"), value("fingerprint"), value("userId")))
        else -> existing
    }
    fun ssh(): String {
        if (type != "SSH_KEY") return originalSsh
        val old = SshKeyDataCodec.decode(originalSsh) ?: SshKeyData()
        return SshKeyDataCodec.encode(old.copy(algorithm = value("algorithm"), keySize = value("keySize").toIntOrNull() ?: 0,
            publicKeyOpenSsh = value("publicKeyOpenSsh"), privateKeyOpenSsh = value("privateKeyOpenSsh"),
            fingerprintSha256 = value("fingerprintSha256"), comment = value("comment"), format = value("format").ifBlank { old.format }))
    }
    fun wifi(): String {
        if (type != "WIFI") return originalWifi
        // Patch only editable fields. EAP, proxy, IP and unknown future JSON remain byte-for-byte values.
        val raw = if (originalWifi.isBlank()) emptyMap() else Json.parseToJsonElement(originalWifi).jsonObject
        return JsonObject(raw + mapOf("ssid" to JsonPrimitive(value("ssid")), "security" to JsonPrimitive(value("security")),
            "hiddenNetwork" to JsonPrimitive(value("hiddenNetwork") == "true"))).toString()
    }
    fun valid(): Boolean = when (type) {
        // An endpoint is stored metadata, not a request to open a URL. Local hostnames,
        // placeholders and imported values must not prevent saving the credential.
        "API_KEY" -> value("key").isNotBlank()
        "SSH_KEY" -> value("publicKeyOpenSsh").isNotBlank() || value("privateKeyOpenSsh").isNotBlank()
        "GPG_KEY" -> value("publicKey").isNotBlank()
        "WIFI" -> value("ssid").isNotBlank()
        else -> true
    }
    /** Validate imported key material once at save time, not on every text recomposition. */
    fun validateKeyMaterial() {
        if (type != "GPG_KEY") return
        val public = GpgKeyGenerator.parse(value("publicKey"))
        if (value("privateKey").isNotBlank()) require(GpgKeyGenerator.parse(value("privateKey")).fingerprint == public.fingerprint)
    }
    companion object {
        val types = setOf("API_KEY", "GPG_KEY", "SSH_KEY", "WIFI")
        fun ownsField(name: String) = ApiKeyEntryFields.owns(name) || GpgEntryFields.owns(name)
        fun load(entry: PasswordEntry, fields: List<CustomFieldDraft>, requestedType: String = entry.loginType): TemplateCredentialDraft? {
            val map = fields.associate { it.title to it.value }
            val type = when { GpgEntryFields.isGpg(map) -> "GPG_KEY"; ApiKeyEntryFields.isApiKey(map) -> "API_KEY"; else -> requestedType }
            if (type !in types) return null
            val values = when (type) {
                "API_KEY" -> mapOf("key" to entry.password, "url" to map[ApiKeyEntryFields.API_URL].orEmpty())
                "GPG_KEY" -> mapOf("privateKey" to entry.password, "publicKey" to
                    if (fields.any { GpgEntryFields.owns(it.title) }) GpgEntryFields.publicKey(map) else "",
                    "fingerprint" to map[GpgEntryFields.FINGERPRINT].orEmpty(), "userId" to map[GpgEntryFields.USER_ID].orEmpty())
                "SSH_KEY" -> {
                    require(entry.sshKeyData.isBlank() || SshKeyDataCodec.decode(entry.sshKeyData) != null)
                    val data = SshKeyDataCodec.decode(entry.sshKeyData) ?: SshKeyData(algorithm = "ED25519", keySize = 256)
                    mapOf("algorithm" to data.algorithm, "keySize" to data.keySize.toString(), "format" to data.format,
                        "publicKeyOpenSsh" to data.publicKeyOpenSsh, "privateKeyOpenSsh" to data.privateKeyOpenSsh,
                        "fingerprintSha256" to data.fingerprintSha256, "comment" to data.comment)
                }
                else -> {
                    val raw = if (entry.wifiMetadata.isBlank()) emptyMap() else Json.parseToJsonElement(entry.wifiMetadata).jsonObject
                    mapOf("ssid" to (raw["ssid"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: entry.title), "password" to entry.password,
                        "security" to (raw["security"]?.jsonPrimitive?.content ?: "WPA2_WPA3"),
                        "hiddenNetwork" to (raw["hiddenNetwork"]?.jsonPrimitive?.content ?: "false"))
                }
            }
            return TemplateCredentialDraft(type, values, entry.wifiMetadata, entry.sshKeyData)
        }
    }
}
