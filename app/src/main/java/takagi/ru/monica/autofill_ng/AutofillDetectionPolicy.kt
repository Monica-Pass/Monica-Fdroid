package takagi.ru.monica.autofill_ng

import java.util.Locale
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

internal object AutofillDetectionPolicy {
    private val usernameLabelTranslations = listOf(
        "nickname",
        "username",
        "utilisateur",
        "login",
        "логин",
        "логін",
        "користувач",
        "пользовател",
        "用户名",
        "用戶名",
        "id",
        "customer",
    )

    fun genericNumberFallbackAccuracy(): Accuracy = Accuracy.LOW

    /**
     * Admission policy used only by the automatic compatibility reparse.
     * A weak account-like field must carry an explicit login term before it can
     * be promoted to normal automatic-fill confidence. Manual requests keep
     * their separate permissive path in [shouldKeepTarget].
     */
    fun automaticWeakAccountAccuracy(
        accuracy: Accuracy,
        hasLoginTerm: Boolean,
    ): Accuracy? = when {
        accuracy.score >= Accuracy.MEDIUM.score -> accuracy
        hasLoginTerm -> Accuracy.MEDIUM
        else -> null
    }

    /**
     * URL bars are always excluded. Other forced-off signals are controlled by
     * the user's existing "respect autofill-off" preference.
     */
    fun shouldSkipAutofillOffGroup(
        respectAutofillOff: Boolean,
        hasForcedOffSignal: Boolean,
        isAlwaysExcluded: Boolean,
    ): Boolean = isAlwaysExcluded || (respectAutofillOff && hasForcedOffSignal)

    fun shouldKeepTarget(
        hint: FieldHint,
        accuracy: Accuracy,
        hasPasswordTarget: Boolean,
        manualRequest: Boolean,
    ): Boolean {
        if (manualRequest) return true
        if (!isAccountHint(hint)) return true
        return accuracy.score >= Accuracy.MEDIUM.score || hasPasswordTarget
    }

    fun shouldIncludeHiddenCredential(
        hint: FieldHint,
        accuracy: Accuracy,
    ): Boolean {
        // 密码类是强登录信号，即便是低精度（如 VISIBLE_PASSWORD / NUMBER_PASSWORD
        // 变体映射为 LOW）且当前不可见，也应纳入解析，避免电影猎手这类 App 在聚焦
        // 账号框时因密码框尚未可见而被整体丢弃、导致密码填充失效。账号类仍需较高
        // 精度，避免把隐藏的搜索/备注等误判为登录账号（QQ 搜索框修复不受影响）。
        if (isPasswordHint(hint)) return accuracy.score >= Accuracy.LOWEST.score
        val credentialHint = isAccountHint(hint)
        return credentialHint && accuracy.score >= Accuracy.MEDIUM.score
    }

    fun matchesUsernameLabel(value: String): Boolean {
        val normalized = value.lowercase(Locale.ENGLISH).trim()
        if (normalized.isBlank()) return false
        return usernameLabelTranslations.any { translation ->
            if (translation == "id") {
                normalized
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .any { token -> token == translation }
            } else {
                translation in normalized
            }
        }
    }

    fun matchesPhoneFieldName(value: String): Boolean {
        val normalized = value.lowercase(Locale.ENGLISH).trim()
        if (normalized.isBlank()) return false
        if (
            "phone" in normalized ||
            "mobile" in normalized ||
            "telephone" in normalized ||
            "手机号" in normalized ||
            "手機號" in normalized ||
            "电话号码" in normalized ||
            "電話號碼" in normalized
        ) {
            return true
        }
        return normalized
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .any { token -> token == "tel" }
    }

    private fun isAccountHint(hint: FieldHint): Boolean =
        hint == FieldHint.USERNAME ||
            hint == FieldHint.EMAIL_ADDRESS ||
            hint == FieldHint.PHONE_NUMBER

    private fun isPasswordHint(hint: FieldHint): Boolean =
        hint == FieldHint.PASSWORD || hint == FieldHint.NEW_PASSWORD
}
