package takagi.ru.monica.data

import java.util.Locale
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.data.model.EntryContentFields
import takagi.ru.monica.data.model.ProjectCredentialGroup

/** Explicit allowlist: passwords, PINs and identity/card numbers never become suggestions. */
enum class CommonSuggestionField(val key: String) {
    BANK_NAME("bankName"), BRANCH_CODE("branchCode"), CUSTOMER_SERVICE_PHONE("customerServicePhone"),
    CREDENTIAL_LABEL("credentialLabel"), WEBSITE("website"), ISSUED_BY("issuedBy");

    companion object {
        fun forSupplementalKey(key: String): CommonSuggestionField? = when (key) {
            "bankName" -> BANK_NAME
            "branchCode" -> BRANCH_CODE
            "customerServicePhone" -> CUSTOMER_SERVICE_PHONE
            "issuedBy" -> ISSUED_BY
            else -> null
        }
    }
}

/** Room projection intentionally excludes passwords and all other credential secrets. */
data class CommonSuggestionOwner(
    val id: Long, val website: String,
    val keepassDatabaseId: Long?, val bitwardenVaultId: Long?, val mdbxDatabaseId: Long?,
)

internal fun commonSuggestionSourceAccessible(
    keepassId: Long?, bitwardenId: Long?, mdbxId: Long?, sources: Set<String>,
): Boolean {
    if (listOfNotNull(keepassId, bitwardenId, mdbxId).size > 1) return false
    val source = when {
        keepassId != null -> "keepass:$keepassId"
        bitwardenId != null -> "bitwarden:$bitwardenId"
        mdbxId != null -> "mdbx:$mdbxId"
        else -> "local"
    }
    return source in sources
}

data class CommonFieldCandidate(val value: String, val uses: Int)

class CommonFieldSuggestionIndex(values: List<String>, field: CommonSuggestionField) {
    private val candidates = values.asSequence().map(String::trim).filter(String::isNotEmpty)
        // URL paths may be case sensitive. Matching ignores case, stored values do not.
        .groupBy { if (field == CommonSuggestionField.WEBSITE) it else it.lowercase(Locale.ROOT) }
        .values.map { CommonFieldCandidate(it.first(), it.size) }

    fun match(query: String, limit: Int = 3): List<CommonFieldCandidate> {
        val text = query.trim()
        if (text.isEmpty() || limit <= 0) return emptyList()
        return candidates.asSequence()
            .filter { it.value != text && it.value.contains(text, ignoreCase = true) }
            .sortedWith(compareByDescending<CommonFieldCandidate> { it.value.startsWith(text, ignoreCase = true) }
                .thenByDescending { it.uses }.thenBy { it.value.length }
                .thenBy { it.value.lowercase(Locale.ROOT) }.thenBy { it.value })
            .take(limit).toList()
    }
}

internal fun CommonSuggestionField.metadataTitles(): List<String> = when (this) {
    CommonSuggestionField.WEBSITE -> emptyList()
    CommonSuggestionField.CREDENTIAL_LABEL -> listOf(ProjectCredentialGroup.FIELD)
    CommonSuggestionField.ISSUED_BY -> listOf(EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.DOCUMENT), EntryContentFields.key("CONTACT", key))
    else -> listOf(EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.BANK_CARD), EntryContentFields.key("PAYMENT", key))
}

internal fun CommonSuggestionField.walletValue(raw: String): String? = when (this) {
    CommonSuggestionField.BANK_NAME -> CardWalletDataCodec.parseBankCardData(raw)?.bankName
    CommonSuggestionField.BRANCH_CODE -> CardWalletDataCodec.parseBankCardData(raw)?.branchCode
    CommonSuggestionField.CUSTOMER_SERVICE_PHONE -> CardWalletDataCodec.parseBankCardData(raw)?.customerServicePhone
    CommonSuggestionField.ISSUED_BY -> CardWalletDataCodec.parseDocumentData(raw)?.issuedBy
    else -> null
}

internal fun CommonSuggestionField.metadataValue(title: String, plain: String): String? {
    if (title !in metadataTitles()) return null
    if (this == CommonSuggestionField.CREDENTIAL_LABEL) return ProjectCredentialGroup.parse(plain)?.label
    if (title.startsWith(EmbeddedWalletContent.PREFIX)) {
        val snapshot = (EmbeddedWalletContent.read(plain) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot ?: return null
        val expected = if (this == CommonSuggestionField.ISSUED_BY) EmbeddedWalletContent.Kind.DOCUMENT else EmbeddedWalletContent.Kind.BANK_CARD
        return if (snapshot.kind == expected) walletValue(snapshot.itemData.toString()) else null
    }
    return plain
}
