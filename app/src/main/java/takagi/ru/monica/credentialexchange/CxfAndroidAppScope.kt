package takagi.ru.monica.credentialexchange

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import takagi.ru.monica.data.LinkedAppBinding

/** Preserve the signed application scope when translating CXF into native custom fields. */
internal object CxfAndroidAppScope {
    const val FIELD_NAME = "CXF Android apps"

    fun restore(value: String?): JsonArray = value?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull()
    } ?: JsonArray(emptyList())

    fun bindings(context: Context, apps: JsonArray): List<LinkedAppBinding> = apps.mapNotNull { element ->
        val app = element as? JsonObject ?: return@mapNotNull null
        val bundleId = (app["bundleId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        if (bundleId.length > 255 || !bundleId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) return@mapNotNull null
        // A supplied certificate is a constraint, never a hint that can silently be discarded.
        if ("certificate" in app && !matchesCertificate(context, bundleId, app["certificate"] as? JsonObject)) return@mapNotNull null
        val name = (app["name"] as? JsonPrimitive)?.contentOrNull?.replace('|', ' ') ?: bundleId
        LinkedAppBinding(bundleId, name)
    }.distinctBy { it.packageName }

    @Suppress("DEPRECATION")
    private fun matchesCertificate(context: Context, bundleId: String, certificate: JsonObject?): Boolean = runCatching {
        certificate ?: return false
        val algorithm = when ((certificate["hashAlg"] as? JsonPrimitive)?.contentOrNull) {
            "sha256" -> "SHA-256"
            "sha512" -> "SHA-512"
            else -> return false
        }
        val expected = Base64.getUrlDecoder().decode((certificate["fingerprint"] as? JsonPrimitive)?.contentOrNull ?: return false)
        val info = context.packageManager.getPackageInfo(bundleId,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        signatures?.any { MessageDigest.isEqual(expected, MessageDigest.getInstance(algorithm).digest(it.toByteArray())) } == true
    }.getOrDefault(false)
}
