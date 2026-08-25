package takagi.ru.monica.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.OperationLog
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.areTimelineSnapshotFieldsReversible
import takagi.ru.monica.data.maintenance.MaintenanceSnapshotManager
import takagi.ru.monica.data.model.DiffChange
import takagi.ru.monica.data.model.TIMELINE_FIELD_BATCH_COPY_PAYLOAD
import takagi.ru.monica.data.model.TIMELINE_FIELD_BATCH_MOVE_PAYLOAD
import takagi.ru.monica.data.model.TIMELINE_FIELD_MAINTENANCE_SNAPSHOT_PAYLOAD
import takagi.ru.monica.data.model.TimelineBatchCopyPayload
import takagi.ru.monica.data.model.TimelineMaintenanceSnapshotPayload
import takagi.ru.monica.data.model.TimelineBatchMovePayload
import takagi.ru.monica.data.model.TimelineBranch
import takagi.ru.monica.data.model.TimelineEvent
import takagi.ru.monica.repository.OperationLogRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.FieldChange
import java.util.Date

/**
 * Timeline ViewModel - 管理时间线事件的状态
 * 从 OperationLogRepository 加载真实数据
 */
class TimelineViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = PasswordDatabase.getDatabase(application)
    private val repository = OperationLogRepository(database.operationLogDao())
    private val passwordRepository = PasswordRepository(database.passwordEntryDao())
    private val secureItemRepository = SecureItemRepository(database.secureItemDao())
    private val bitwardenRepository = BitwardenRepository.getInstance(application)
    private val securityManager = SecurityManager(application)
    private val maintenanceSnapshotManager = MaintenanceSnapshotManager(database)
    
    private val _timelineEvents = MutableStateFlow<List<TimelineEvent>>(emptyList())
    val timelineEvents: StateFlow<List<TimelineEvent>> = _timelineEvents.asStateFlow()
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _maintenanceRestoreMessage = MutableStateFlow<String?>(null)
    val maintenanceRestoreMessage: StateFlow<String?> = _maintenanceRestoreMessage.asStateFlow()
    
    private val json = Json { ignoreUnknownKeys = true }
    private val app = getApplication<Application>()
    
    init {
        loadTimelineData()
    }
    
    /**
     * 从数据库加载时间线数据
     */
    private fun loadTimelineData() {
        viewModelScope.launch {
            repository.getRecentLogs(100).collectLatest { logs ->
                _isLoading.value = false
                val snapshotByLogId = withContext(Dispatchers.IO) {
                    database.timelineVersionSnapshotDao()
                        .getByOperationLogIds(logs.mapNotNull { it.id.takeIf { id -> id > 0 } })
                        .associateBy { it.operationLogId }
                }
                _timelineEvents.value = convertLogsToEvents(logs, snapshotByLogId)
            }
        }
    }
    
    /**
     * 将 OperationLog 列表转换为 TimelineEvent 列表
     */
    private fun convertLogsToEvents(
        logs: List<OperationLog>,
        snapshotsByLogId: Map<Long, takagi.ru.monica.data.TimelineVersionSnapshot> = emptyMap()
    ): List<TimelineEvent> {
        return logs.map { log ->
            val rawChanges = parseRawChangesIncludingTechnical(log.changesJson)
            val changes = parseChanges(rawChanges)
            val summary = generateSummary(log)
            val snapshot = snapshotsByLogId[log.id]
            val hasRedactedChanges = rawChanges.any { change ->
                change.oldValue.isRedactedTimelineValue() || change.newValue.isRedactedTimelineValue()
            }
            
            TimelineEvent.StandardLog(
                id = log.id.toString(),
                timestamp = log.timestamp,
                deviceId = log.deviceId,
                summary = summary,
                itemId = log.itemId,
                itemType = log.itemType,
                operationType = log.operationType,
                isReverted = log.isReverted,
                changes = changes,
                canRevert = snapshot != null || hasValidRestorablePayload(rawChanges),
                canSaveOldAsNew = snapshot != null,
                hasRedactedChanges = hasRedactedChanges,
                snapshotId = snapshot?.id,
                hasEncryptedSnapshot = snapshot != null
            )
        }
    }

    suspend fun readEncryptedSnapshot(logId: String): List<DiffChange>? = withContext(Dispatchers.IO) {
        val operationLogId = logId.toLongOrNull() ?: return@withContext null
        val snapshot = database.timelineVersionSnapshotDao().getByOperationLogId(operationLogId)
            ?: return@withContext null
        runCatching {
            json.decodeFromString<List<FieldChange>>(
                securityManager.decryptTimelineSnapshot(snapshot.encryptedChangesJson)
            ).map { change ->
                DiffChange(
                    fieldName = change.fieldName,
                    oldValue = change.oldValue,
                    newValue = change.newValue
                )
            }
        }.getOrNull()
    }
    
    /**
     * 解析 JSON 格式的变更记录
     */
    private fun parseChanges(changesJson: String): List<DiffChange> {
        if (changesJson.isEmpty()) return emptyList()

        return parseChanges(parseRawChangesIncludingTechnical(changesJson))
    }

    private fun parseChanges(fieldChanges: List<FieldChange>): List<DiffChange> {
        return try {
            fieldChanges.mapNotNull { fc ->
                when (fc.fieldName) {
                    TIMELINE_FIELD_BATCH_MOVE_PAYLOAD -> DiffChange(
                        fieldName = app.getString(R.string.timeline_field_batch_move),
                        oldValue = "",
                        newValue = app.getString(R.string.timeline_snapshot_ready)
                    )
                    TIMELINE_FIELD_BATCH_COPY_PAYLOAD -> DiffChange(
                        fieldName = app.getString(R.string.timeline_field_batch_copy),
                        oldValue = "",
                        newValue = app.getString(R.string.timeline_snapshot_ready)
                    )
                    TIMELINE_FIELD_MAINTENANCE_SNAPSHOT_PAYLOAD -> DiffChange(
                        fieldName = app.getString(R.string.timeline_field_maintenance_snapshot),
                        oldValue = "",
                        newValue = app.getString(R.string.timeline_snapshot_ready)
                    )
                    else -> {
                        if (fc.fieldName.startsWith("__")) {
                            null
                        } else {
                            DiffChange(
                                fieldName = fc.fieldName,
                                oldValue = fc.oldValue,
                                newValue = fc.newValue
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun hasValidRestorablePayload(changes: List<FieldChange>): Boolean {
        val batchMovePayload = changes.firstOrNull {
            it.fieldName == TIMELINE_FIELD_BATCH_MOVE_PAYLOAD
        }?.newValue
        if (!batchMovePayload.isNullOrBlank() && !batchMovePayload.isRedactedTimelineValue()) {
            val payload = runCatching {
                json.decodeFromString<TimelineBatchMovePayload>(batchMovePayload)
            }.getOrNull()
            if (payload != null && payload.oldStates.isNotEmpty()) return true
        }

        val batchCopyPayload = changes.firstOrNull {
            it.fieldName == TIMELINE_FIELD_BATCH_COPY_PAYLOAD
        }?.newValue
        if (!batchCopyPayload.isNullOrBlank() && !batchCopyPayload.isRedactedTimelineValue()) {
            val payload = runCatching {
                json.decodeFromString<TimelineBatchCopyPayload>(batchCopyPayload)
            }.getOrNull()
            if (payload != null && payload.copiedEntryIds.isNotEmpty()) return true
        }

        val maintenancePayload = changes.firstOrNull {
            it.fieldName == TIMELINE_FIELD_MAINTENANCE_SNAPSHOT_PAYLOAD
        }?.newValue
        if (!maintenancePayload.isNullOrBlank() && !maintenancePayload.isRedactedTimelineValue()) {
            val payload = runCatching {
                json.decodeFromString<TimelineMaintenanceSnapshotPayload>(maintenancePayload)
            }.getOrNull()
            if (
                payload != null &&
                (
                    payload.passwordIds.isNotEmpty() ||
                        payload.secureItemIds.isNotEmpty() ||
                        payload.passwordRows.isNotEmpty() ||
                        payload.secureItemRows.isNotEmpty() ||
                        payload.passwordRowsCompressedChunks.isNotEmpty() ||
                        payload.secureItemRowsCompressedChunks.isNotEmpty()
                    )
            ) {
                return true
            }
        }

        return false
    }

    private fun String.isRedactedTimelineValue(): Boolean =
        trim().equals("<redacted>", ignoreCase = true)

    private fun parseRawChanges(changesJson: String): List<FieldChange> {
        if (changesJson.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<FieldChange>>(changesJson)
                .filterNot { it.fieldName.startsWith("__") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRawChangesIncludingTechnical(changesJson: String): List<FieldChange> {
        if (changesJson.isBlank()) return emptyList()
        return try {
            json.decodeFromString(changesJson)
        } catch (_: Exception) {
            emptyList()
        }
    }
    
    /**
     * 根据操作类型生成摘要 - 只显示项目标题
     */
    private fun generateSummary(log: OperationLog): String {
        return log.itemTitle
    }
    
    /**
     * 恢复到指定分支版本
     */
    fun restoreVersion(branch: TimelineBranch) {
        // TODO: 实现实际的恢复逻辑
    }
    
    /**
     * 保存为新条目
     */
    fun saveAsNewEntry(branch: TimelineBranch) {
        // TODO: 实现实际的保存逻辑
    }
    
    /**
     * 恢复编辑操作 - 将条目恢复到编辑前的状态
     * @param log 要恢复的日志记录
     * @param onResult 回调函数，返回是否成功
     */
    fun revertEdit(log: TimelineEvent.StandardLog, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val logId = log.id.toLongOrNull() ?: return@launch onResult(false)
                val itemId = log.itemId
                val rawLog = database.operationLogDao().getLogById(logId) ?: return@launch onResult(false)
                val isCurrentlyReverted = rawLog.isReverted
                val rawChanges = parseRawChangesIncludingTechnical(rawLog.changesJson)

                if (log.hasEncryptedSnapshot) {
                    val snapshotChanges = readEncryptedSnapshot(log.id)
                        ?: return@launch onResult(false)
                    val restored = revertEncryptedSnapshot(
                        log = log,
                        rawLog = rawLog,
                        changes = snapshotChanges
                    )
                    return@launch onResult(restored)
                }

                val batchMovePayload = rawChanges.firstOrNull {
                    it.fieldName == TIMELINE_FIELD_BATCH_MOVE_PAYLOAD
                }?.newValue
                val batchCopyPayload = rawChanges.firstOrNull {
                    it.fieldName == TIMELINE_FIELD_BATCH_COPY_PAYLOAD
                }?.newValue
                val maintenanceSnapshotPayload = rawChanges.firstOrNull {
                    it.fieldName == TIMELINE_FIELD_MAINTENANCE_SNAPSHOT_PAYLOAD
                }?.newValue

                if (!maintenanceSnapshotPayload.isNullOrBlank()) {
                    if (isCurrentlyReverted) return@launch onResult(false)
                    val restored = restoreMaintenanceSnapshot(payloadJson = maintenanceSnapshotPayload)
                    if (!restored.success) return@launch onResult(false)
                    _maintenanceRestoreMessage.value = if (restored.usedLegacyMode) {
                        app.getString(
                            R.string.timeline_restore_snapshot_result_legacy,
                            restored.deletedPasswords,
                            restored.deletedSecureItems
                        )
                    } else {
                        app.getString(
                            R.string.timeline_restore_snapshot_result,
                            restored.deletedPasswords,
                            restored.deletedSecureItems,
                            restored.upsertedPasswords,
                            restored.upsertedSecureItems
                        )
                    }
                    database.operationLogDao().updateRevertedStatus(logId, true)
                    return@launch onResult(true)
                }

                if (!batchMovePayload.isNullOrBlank()) {
                    if (isCurrentlyReverted) return@launch onResult(false)
                    val restored = restoreBatchMove(payloadJson = batchMovePayload)
                    if (!restored) return@launch onResult(false)
                    database.operationLogDao().updateRevertedStatus(logId, !isCurrentlyReverted)
                    return@launch onResult(true)
                }

                if (!batchCopyPayload.isNullOrBlank()) {
                    if (isCurrentlyReverted) return@launch onResult(false)
                    val restored = restoreBatchCopy(payloadJson = batchCopyPayload)
                    if (!restored) return@launch onResult(false)
                    database.operationLogDao().updateRevertedStatus(logId, !isCurrentlyReverted)
                    return@launch onResult(true)
                }
                
                when (log.itemType) {
                    "PASSWORD" -> {
                        val entry = passwordRepository.getPasswordEntryById(itemId) ?: return@launch onResult(false)
                        
                        // 应用变更（恢复或重做）
                        var updatedEntry = entry
                        rawChanges.forEach { change ->
                            // 确定要使用的目标值
                            val targetValue = if (isCurrentlyReverted) {
                                // 当前是已恢复状态，要重做（恢复到编辑后）-> 使用 newValue
                                change.newValue
                            } else {
                                // 当前是编辑后状态，要恢复（恢复到编辑前）-> 使用 oldValue
                                change.oldValue
                            }
                            
                            updatedEntry = when (change.fieldName) {
                                "用户名" -> updatedEntry.copy(username = targetValue)
                                "网站" -> updatedEntry.copy(website = targetValue)
                                "密码" -> if (targetValue.isNotBlank()) {
                                    updatedEntry.copy(password = securityManager.encryptData(targetValue))
                                } else updatedEntry
                                "备注" -> updatedEntry.copy(notes = targetValue)
                                "标题" -> updatedEntry.copy(title = targetValue)
                                else -> updatedEntry
                            }
                        }
                        
                        passwordRepository.updatePasswordEntry(updatedEntry)
                    }
                    "TOTP", "BANK_CARD", "NOTE", "DOCUMENT" -> {
                        val item = secureItemRepository.getItemById(itemId) ?: return@launch onResult(false)
                        
                        var updatedItem = item
                        rawChanges.forEach { change ->
                            val targetVal = if (isCurrentlyReverted) change.newValue else change.oldValue
                            
                            updatedItem = when (change.fieldName) {
                                "标题" -> updatedItem.copy(title = targetVal)
                                "备注" -> updatedItem.copy(notes = targetVal)
                                "内容" -> updatedItem.copy(notes = targetVal) // 笔记内容
                                else -> updatedItem
                            }
                        }
                        
                        secureItemRepository.updateItem(updatedItem)
                    }
                    else -> return@launch onResult(false)
                }
                
                // 切换恢复状态
                database.operationLogDao().updateRevertedStatus(logId, !isCurrentlyReverted)
                onResult(true)
                
            } catch (e: Exception) {
                android.util.Log.e("TimelineViewModel", "Failed to revert edit", e)
                onResult(false)
            }
        }
    }

    private suspend fun revertEncryptedSnapshot(
        log: TimelineEvent.StandardLog,
        rawLog: OperationLog,
        changes: List<DiffChange>
    ): Boolean {
        if (!areTimelineSnapshotFieldsReversible(log.itemType, changes.map(DiffChange::fieldName))) {
            return false
        }
        val isCurrentlyReverted = rawLog.isReverted
        val useNewValues = isCurrentlyReverted
        var appliedChanges = 0

        fun targetValue(change: DiffChange): String =
            if (useNewValues) change.newValue else change.oldValue

        fun expectedValue(change: DiffChange): String =
            if (useNewValues) change.oldValue else change.newValue

        when (log.itemType) {
            "PASSWORD" -> {
                val entry = passwordRepository.getPasswordEntryById(log.itemId) ?: return false
                if (entry.keepassDatabaseId != null) return false
                var updated = entry
                changes.forEach { change ->
                    val current = when (change.fieldName) {
                        "用户名" -> entry.username
                        "网站" -> entry.website
                        "密码" -> securityManager.decryptDataIfMonicaCiphertext(entry.password)
                        "备注" -> entry.notes
                        "标题" -> entry.title
                        else -> return@forEach
                    }
                    if (current != expectedValue(change)) return false
                    updated = when (change.fieldName) {
                        "用户名" -> updated.copy(username = targetValue(change))
                        "网站" -> updated.copy(website = targetValue(change))
                        "密码" -> updated.copy(password = securityManager.encryptData(targetValue(change)))
                        "备注" -> updated.copy(notes = targetValue(change))
                        "标题" -> updated.copy(title = targetValue(change))
                        else -> updated
                    }
                    appliedChanges++
                }
                if (appliedChanges == 0) return false
                passwordRepository.updatePasswordEntry(updated.copy(updatedAt = Date()))
            }
            "TOTP", "BANK_CARD", "NOTE", "DOCUMENT", "BILLING_ADDRESS" -> {
                val item = secureItemRepository.getItemById(log.itemId) ?: return false
                if (item.keepassDatabaseId != null) return false
                var updated = item
                changes.forEach { change ->
                    val current = when (change.fieldName) {
                        "标题" -> item.title
                        "备注" -> item.notes
                        takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_ITEM_DATA -> item.itemData
                        takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_NOTES -> item.notes
                        else -> return@forEach
                    }
                    if (current != expectedValue(change)) return false
                    updated = when (change.fieldName) {
                        "标题" -> updated.copy(title = targetValue(change))
                        "备注" -> updated.copy(notes = targetValue(change))
                        takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_ITEM_DATA ->
                            updated.copy(itemData = targetValue(change))
                        takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_NOTES ->
                            updated.copy(notes = targetValue(change))
                        else -> updated
                    }
                    appliedChanges++
                }
                if (appliedChanges == 0) return false
                secureItemRepository.updateItem(updated.copy(updatedAt = Date()))
            }
            else -> return false
        }

        database.operationLogDao().updateRevertedStatus(
            log.id.toLongOrNull() ?: return false,
            !isCurrentlyReverted
        )
        return true
    }

    private suspend fun matchesCurrentEncryptedSnapshotState(
        log: TimelineEvent.StandardLog,
        changes: List<DiffChange>
    ): Boolean {
        if (!areTimelineSnapshotFieldsReversible(log.itemType, changes.map(DiffChange::fieldName))) {
            return false
        }
        return when (log.itemType) {
            "PASSWORD" -> {
                val entry = passwordRepository.getPasswordEntryById(log.itemId) ?: return false
                if (entry.keepassDatabaseId != null) return false
                changes.all { change ->
                    val current = when (change.fieldName) {
                        "用户名" -> entry.username
                        "网站" -> entry.website
                        "密码" -> securityManager.decryptDataIfMonicaCiphertext(entry.password)
                        "备注" -> entry.notes
                        "标题" -> entry.title
                        else -> return@all false
                    }
                    current == change.newValue
                }
            }
            "TOTP", "BANK_CARD", "NOTE", "DOCUMENT", "BILLING_ADDRESS" -> {
                val item = secureItemRepository.getItemById(log.itemId) ?: return false
                if (item.keepassDatabaseId != null) return false
                changes.all { change ->
                    when (change.fieldName) {
                        "标题" -> item.title == change.newValue
                        "备注" -> item.notes == change.newValue
                        takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_ITEM_DATA ->
                            item.itemData == change.newValue
                        takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_NOTES ->
                            item.notes == change.newValue
                        else -> true // Granular itemData display fields are covered by the full itemData state.
                    }
                }
            }
            else -> false
        }
    }

    private suspend fun restoreBatchMove(payloadJson: String): Boolean {
        val payload = try {
            json.decodeFromString<TimelineBatchMovePayload>(payloadJson)
        } catch (_: Exception) {
            return false
        }

        val targetStates = payload.oldStates
        if (targetStates.isEmpty()) return false

        val recreatedEntryMap = payload.recreatedEntries.associate { it.sourceEntryId to it.recreatedEntryId }
        var changed = false
        targetStates.forEach { state ->
            val effectiveEntryId = recreatedEntryMap[state.id] ?: state.id
            val entry = passwordRepository.getPasswordEntryById(effectiveEntryId)
                ?: passwordRepository.getPasswordEntryById(state.id)
                ?: return@forEach
            if (!restoreBatchMoveRemoteState(entry, state)) {
                return@forEach
            }
            val updatedEntry = entry.copy(
                categoryId = state.categoryId,
                keepassDatabaseId = state.keepassDatabaseId,
                keepassGroupPath = state.keepassGroupPath,
                bitwardenVaultId = state.bitwardenVaultId,
                bitwardenCipherId = state.bitwardenCipherId,
                bitwardenFolderId = state.bitwardenFolderId,
                bitwardenRevisionDate = state.bitwardenRevisionDate,
                bitwardenLocalModified = state.bitwardenLocalModified,
                isArchived = state.isArchived,
                archivedAt = state.archivedAtMillis?.let { Date(it) },
                updatedAt = Date()
            )
            database.passwordEntryDao().update(updatedEntry)
            changed = true
        }
        return changed
    }

    private suspend fun restoreBatchMoveRemoteState(
        entry: takagi.ru.monica.data.PasswordEntry,
        targetState: takagi.ru.monica.data.model.TimelinePasswordLocationState
    ): Boolean {
        val targetVaultId = targetState.bitwardenVaultId
        val targetCipherId = targetState.bitwardenCipherId
        if (targetVaultId != null && !targetCipherId.isNullOrBlank()) {
            if (entry.bitwardenVaultId == targetVaultId && entry.bitwardenCipherId == targetCipherId) {
                return true
            }
            return bitwardenRepository.queueCipherRestore(
                vaultId = targetVaultId,
                cipherId = targetCipherId,
                entryId = entry.id
            ).isSuccess
        }

        val currentVaultId = entry.bitwardenVaultId
        val currentCipherId = entry.bitwardenCipherId
        if (currentVaultId != null && !currentCipherId.isNullOrBlank()) {
            return bitwardenRepository.queueCipherDelete(
                vaultId = currentVaultId,
                cipherId = currentCipherId,
                entryId = entry.id
            ).isSuccess
        }
        return true
    }

    private suspend fun restoreBatchCopy(payloadJson: String): Boolean {
        val payload = try {
            json.decodeFromString<TimelineBatchCopyPayload>(payloadJson)
        } catch (_: Exception) {
            return false
        }
        if (payload.copiedEntryIds.isEmpty()) return false

        var changed = false
        payload.copiedEntryIds.forEach { id ->
            val entry = passwordRepository.getPasswordEntryById(id) ?: return@forEach
            val updatedEntry = entry.copy(
                isDeleted = true,
                deletedAt = Date(),
                updatedAt = Date()
            )
            passwordRepository.updatePasswordEntry(updatedEntry)
            changed = true
        }
        return changed
    }

    fun consumeMaintenanceRestoreMessage() {
        _maintenanceRestoreMessage.value = null
    }

    private suspend fun restoreMaintenanceSnapshot(payloadJson: String): MaintenanceSnapshotManager.RestoreStats {
        val payload = try {
            json.decodeFromString<TimelineMaintenanceSnapshotPayload>(payloadJson)
        } catch (_: Exception) {
            return MaintenanceSnapshotManager.RestoreStats(success = false)
        }

        return maintenanceSnapshotManager.restorePayload(payload)
    }
    
    /**
     * 将旧数据保存为新条目
     * @param log 日志记录（从中提取旧数据）
     * @param onResult 回调函数，返回是否成功
     */
    fun saveOldDataAsNew(log: TimelineEvent.StandardLog, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val itemId = log.itemId
                val snapshotChanges = readEncryptedSnapshot(log.id)
                    ?: return@launch onResult(false)
                if (!areTimelineSnapshotFieldsReversible(
                        log.itemType,
                        snapshotChanges.map(DiffChange::fieldName)
                    )
                ) {
                    return@launch onResult(false)
                }
                if (!matchesCurrentEncryptedSnapshotState(log, snapshotChanges)) {
                    return@launch onResult(false)
                }
                
                when (log.itemType) {
                    "PASSWORD" -> {
                        val entry = passwordRepository.getPasswordEntryById(itemId) ?: return@launch onResult(false)
                        if (entry.keepassDatabaseId != null) return@launch onResult(false)
                        
                        // 创建新条目，使用旧值
                        var newEntry = entry.copy(
                            id = 0,  // 新ID
                            title = "${entry.title} (旧版本)",
                            keepassDatabaseId = null,
                            keepassGroupPath = null,
                            keepassEntryUuid = null,
                            keepassGroupUuid = null,
                            mdbxDatabaseId = null,
                            mdbxFolderId = null,
                            replicaGroupId = null,
                            bitwardenVaultId = null,
                            bitwardenCipherId = null,
                            bitwardenFolderId = null,
                            bitwardenRevisionDate = null,
                            bitwardenLocalModified = false,
                            createdAt = java.util.Date(),
                            updatedAt = java.util.Date()
                        )
                        
                        // 应用旧值
                        snapshotChanges.forEach { change ->
                            newEntry = when (change.fieldName) {
                                "用户名" -> newEntry.copy(username = change.oldValue)
                                "网站" -> newEntry.copy(website = change.oldValue)
                                "密码" -> newEntry.copy(
                                    password = if (change.oldValue.isBlank()) {
                                        ""
                                    } else {
                                        securityManager.encryptData(change.oldValue)
                                    }
                                )
                                "备注" -> newEntry.copy(notes = change.oldValue)
                                "标题" -> newEntry.copy(title = "${change.oldValue} (旧版本)")
                                else -> newEntry
                            }
                        }
                        
                        passwordRepository.insertPasswordEntry(newEntry)
                    }
                    "NOTE" -> {
                        val item = secureItemRepository.getItemById(itemId) ?: return@launch onResult(false)
                        if (item.keepassDatabaseId != null) return@launch onResult(false)
                        
                        var newItem = item.copy(
                            id = 0,
                            title = "${item.title} (旧版本)",
                            keepassDatabaseId = null,
                            keepassGroupPath = null,
                            keepassEntryUuid = null,
                            keepassGroupUuid = null,
                            mdbxDatabaseId = null,
                            mdbxFolderId = null,
                            replicaGroupId = null,
                            bitwardenVaultId = null,
                            bitwardenCipherId = null,
                            bitwardenFolderId = null,
                            bitwardenRevisionDate = null,
                            bitwardenLocalModified = false,
                            syncStatus = "NONE",
                            createdAt = java.util.Date(),
                            updatedAt = java.util.Date()
                        )
                        
                        snapshotChanges.forEach { change ->
                            newItem = when (change.fieldName) {
                                "标题" -> newItem.copy(title = "${change.oldValue} (旧版本)")
                                takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_ITEM_DATA ->
                                    newItem.copy(itemData = change.oldValue)
                                takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_NOTES ->
                                    newItem.copy(notes = change.oldValue)
                                else -> newItem
                            }
                        }
                        
                        secureItemRepository.insertItem(newItem)
                    }
                    "TOTP", "BANK_CARD", "DOCUMENT", "BILLING_ADDRESS" -> {
                        val item = secureItemRepository.getItemById(itemId) ?: return@launch onResult(false)
                        if (item.keepassDatabaseId != null) return@launch onResult(false)
                        
                        var newItem = item.copy(
                            id = 0,
                            title = "${item.title} (旧版本)",
                            keepassDatabaseId = null,
                            keepassGroupPath = null,
                            keepassEntryUuid = null,
                            keepassGroupUuid = null,
                            mdbxDatabaseId = null,
                            mdbxFolderId = null,
                            replicaGroupId = null,
                            bitwardenVaultId = null,
                            bitwardenCipherId = null,
                            bitwardenFolderId = null,
                            bitwardenRevisionDate = null,
                            bitwardenLocalModified = false,
                            syncStatus = "NONE",
                            createdAt = java.util.Date(),
                            updatedAt = java.util.Date()
                        )
                        
                        snapshotChanges.forEach { change ->
                            newItem = when (change.fieldName) {
                                "标题" -> newItem.copy(title = "${change.oldValue} (旧版本)")
                                "备注" -> newItem.copy(notes = change.oldValue)
                                takagi.ru.monica.data.TIMELINE_SNAPSHOT_FIELD_ITEM_DATA ->
                                    newItem.copy(itemData = change.oldValue)
                                else -> newItem
                            }
                        }
                        
                        secureItemRepository.insertItem(newItem)
                    }
                    else -> return@launch onResult(false)
                }
                
                onResult(true)
                
            } catch (e: Exception) {
                android.util.Log.e("TimelineViewModel", "Failed to save old data as new", e)
                onResult(false)
            }
        }
    }
}
