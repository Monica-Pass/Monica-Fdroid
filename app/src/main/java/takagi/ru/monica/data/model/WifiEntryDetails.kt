package takagi.ru.monica.data.model

import kotlinx.serialization.json.*
import takagi.ru.monica.data.PasswordEntry

/** Read only the core display fields; unfamiliar advanced settings remain in the original JSON. */
data class WifiEntryDetails(val ssid: String, val securityName: String, val hidden: Boolean, val readable: Boolean) {
    val security: WifiSecurity? get() = WifiSecurity.entries.firstOrNull { it.name == securityName }
    fun qrData(): WifiData? = security?.takeIf { readable }?.let {
        WifiData(ssid = ssid, security = it, hiddenNetwork = hidden)
    }

    companion object {
        fun from(entry: PasswordEntry): WifiEntryDetails = runCatching {
            val raw = if (entry.wifiMetadata.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(entry.wifiMetadata).jsonObject
            fun text(key: String, default: String): String {
                val field = raw[key] ?: return default
                require(field is JsonPrimitive && field.isString)
                return field.content
            }
            val hidden = raw["hiddenNetwork"]?.let { requireNotNull(it.jsonPrimitive.booleanOrNull) } ?: false
            WifiEntryDetails(text("ssid", entry.title).ifBlank { entry.title },
                text("security", WifiSecurity.WPA2_WPA3.name), hidden, true)
        }.getOrElse { WifiEntryDetails(entry.title, "", false, false) }
    }
}
