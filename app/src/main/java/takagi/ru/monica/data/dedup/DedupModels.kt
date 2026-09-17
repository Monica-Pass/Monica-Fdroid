package takagi.ru.monica.data.dedup

import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.SecureItem

enum class DedupMergeSourceKind {
    MONICA_LOCAL,
    MDBX,
    KEEPASS,
    BITWARDEN
}

data class DedupMergeSourceOption(
    val key: String,
    val kind: DedupMergeSourceKind,
    val label: String,
    val passwordCount: Int,
    val secureItemCount: Int = 0,
    val passkeyCount: Int = 0
)

sealed class DedupMergeTarget {
    data object MonicaLocal : DedupMergeTarget()
    data class MdbxDatabase(
        val databaseId: Long,
        val label: String
    ) : DedupMergeTarget()
}

data class DedupMergeTargetOption(
    val target: DedupMergeTarget,
    val sourceKey: String,
    val label: String,
    val passwordCount: Int,
    val secureItemCount: Int = 0,
    val passkeyCount: Int = 0
)

enum class DedupConflictPolicy {
    MOST_COMPLETE,
    NEWEST
}

enum class DedupMergeValidationIssue {
    NEED_SOURCES,
    NEED_TARGET,
    TARGET_IS_SOURCE,
    NOTHING_TO_WRITE
}

data class DedupMergeValidation(
    val issues: Set<DedupMergeValidationIssue>
) {
    val canReview: Boolean
        get() = issues.none {
            it == DedupMergeValidationIssue.NEED_SOURCES ||
                it == DedupMergeValidationIssue.NEED_TARGET ||
                it == DedupMergeValidationIssue.TARGET_IS_SOURCE
        }

    val canExecute: Boolean
        get() = issues.isEmpty()
}

data class DedupMergeSelection(
    val sourceKeys: Set<String> = emptySet(),
    val targetOption: DedupMergeTargetOption? = null
) {
    fun toggleSource(sourceKey: String): DedupMergeSelection {
        if (sourceKey in sourceKeys) {
            return copy(sourceKeys = sourceKeys - sourceKey)
        }
        return copy(
            sourceKeys = sourceKeys + sourceKey,
            targetOption = targetOption?.takeUnless { it.sourceKey == sourceKey }
        )
    }

    fun selectTarget(option: DedupMergeTargetOption): DedupMergeSelection = copy(
        sourceKeys = sourceKeys - option.sourceKey,
        targetOption = option
    )

    fun selectAll(availableSourceKeys: Set<String>): DedupMergeSelection = copy(
        sourceKeys = availableSourceKeys - setOfNotNull(targetOption?.sourceKey)
    )

    fun validate(writableItems: Int): DedupMergeValidation {
        val issues = buildSet {
            if (sourceKeys.size < MINIMUM_SOURCE_DATABASES) {
                add(DedupMergeValidationIssue.NEED_SOURCES)
            }
            if (targetOption == null) {
                add(DedupMergeValidationIssue.NEED_TARGET)
            }
            if (targetOption?.sourceKey in sourceKeys) {
                add(DedupMergeValidationIssue.TARGET_IS_SOURCE)
            }
            if (writableItems <= 0) {
                add(DedupMergeValidationIssue.NOTHING_TO_WRITE)
            }
        }
        return DedupMergeValidation(issues)
    }

    companion object {
        const val MINIMUM_SOURCE_DATABASES = 1
    }
}

data class DedupResolvedPassword(
    val mergeKey: String,
    val entry: PasswordEntry,
    val customFields: List<CustomField>,
    val sourceEntryIds: List<Long>,
    val sourceLabels: List<String>,
    val conflictFields: Set<String>,
    val existsInTarget: Boolean = false,
    val targetHasDifferentContent: Boolean = false,
    val preferredSourceLabel: String = "",
    val attachments: List<DedupAttachmentRef> = emptyList()
)

data class DedupResolvedSecureItem(
    val mergeKey: String,
    val item: SecureItem,
    val sourceItemIds: List<Long>,
    val sourceLabels: List<String>,
    val conflictFields: Set<String>,
    val existsInTarget: Boolean = false,
    val targetHasDifferentContent: Boolean = false,
    val preferredSourceLabel: String = "",
    val attachments: List<DedupAttachmentRef> = emptyList()
)

data class DedupAttachmentRef(
    val attachmentId: Long,
    val contentKey: String,
    val readable: Boolean = true
)

enum class DedupPasskeySkipReason {
    REFERENCE_OR_MISSING_KEY, NONZERO_COUNTER, BOUND_PASSWORD, DEVICE_KEY, CREDENTIAL_CONFLICT
}

