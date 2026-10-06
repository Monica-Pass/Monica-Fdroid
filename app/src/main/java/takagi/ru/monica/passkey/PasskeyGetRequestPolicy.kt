package takagi.ru.monica.passkey

import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.data.PasskeyEntry

/** A malformed allow-list must never turn into an unrestricted discovery request. */
internal data class PasskeyGetRequestPolicy(
    val rpId: String,
    val challenge: String,
    val allowedCredentialIds: Set<String>?,
) {
    fun allows(passkey: PasskeyEntry): Boolean =
        PasskeyRpIdNormalizer.isEquivalent(rpId, passkey.rpId) &&
            (allowedCredentialIds == null || PasskeyCredentialIdCodec.normalize(passkey.credentialId) in allowedCredentialIds)

    fun isSameCeremony(other: PasskeyGetRequestPolicy): Boolean =
        PasskeyRpIdNormalizer.isEquivalent(rpId, other.rpId) && challenge == other.challenge

    companion object {
        fun parse(requestJson: String): PasskeyGetRequestPolicy {
            val json = JSONObject(requestJson)
            val rpId = json.opt("rpId") as? String
            require(!rpId.isNullOrBlank()) { "Missing relying party ID" }
            val challenge = json.opt("challenge") as? String
            require(!challenge.isNullOrBlank()) { "Missing challenge" }
            var allowed: Set<String>? = null
            if (json.has("allowCredentials")) {
                val list = json.opt("allowCredentials") as? JSONArray
                    ?: throw IllegalArgumentException("Invalid allowCredentials")
                if (list.length() > 0) {
                    allowed = (0 until list.length()).map { index ->
                        val descriptor = list.optJSONObject(index)
                            ?: throw IllegalArgumentException("Invalid credential descriptor")
                        require(descriptor.opt("type") == "public-key") { "Invalid credential type" }
                        val id = descriptor.opt("id") as? String
                        require(id != null && PasskeyCredentialIdCodec.isValid(id)) { "Invalid credential ID" }
                        requireNotNull(PasskeyCredentialIdCodec.normalize(id))
                    }.toSet()
                }
            }
            return PasskeyGetRequestPolicy(rpId, challenge, allowed)
        }
    }
}
