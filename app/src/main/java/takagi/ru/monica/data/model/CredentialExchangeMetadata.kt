package takagi.ru.monica.data.model

/** Persist signed CXF application scopes, but keep them out of editable/displayed user fields. */
object CredentialExchangeMetadata {
    const val ANDROID_APPS = "CXF Android apps"

    fun ownsField(title: String): Boolean = title == ANDROID_APPS
}
