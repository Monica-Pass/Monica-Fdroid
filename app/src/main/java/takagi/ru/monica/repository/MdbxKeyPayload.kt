package takagi.ru.monica.repository

import org.json.JSONObject

/** Raw portable JSON; encryption belongs to the vault envelope, not the SSH field. */
internal fun JSONObject.readMdbxSshKeyData(existing: String = ""): String {
    val key = when {
        has("ssh_key_data") && !isNull("ssh_key_data") -> "ssh_key_data"
        has("sshKeyData") && !isNull("sshKeyData") -> "sshKeyData"
        else -> return existing // Old Android payloads did not contain key material.
    }
    return when (val value = get(key)) {
        is String -> value // Includes an explicit empty value, which clears the field.
        is JSONObject -> value.toString() // Tolerate object-valued producers on import.
        else -> throw IllegalArgumentException("Invalid SSH key field type")
    }
}
