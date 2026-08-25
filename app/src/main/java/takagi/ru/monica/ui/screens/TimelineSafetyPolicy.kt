package takagi.ru.monica.ui.screens

import takagi.ru.monica.data.model.DiffChange
import takagi.ru.monica.data.model.TimelineEvent

internal const val TIMELINE_REDACTED_VALUE = "<redacted>"

internal fun String.isTimelineRedactedValue(): Boolean =
    trim().equals(TIMELINE_REDACTED_VALUE, ignoreCase = true)

internal fun DiffChange.isTimelineSensitiveChange(): Boolean =
    oldValue.isTimelineRedactedValue() ||
        newValue.isTimelineRedactedValue() ||
        fieldName.isTimelinePasswordField()

internal fun String.isTimelinePasswordField(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "password" || normalized == "密码"
}

internal fun shouldMaskTimelineSnapshotField(itemType: String, fieldName: String): Boolean {
    if (fieldName == "标题") return false
    return itemType in setOf(
        "PASSWORD",
        "TOTP",
        "BANK_CARD",
        "DOCUMENT",
        "BILLING_ADDRESS",
        "PAYMENT_ACCOUNT",
        "NOTE"
    )
}

internal fun isInternalTimelineTitle(
    summary: String,
    itemType: String,
    itemId: Long
): Boolean = summary == "$itemType#$itemId"

internal fun resolveTimelineDisplaySummary(
    log: TimelineEvent.StandardLog,
    currentTitle: String?,
    genericTypeLabel: String
): String {
    if (!isInternalTimelineTitle(log.summary, log.itemType, log.itemId)) {
        return log.summary
    }
    return currentTitle?.takeIf(String::isNotBlank) ?: genericTypeLabel
}
