package takagi.ru.monica.external

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpUriParser

data class ExternalTotpImportRequest(
    val id: Long,
    val uri: String
)

class ExternalTotpImportController(
    private val uriValidator: (String) -> Boolean = ::isSupportedSingleOtpAuthUri
) {
    companion object {
        private const val MAX_URI_LENGTH = 16_384

        fun isExternalOtpAuthIntent(intent: Intent?): Boolean {
            return intent?.action == Intent.ACTION_VIEW &&
                intent.data?.scheme.equals("otpauth", ignoreCase = true)
        }

        private fun isSupportedSingleOtpAuthUri(raw: String): Boolean {
            val parsed = TotpUriParser.parseUri(raw) ?: return false
            val data = TotpDataResolver.normalizeTotpData(parsed.totpData)
            val secret = TotpDataResolver.normalizeBase32Secret(data.secret)
            if (secret.isBlank() || secret.length > 4_096) return false
            if (!secret.matches(Regex("^[A-Z2-7]+=*$"))) return false
            if (data.algorithm.uppercase() !in setOf("SHA1", "SHA256", "SHA512")) return false
            if (data.digits !in 4..10) return false
            if (data.period !in 5..300) return false
            if (data.counter < 0L) return false
            return true
        }
    }

    private val _pendingRequest = MutableStateFlow<ExternalTotpImportRequest?>(null)
    val pendingRequest: StateFlow<ExternalTotpImportRequest?> = _pendingRequest.asStateFlow()

    private var nextRequestId = 0L

    fun offer(intent: Intent?): Boolean {
        if (!isExternalOtpAuthIntent(intent)) return false
        return offerUri(intent?.dataString)
    }

    @Synchronized
    internal fun offerUri(raw: String?): Boolean {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isBlank() || normalized.length > MAX_URI_LENGTH) return false
        if (!normalized.startsWith("otpauth://", ignoreCase = true)) return false
        if (!uriValidator(normalized)) return false

        nextRequestId = if (nextRequestId == Long.MAX_VALUE) 1L else nextRequestId + 1L
        _pendingRequest.value = ExternalTotpImportRequest(
            id = nextRequestId,
            uri = normalized
        )
        return true
    }

    @Synchronized
    fun consume(requestId: Long) {
        if (_pendingRequest.value?.id == requestId) {
            _pendingRequest.value = null
        }
    }
}
