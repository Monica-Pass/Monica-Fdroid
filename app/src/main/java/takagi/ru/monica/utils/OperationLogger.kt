package takagi.ru.monica.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.data.OperationLog
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.OperationType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.TimelineVersionSnapshot
import takagi.ru.monica.data.timelineSnapshotCutoff
import takagi.ru.monica.security.SecurityManager

/**
 * 操作日志记录工具类。
 *
 * Startup rule: [init] must remain cheap. Room access, Android ID reads,
 * SecurityManager construction, redaction and JSON serialization are deferred to
 * the existing IO logger scope and therefore never compete with the first frame.
 */
object OperationLogger {

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var database: PasswordDatabase? = null
    @Volatile
    private var securityManager: SecurityManager? = null
    @Volatile
    private var deviceId: String? = null
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    @Volatile
    private var lastSnapshotCleanupAt: Long = 0L
    private val scope = CoroutineScope(Dispatchers.IO)

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    /**
     * Only publish the application context. Expensive dependencies are created
     * lazily on the logger's IO dispatcher when the first operation is emitted.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun logCreate(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        details: List<FieldChange> = emptyList()
    ) {
        log(itemType, itemId, itemTitle, OperationType.CREATE, details)
    }

    fun logUpdate(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        changes: List<FieldChange>,
        snapshotChanges: List<FieldChange> = changes
    ) {
        if (changes.isEmpty()) return
        log(
            itemType = itemType,
            itemId = itemId,
            itemTitle = itemTitle,
            operationType = OperationType.UPDATE,
            changes = changes,
            snapshotChanges = snapshotChanges
        )
    }

    fun logDelete(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        detail: String? = null
    ) {
        log(
            itemType = itemType,
            itemId = itemId,
            itemTitle = if (detail != null) "$itemTitle ($detail)" else itemTitle,
            operationType = OperationType.DELETE,
            changes = emptyList()
        )
    }

    fun logWebDavUpload(
        isAutomatic: Boolean,
        isPermanent: Boolean,
        details: List<FieldChange> = emptyList()
    ) {
        val triggerType = if (isAutomatic) "自动" else "手动"
        val backupType = if (isPermanent) "永久" else "临时"
        log(
            itemType = OperationLogItemType.WEBDAV_UPLOAD,
            itemId = System.currentTimeMillis(),
            itemTitle = "${triggerType}上传 · $backupType",
            operationType = OperationType.SYNC,
            changes = details
        )
    }

    fun logWebDavDownload(addedItems: List<FieldChange> = emptyList()) {
        log(
            itemType = OperationLogItemType.WEBDAV_DOWNLOAD,
            itemId = System.currentTimeMillis(),
            itemTitle = "同步下载",
            operationType = OperationType.SYNC,
            changes = addedItems
        )
    }

    fun logSync(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        details: List<FieldChange> = emptyList()
    ) {
        log(itemType, itemId, itemTitle, OperationType.SYNC, details)
    }

    private fun log(
        itemType: OperationLogItemType,
        itemId: Long,
        itemTitle: String,
        operationType: OperationType,
        changes: List<FieldChange>,
        snapshotChanges: List<FieldChange> = changes
    ) {
        val immutableChanges = changes.toList()
        val immutableSnapshotChanges = snapshotChanges.toList()

        scope.launch {
            val db = databaseOrNull()
            if (db == null) {
                android.util.Log.e("OperationLogger", "Logger context is unavailable; init() was not called")
                return@launch
            }

            try {
                android.util.Log.d("OperationLogger", "Logging $operationType for $itemType")
                val sanitizedChanges = sanitizeChanges(itemType, immutableChanges)
                val changesJson = if (sanitizedChanges.isNotEmpty()) {
                    json.encodeToString(sanitizedChanges)
                } else {
                    ""
                }
                val operationLog = OperationLog(
                    itemType = itemType.name,
                    itemId = itemId,
                    itemTitle = sanitizeItemTitle(itemType, itemTitle, itemId),
                    operationType = operationType.name,
                    changesJson = changesJson,
                    deviceId = deviceId(),
                    deviceName = deviceName,
                    timestamp = System.currentTimeMillis()
                )

                db.withTransaction {
                    val operationLogId = db.operationLogDao().insert(operationLog)
                    persistEncryptedVersionSnapshot(
                        database = db,
                        operationLog = operationLog,
                        operationLogId = operationLogId,
                        changes = immutableSnapshotChanges
                    )
                }
                android.util.Log.d("OperationLogger", "Successfully logged operation")
            } catch (e: Exception) {
                android.util.Log.e("OperationLogger", "Failed to log operation", e)
            }
        }
    }

    private fun databaseOrNull(): PasswordDatabase? {
        database?.let { return it }
        val context = appContext ?: return null
        return synchronized(this) {
            database ?: PasswordDatabase.getDatabase(context).also { database = it }
        }
    }

    private fun securityManagerOrNull(): SecurityManager? {
        securityManager?.let { return it }
        val context = appContext ?: return null
        return synchronized(this) {
            securityManager ?: SecurityManager(context).also { securityManager = it }
        }
    }

    private fun deviceId(): String {
        deviceId?.let { return it }
        val context = appContext ?: return "unknown"
        val resolved = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        deviceId = resolved
        return resolved
    }

    private suspend fun persistEncryptedVersionSnapshot(
        database: PasswordDatabase,
        operationLog: OperationLog,
        operationLogId: Long,
        changes: List<FieldChange>
    ) {
        if (operationLog.operationType != OperationType.UPDATE.name) return
        if (operationLog.itemType !in REVERSIBLE_ITEM_TYPES) return

        val snapshotChanges = changes.filter { change ->
            !change.fieldName.startsWith("__") ||
                takagi.ru.monica.data.isTimelineSnapshotInternalField(change.fieldName)
        }
        if (snapshotChanges.isEmpty()) return
        if (snapshotChanges.any { change ->
                change.oldValue.trim().equals("<redacted>", ignoreCase = true) ||
                    change.newValue.trim().equals("<redacted>", ignoreCase = true)
            }
        ) {
            android.util.Log.w("OperationLogger", "Skipped incomplete encrypted timeline snapshot")
            return
        }
        if (!takagi.ru.monica.data.areTimelineSnapshotFieldsReversible(
                operationLog.itemType,
                snapshotChanges.map(FieldChange::fieldName)
            )
        ) {
            android.util.Log.d("OperationLogger", "Skipped non-reversible timeline snapshot")
            return
        }

        val manager = securityManagerOrNull() ?: return
        val encrypted = runCatching {
            manager.encryptTimelineSnapshot(json.encodeToString(snapshotChanges))
        }.getOrElse {
            android.util.Log.w("OperationLogger", "Skipped encrypted timeline snapshot", it)
            return
        }

        runCatching {
            database.timelineVersionSnapshotDao().insert(
                TimelineVersionSnapshot(
                    operationLogId = operationLogId,
                    itemType = operationLog.itemType,
                    itemId = operationLog.itemId,
                    operationType = operationLog.operationType,
                    encryptedChangesJson = encrypted,
                    createdAt = operationLog.timestamp
                )
            )
            val now = System.currentTimeMillis()
            if (now - lastSnapshotCleanupAt >= SNAPSHOT_CLEANUP_INTERVAL_MILLIS) {
                database.timelineVersionSnapshotDao().deleteOlderThan(timelineSnapshotCutoff(now))
                lastSnapshotCleanupAt = now
            }
        }.onFailure {
            android.util.Log.w("OperationLogger", "Failed to persist encrypted timeline snapshot", it)
        }
    }

    private val REVERSIBLE_ITEM_TYPES = setOf(
        OperationLogItemType.PASSWORD.name,
        OperationLogItemType.TOTP.name,
        OperationLogItemType.BANK_CARD.name,
        OperationLogItemType.DOCUMENT.name,
        OperationLogItemType.BILLING_ADDRESS.name,
        OperationLogItemType.NOTE.name
    )
    private const val SNAPSHOT_CLEANUP_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

    private fun sanitizeItemTitle(
        itemType: OperationLogItemType,
        itemTitle: String,
        itemId: Long
    ): String = if (itemType.requiresSensitiveLogRedaction()) {
        "${itemType.name}#$itemId"
    } else {
        itemTitle
    }

    private fun sanitizeChanges(
        itemType: OperationLogItemType,
        changes: List<FieldChange>
    ): List<FieldChange> {
        if (changes.isEmpty()) return emptyList()
        return changes.map { change ->
            if (itemType.requiresSensitiveLogRedaction() || change.fieldName.isSensitiveFieldName()) {
                change.copy(
                    oldValue = redactedValue(change.oldValue),
                    newValue = redactedValue(change.newValue)
                )
            } else {
                change
            }
        }
    }

    private fun OperationLogItemType.requiresSensitiveLogRedaction(): Boolean = this in setOf(
        OperationLogItemType.PASSWORD,
        OperationLogItemType.TOTP,
        OperationLogItemType.PASSKEY,
        OperationLogItemType.BANK_CARD,
        OperationLogItemType.DOCUMENT,
        OperationLogItemType.BILLING_ADDRESS,
        OperationLogItemType.PAYMENT_ACCOUNT,
        OperationLogItemType.NOTE
    )

    private fun String.isSensitiveFieldName(): Boolean {
        val normalized = trim().lowercase()
        return listOf(
            "password",
            "密码",
            "secret",
            "token",
            "private",
            "私钥",
            "内容",
            "备注",
            "卡号",
            "证件号",
            "cvv",
            "totp",
            "验证器",
            "主密码"
        ).any { normalized.contains(it.lowercase()) }
    }

    private fun redactedValue(value: String): String = if (value.isBlank()) "" else "<redacted>"

    fun <T> compareAndGetChanges(
        old: T?,
        new: T,
        fields: List<Pair<String, (T) -> String?>>
    ): List<FieldChange> {
        if (old == null) return emptyList()
        return fields.mapNotNull { (fieldName, getter) ->
            val oldValue = getter(old) ?: ""
            val newValue = getter(new) ?: ""
            if (oldValue != newValue) {
                FieldChange(fieldName, oldValue, newValue)
            } else {
                null
            }
        }
    }
}

@Serializable
data class FieldChange(
    val fieldName: String,
    val oldValue: String,
    val newValue: String
)
