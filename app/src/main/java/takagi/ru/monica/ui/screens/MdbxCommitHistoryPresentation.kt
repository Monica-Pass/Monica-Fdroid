package takagi.ru.monica.ui.screens

import java.util.Locale
import takagi.ru.monica.repository.MdbxCommitChangeSummary
import takagi.ru.monica.repository.MdbxDeltaSummary

internal enum class MdbxHistoryAction {
    CREATED,
    UPDATED,
    MOVED,
    COPIED,
    DELETED,
    RESTORED,
    MERGED,
    SYSTEM
}

internal data class MdbxCommitActionCounts(
    val created: Int = 0,
    val updated: Int = 0,
    val moved: Int = 0,
    val copied: Int = 0,
    val deleted: Int = 0,
    val restored: Int = 0
) {
    val total: Int
        get() = created + updated + moved + copied + deleted + restored

    fun summary(): String = buildList {
        if (created > 0) add("新增 $created")
        if (updated > 0) add("修改 $updated")
        if (moved > 0) add("移动 $moved")
        if (copied > 0) add("复制 $copied")
        if (deleted > 0) add("删除 $deleted")
        if (restored > 0) add("恢复 $restored")
    }.joinToString(" · ")
}

internal data class MdbxCommitPresentation(
    val title: String,
    val supportingText: String,
    val primaryAction: MdbxHistoryAction,
    val actionCounts: MdbxCommitActionCounts,
    val objectCount: Int,
    val isSystemCommit: Boolean,
    val systemDescription: String?,
    val canRevert: Boolean
)

internal fun MdbxDeltaSummary.toHistoryPresentation(): MdbxCommitPresentation {
    val distinctChanges = changes.distinctBy { it.objectType to it.objectId }
    val actionCounts = distinctChanges.toActionCounts()
    val objectCount = distinctChanges.size.takeIf { it > 0 } ?: changedObjectCountFallback()
    val systemCommit = isSystemHistoryCommit()
    val primaryAction = when {
        systemCommit -> MdbxHistoryAction.SYSTEM
        commitKind.equals("merge", ignoreCase = true) || parentCount > 1 -> MdbxHistoryAction.MERGED
        actionCounts.deleted > 0 && actionCounts.deleted == actionCounts.total -> MdbxHistoryAction.DELETED
        actionCounts.restored > 0 && actionCounts.restored == actionCounts.total -> MdbxHistoryAction.RESTORED
        actionCounts.moved > 0 && actionCounts.moved == actionCounts.total -> MdbxHistoryAction.MOVED
        actionCounts.copied > 0 && actionCounts.copied == actionCounts.total -> MdbxHistoryAction.COPIED
        actionCounts.created > 0 && actionCounts.created == actionCounts.total -> MdbxHistoryAction.CREATED
        else -> MdbxHistoryAction.UPDATED
    }
    val title = operationTitle(primaryAction, objectCount, distinctChanges)
    val systemDescription = if (systemCommit) systemCommitDescription() else null
    val supportingText = when {
        systemDescription != null -> systemDescription
        actionCounts.summary().isNotBlank() -> actionCounts.summary()
        message?.isNotBlank() == true -> message.orEmpty()
        changedFieldSummary.isNotBlank() -> changedFieldSummary
        else -> "数据库内容已更新"
    }
    val canRevert = !systemCommit &&
        distinctChanges.isNotEmpty() &&
        distinctChanges.size <= MAX_ANDROID_REVERTABLE_OBJECTS &&
        distinctChanges.all { it.objectType.equals("entry", ignoreCase = true) }

    return MdbxCommitPresentation(
        title = title,
        supportingText = supportingText,
        primaryAction = primaryAction,
        actionCounts = actionCounts,
        objectCount = objectCount,
        isSystemCommit = systemCommit,
        systemDescription = systemDescription,
        canRevert = canRevert
    )
}

internal fun MdbxCommitChangeSummary.historyAction(): MdbxHistoryAction =
    when (action.trim().lowercase(Locale.ROOT)) {
        "create", "created", "add", "added" -> MdbxHistoryAction.CREATED
        "move", "moved" -> MdbxHistoryAction.MOVED
        "copy", "copied" -> MdbxHistoryAction.COPIED
        "delete", "deleted", "remove", "removed" -> MdbxHistoryAction.DELETED
        "restore", "restored", "revert", "reverted" -> MdbxHistoryAction.RESTORED
        else -> MdbxHistoryAction.UPDATED
    }

internal fun mdbxHistoryObjectTypeLabel(objectType: String, contentType: String? = null): String {
    val normalizedContentType = contentType?.trim()?.lowercase(Locale.ROOT)
    return when (normalizedContentType) {
        "login", "password" -> "密码"
        "note" -> "笔记"
        "totp" -> "验证器"
        "card" -> "卡片"
        "document-ref", "document" -> "证件"
        "billing-address" -> "地址"
        "payment-account" -> "支付账户"
        "passkey" -> "通行密钥"
        "steam-mafile" -> "Steam 账号"
        else -> when (objectType.trim().lowercase(Locale.ROOT)) {
            "entry" -> "条目"
            "project", "folder" -> "文件夹"
            "attachment" -> "附件"
            "passkey" -> "通行密钥"
            "object-relation" -> "关联"
            "object-label", "object-label-assignment" -> "标签"
            "vault-meta" -> "数据库设置"
            "key-epoch" -> "数据库密钥"
            "snapshot" -> "快照"
            "branch" -> "同步分支"
            else -> "对象"
        }
    }
}

