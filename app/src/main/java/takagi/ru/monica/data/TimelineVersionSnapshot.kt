package takagi.ru.monica.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.concurrent.TimeUnit

internal const val TIMELINE_SNAPSHOT_RETENTION_DAYS = 30L
internal const val TIMELINE_SNAPSHOT_FIELD_ITEM_DATA = "__snapshot_item_data"
internal const val TIMELINE_SNAPSHOT_FIELD_NOTES = "__snapshot_notes"

internal fun isTimelineSnapshotInternalField(fieldName: String): Boolean =
    fieldName == TIMELINE_SNAPSHOT_FIELD_ITEM_DATA ||
        fieldName == TIMELINE_SNAPSHOT_FIELD_NOTES

internal fun timelineSnapshotCutoff(nowMillis: Long): Long =
    nowMillis - TimeUnit.DAYS.toMillis(TIMELINE_SNAPSHOT_RETENTION_DAYS)

internal fun areTimelineSnapshotFieldsReversible(
    itemType: String,
    fieldNames: List<String>
): Boolean {
    if (fieldNames.isEmpty()) return false
    val fields = fieldNames.toSet()
    return when (itemType) {
        "PASSWORD" -> fields.all(setOf("用户名", "网站", "密码", "备注", "标题")::contains)
        "TOTP" -> fields.all(setOf("标题", "备注")::contains)
        "NOTE" -> {
            fields.containsAll(
                setOf(TIMELINE_SNAPSHOT_FIELD_ITEM_DATA, TIMELINE_SNAPSHOT_FIELD_NOTES)
            ) && fields.all(
                setOf(
                    "标题",
                    "内容",
                    TIMELINE_SNAPSHOT_FIELD_ITEM_DATA,
                    TIMELINE_SNAPSHOT_FIELD_NOTES
                )::contains
            )
        }
        "BANK_CARD", "DOCUMENT", "BILLING_ADDRESS" -> {
            fields.contains(TIMELINE_SNAPSHOT_FIELD_ITEM_DATA) &&
                fields.none { field ->
                    field == "更新" ||
                        (field.startsWith("__") && !isTimelineSnapshotInternalField(field))
                }
        }
        else -> false
    }
}

/**
 * Encrypted before/after field values for a reversible timeline update.
 * This table is intentionally not part of the operation-log backup payload.
 */
@Entity(
    tableName = "timeline_version_snapshots",
    indices = [
        Index(value = ["operation_log_id"], unique = true),
        Index(value = ["created_at"]),
        Index(value = ["item_type", "item_id"])
    ]
)
data class TimelineVersionSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "operation_log_id")
    val operationLogId: Long,
    @ColumnInfo(name = "item_type")
    val itemType: String,
    @ColumnInfo(name = "item_id")
    val itemId: Long,
    @ColumnInfo(name = "operation_type")
    val operationType: String,
    @ColumnInfo(name = "encrypted_changes_json")
    val encryptedChangesJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
