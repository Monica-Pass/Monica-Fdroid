package takagi.ru.monica.utils

import android.content.Context
import android.os.Build
import androidx.fragment.app.FragmentActivity

/**
 * Compatibility facade for older call sites.
 *
 * All authentication work is delegated to [BiometricAuthHelper] so login,
 * Autofill and secondary unlock surfaces share the same low-latency system
 * prompt implementation and device-credential fallback policy.
 */
class BiometricHelper(context: Context) {

    private val delegate = BiometricAuthHelper(context.applicationContext)

    /** Returns true when a strong biometric is enrolled and currently usable. */
    fun isBiometricAvailable(): Boolean = delegate.isBiometricAvailable()

    fun hasBiometricEnrolled(): Boolean = delegate.isBiometricEnrolled()

    fun getBiometricStatusMessage(): String = delegate.getBiometricStatusMessage()

    fun authenticate(
        activity: FragmentActivity,
        title: String = "验证身份",
        subtitle: String? = "使用生物识别快速填充",
        description: String? = null,
        negativeButtonText: String = "取消",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onFailed: () -> Unit
    ) {
        delegate.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            description = description,
            negativeButtonText = negativeButtonText,
            onSuccess = onSuccess,
            onError = { _, message -> onError(message) },
            onCancel = onFailed
        )
    }

    fun isVersionSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    companion object {
        const val MIN_API_LEVEL = Build.VERSION_CODES.M
        const val RECOMMENDED_API_LEVEL = Build.VERSION_CODES.P
    }
}