private fun List<MdbxCommitChangeSummary>.toActionCounts(): MdbxCommitActionCounts {
    var created = 0
    var updated = 0
    var moved = 0
    var copied = 0
    var deleted = 0
    var restored = 0
    forEach { change ->
        when (change.historyAction()) {
            MdbxHistoryAction.CREATED -> created++
            MdbxHistoryAction.UPDATED -> updated++
            MdbxHistoryAction.MOVED -> moved++
            MdbxHistoryAction.COPIED -> copied++
            MdbxHistoryAction.DELETED -> deleted++
            MdbxHistoryAction.RESTORED -> restored++
            MdbxHistoryAction.MERGED,
            MdbxHistoryAction.SYSTEM -> updated++
        }
    }
    return MdbxCommitActionCounts(created, updated, moved, copied, deleted, restored)
}

private fun MdbxDeltaSummary.operationTitle(
    primaryAction: MdbxHistoryAction,
    objectCount: Int,
    changes: List<MdbxCommitChangeSummary>
): String {
    val operation = operationKind?.trim()?.lowercase(Locale.ROOT)
    return when (operation) {
        "monica-initialize" -> "初始化数据库"
        "monica-create-folder" -> "新建文件夹"
        "monica-rename-folder" -> "重命名文件夹"
        "monica-move-folder" -> "移动文件夹"
        "monica-delete-folder" -> "删除文件夹"
        "monica-restore-folder" -> "恢复文件夹"
        "monica-migration-folders" -> "导入文件夹"
        "monica-project-tags" -> "更新文件夹标签"
        "monica-delete-entries" -> actionTitle(MdbxHistoryAction.DELETED, objectCount, changes)
        "revert-commit" -> "恢复历史版本"
        else -> when {
            operation?.contains("attachment-create") == true -> actionTitle(
                MdbxHistoryAction.CREATED,
                objectCount,
                changes,
                forcedType = "附件"
            )
            operation?.contains("attachment-replace") == true -> "更新附件内容"
            operation?.contains("snapshot") == true -> "更新数据库快照"
            operation?.contains("key") == true && operation.contains("rotat") -> "轮换数据库密钥"
            else -> actionTitle(primaryAction, objectCount, changes)
        }
    }
}

private fun actionTitle(
    action: MdbxHistoryAction,
    objectCount: Int,
    changes: List<MdbxCommitChangeSummary>,
    forcedType: String? = null
): String {
    val objectLabel = forcedType ?: changes.singleObjectTypeLabel()
    val quantity = if (objectCount > 0) "$objectCount 个$objectLabel" else objectLabel
    return when (action) {
        MdbxHistoryAction.CREATED -> "添加了$quantity"
        MdbxHistoryAction.UPDATED -> "更新了$quantity"
        MdbxHistoryAction.MOVED -> "移动了$quantity"
        MdbxHistoryAction.COPIED -> "复制了$quantity"
        MdbxHistoryAction.DELETED -> "删除了$quantity"
        MdbxHistoryAction.RESTORED -> "恢复了$quantity"
        MdbxHistoryAction.MERGED -> "合并了数据库变更"
        MdbxHistoryAction.SYSTEM -> "数据库系统事件"
    }
}

private fun List<MdbxCommitChangeSummary>.singleObjectTypeLabel(): String {
    val labels = map { mdbxHistoryObjectTypeLabel(it.objectType) }.distinct()
    return labels.singleOrNull() ?: "项目"
}

private fun MdbxDeltaSummary.isSystemHistoryCommit(): Boolean {
    val operation = operationKind?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val scope = changeScope.trim().lowercase(Locale.ROOT)
    val kind = commitKind.trim().lowercase(Locale.ROOT)
    return operation == "monica-initialize" ||
        operation.startsWith("snapshot-") ||
        operation.startsWith("branch-") ||
        operation.contains("key-rotation") ||
        operation.contains("security-policy") ||
        scope in SYSTEM_CHANGE_SCOPES ||
        kind in SYSTEM_COMMIT_KINDS
}

private fun MdbxDeltaSummary.systemCommitDescription(): String = when {
    operationKind.equals("monica-initialize", ignoreCase = true) ->
        "建立数据库根目录和初始结构"
    commitKind.equals("key-rotation", ignoreCase = true) ||
        changeScope.equals("key-epoch", ignoreCase = true) ->
        "更新数据库加密密钥或解锁材料"
    commitKind.equals("snapshot", ignoreCase = true) ||
        changeScope.equals("snapshot", ignoreCase = true) ->
        "记录或整理数据库快照"
    changeScope.equals("branch", ignoreCase = true) ->
        "更新数据库同步分支状态"
    changeScope.equals("vault-meta", ignoreCase = true) ->
        "更新数据库设置或安全元数据"
    else -> message?.takeIf { it.isNotBlank() }
        ?: "此提交记录的是数据库级事件，不包含普通条目变更"
}

private fun MdbxDeltaSummary.changedObjectCountFallback(): Int {
    val normalized = changedObjectIds.trim()
    if (normalized.isBlank() || normalized == "[]") return 0
    return normalized.trim('[', ']')
        .split(',')
        .count { it.trim().isNotBlank() }
}

private val SYSTEM_CHANGE_SCOPES = setOf("vault-meta", "key-epoch", "snapshot", "branch")
private val SYSTEM_COMMIT_KINDS = setOf("snapshot", "key-rotation")
private const val MAX_ANDROID_REVERTABLE_OBJECTS = 500