data class DedupResolvedPasskey(
    val mergeKey: String,
    val entry: PasskeyEntry,
    val sourceEntryIds: List<Long>,
    val sourceLabels: List<String>,
    val preferredSourceLabel: String,
    val existsInTarget: Boolean = false,
    val skipReason: DedupPasskeySkipReason? = null,
) {
    val writable: Boolean get() = !existsInTarget && skipReason == null
}

data class DedupMergePlan(
    val selectedSources: List<DedupMergeSourceOption> = emptyList(),
    val target: DedupMergeTarget? = null,
    val conflictPolicy: DedupConflictPolicy = DedupConflictPolicy.MOST_COMPLETE,
    val totalSourcePasswords: Int = 0,
    val totalSourceSecureItems: Int = 0,
    val unsupportedSourcePasskeys: Int = 0,
    val totalSourcePasskeys: Int = unsupportedSourcePasskeys,
    val uniquePasswords: Int = 0,
    val uniqueSecureItems: Int = 0,
    val duplicateGroups: Int = 0,
    val duplicateSecureItemGroups: Int = 0,
    val passwordConflictGroups: Int = 0,
    val secureItemConflictGroups: Int = 0,
    val targetExistingDuplicates: Int = 0,
    val targetExistingSecureItems: Int = 0,
    val previewPasswords: List<DedupResolvedPassword> = emptyList(),
    val previewSecureItems: List<DedupResolvedSecureItem> = emptyList(),
    val previewPasskeys: List<DedupResolvedPasskey> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val writablePasswords: Int
        get() = (uniquePasswords - targetExistingDuplicates).coerceAtLeast(0)

    val writableSecureItems: Int
        get() = (uniqueSecureItems - targetExistingSecureItems).coerceAtLeast(0)

    val writableItems: Int
        get() = writablePasswords + writableSecureItems + previewPasskeys.count { it.writable }

    val targetExistingPasskeys: Int
        get() = previewPasskeys.count { it.existsInTarget }

    val previewItemCount: Int
        get() = previewPasswords.size + previewSecureItems.size + previewPasskeys.size

    val totalSourceItems: Int
        get() = totalSourcePasswords + totalSourceSecureItems + totalSourcePasskeys

    val duplicateGroupsTotal: Int
        get() = duplicateGroups + duplicateSecureItemGroups + previewPasskeys.count { it.sourceEntryIds.size > 1 }

    val conflictGroupsTotal: Int
        get() = passwordConflictGroups + secureItemConflictGroups

    val skippedItems: Int
        get() = targetExistingDuplicates + targetExistingSecureItems + targetExistingPasskeys + unsupportedSourcePasskeys

    val consolidatedCopies: Int
        get() = (totalSourcePasswords + totalSourceSecureItems - uniquePasswords - uniqueSecureItems).coerceAtLeast(0) +
            previewPasskeys.sumOf { (it.sourceEntryIds.size - 1).coerceAtLeast(0) }

    val reviewConflictGroups: Int
        get() = previewPasswords.count { it.conflictFields.isNotEmpty() || it.targetHasDifferentContent } +
            previewSecureItems.count { it.conflictFields.isNotEmpty() || it.targetHasDifferentContent } +
            previewPasskeys.count { it.skipReason == DedupPasskeySkipReason.CREDENTIAL_CONFLICT }
}

enum class DedupMergeItemKind {
    PASSWORD,
    SECURE_ITEM,
    PASSKEY
}

data class DedupMergeFailure(
    val kind: DedupMergeItemKind,
    val label: String,
    val reason: String
)

data class DedupMergeExecutionProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentLabel: String
) {
    val fraction: Float
        get() = if (totalItems <= 0) 0f else completedItems.toFloat() / totalItems.toFloat()
}

data class DedupMergeExecutionResult(
    val insertedPasswords: Int,
    val insertedSecureItems: Int = 0,
    val insertedPasskeys: Int = 0,
    val skippedExistingPasswords: Int,
    val skippedExistingSecureItems: Int = 0,
    val skippedExistingPasskeys: Int = 0,
    val skippedUnsupportedPasskeys: Int = 0,
    val failedPasswords: Int,
    val failedSecureItems: Int = 0,
    val failedPasskeys: Int = 0,
    val targetLabel: String,
    val failures: List<DedupMergeFailure> = emptyList(),
    val cancelled: Boolean = false
) {
    val insertedItems: Int
        get() = insertedPasswords + insertedSecureItems + insertedPasskeys

    val skippedExistingItems: Int
        get() = skippedExistingPasswords + skippedExistingSecureItems + skippedExistingPasskeys

    val failedItems: Int
        get() = failedPasswords + failedSecureItems + failedPasskeys

    val hasPartialFailure: Boolean
        get() = failedItems > 0 && insertedItems > 0
}
