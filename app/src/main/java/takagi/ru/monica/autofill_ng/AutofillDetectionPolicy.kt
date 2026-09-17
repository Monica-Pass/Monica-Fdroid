package takagi.ru.monica.autofill_ng

import java.util.Locale
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import takagi.ru.monica.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

internal object AutofillDetectionPolicy {
    private val explicitAccountTerms = listOf(
        "username", "user_name", "user name", "login", "account", "email", "e-mail",
        "用户名", "用戶名", "账号", "帐号", "賬號", "帳號", "账户", "帳戶", "賬戶",
        "工号", "工號", "学号", "學號", "职工号", "員工編號", "员工编号",
        "student id", "student_id", "studentid", "employee id", "employee_id", "employeeid",
        "邮箱", "郵箱", "電郵", "utilisateur", "identifiant", "логин", "логін",
    )
    private val passwordTerms = listOf(
        "password", "passwd", "pwd", "passcode", "密码", "密碼", "口令",
        "парол", "parol", "passwort", "passe", "contraseña", "パスワード", "비밀번호",
    )
    private val otpTerms = listOf(
        "otp", "2fa", "captcha", "verification code", "verification_code", "verificationcode",
        "verify code", "verify_code", "one-time", "one_time", "one time", "onetime",
        "sms code", "sms_code", "auth code", "auth_code", "验证码", "驗證碼",
        "校验码", "校驗碼", "动态码", "動態碼", "动态密码", "動態密碼",
        "一次性密码", "一次性密碼", "一次性口令",
    )
    private val searchTerms = listOf(
        "search", "搜索", "搜寻", "搜尋", "查询", "查詢", "查找", "recherche", "buscar", "suche",
    )

    fun matchesExplicitAccountField(value: String): Boolean =
        explicitAccountTerms.any { value.contains(it, ignoreCase = true) }

    fun matchesPasswordField(value: String): Boolean =
        passwordTerms.any { value.contains(it, ignoreCase = true) }

    fun matchesOtpField(value: String): Boolean =
        otpTerms.any { value.contains(it, ignoreCase = true) }

    fun matchesSearchField(value: String): Boolean =
        searchTerms.any { value.contains(it, ignoreCase = true) }

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
        if (matchesExplicitAccountField(normalized)) return true
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
