package takagi.ru.monica.webdav

import android.content.Context
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Stores explicit WebDAV certificate decisions per host. */
object WebDavCertificateTrustStore {
    private const val PREFS = "webdav_certificate_trust"
    private const val KEY_PREFIX = "sha256:"
    @Volatile private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun isTrusted(host: String, fingerprint: String): Boolean =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_PREFIX + normalizeHost(host), null)
            ?.equals(fingerprint, ignoreCase = true) == true

    fun trust(host: String, fingerprint: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_PREFIX + normalizeHost(host), fingerprint.uppercase())?.apply()
    }

    fun remove(host: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(KEY_PREFIX + normalizeHost(host))?.apply()
    }

    fun clearAll() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    private fun normalizeHost(host: String): String = host.trim().lowercase()
}
