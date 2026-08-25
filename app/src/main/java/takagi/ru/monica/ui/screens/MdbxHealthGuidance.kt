package takagi.ru.monica.ui.screens

import takagi.ru.monica.repository.MdbxHealthIssueDiagnostic
import takagi.ru.monica.repository.MdbxHealthSeverity
import takagi.ru.monica.repository.MdbxVaultDiagnostics

internal enum class MdbxHealthGuidanceAction {
    RECHECK,
    MAINTENANCE,
    SNAPSHOTS,
    COMMIT_HISTORY,
    ATTACHMENTS
}

internal data class MdbxHealthGuidance(
    val id: String,
    val category: String,
    val severity: MdbxHealthSeverity,
    val title: String,
    val summary: String,
    val impact: String,
    val steps: List<String>,
    val action: MdbxHealthGuidanceAction,
    val technicalDetails: List<String>
)

private enum class MdbxHealthGuidanceKind {
    FILE_UNREADABLE,
    BASIC_INTEGRITY,
    HEADER_VERIFICATION_PENDING,
    HEADER_AUTHENTICATION_FAILED,
    INTEGRITY_ROOT_PENDING,
    INTEGRITY_ROOT_STALE,
    COMMIT_REFERENCE_MISSING,
    COMMIT_AUTHENTICATION_PENDING,
    COMMIT_AUTHENTICATION_FAILED,
    ATTACHMENT_STRUCTURE,
    SNAPSHOT_INVALID,
    ORPHAN_RECORD,
    COLLECTION_PROFILE,
    TOMBSTONE_DUPLICATE,
    TOMBSTONE_MISSING,
    TOMBSTONE_STALE,
    TOMBSTONE_ACKNOWLEDGEMENT,
    PURGE_RECORD,
    DEVICE_REFERENCE,
    INACTIVE_DEVICE,
    UNKNOWN
}

internal fun MdbxVaultDiagnostics.healthGuidance(): List<MdbxHealthGuidance> {
    val reportedIssues = buildList {
        if (!isReadable) {
            add(
                MdbxHealthIssueDiagnostic(
                    severity = MdbxHealthSeverity.CRITICAL,
                    category = "file-access",
                    description = unavailableReason ?: "database file is not readable"
                )
            )
        }
        addAll(
            when {
                healthIssues.isNotEmpty() -> healthIssues
                !integrityOk -> legacyHealthIssues()
                else -> emptyList()
            }
        )
    }

    return reportedIssues
        .groupBy { it.guidanceKind() }
        .map { (kind, issues) -> guidanceFor(kind, issues) }
        .sortedWith(
            compareByDescending<MdbxHealthGuidance> { it.severity.ordinal }
                .thenBy(MdbxHealthGuidance::title)
        )
}

private fun MdbxVaultDiagnostics.legacyHealthIssues(): List<MdbxHealthIssueDiagnostic> {
    val message = integrityMessage?.takeIf {
        it.isNotBlank() && !it.contains("health check passed", ignoreCase = true)
    } ?: return listOf(
        MdbxHealthIssueDiagnostic(
            severity = MdbxHealthSeverity.ERROR,
            category = "integrity",
            description = "database integrity check failed"
        )
    )

    return message.split("; ")
        .filter(String::isNotBlank)
        .map { raw ->
            val category = raw.substringBefore(':', missingDelimiterValue = "integrity").trim()
            val description = raw.substringAfter(':', missingDelimiterValue = raw).trim()
            MdbxHealthIssueDiagnostic(
                severity = MdbxHealthSeverity.ERROR,
                category = category,
                description = description
            )
        }
}

private fun MdbxHealthIssueDiagnostic.guidanceKind(): MdbxHealthGuidanceKind {
    val normalizedCategory = category.lowercase()
    val normalizedDescription = description.lowercase()
    return when (normalizedCategory) {
        "file-access" -> MdbxHealthGuidanceKind.FILE_UNREADABLE
        "integrity" -> MdbxHealthGuidanceKind.BASIC_INTEGRITY
        "vault-header-integrity" -> {
            if (
                normalizedDescription.contains("pending") ||
                normalizedDescription.contains("requires an unlocked keyring")
            ) {
                MdbxHealthGuidanceKind.HEADER_VERIFICATION_PENDING
            } else {
                MdbxHealthGuidanceKind.HEADER_AUTHENTICATION_FAILED
            }
        }
        "incremental-integrity-root" -> {
            if (
                normalizedDescription.contains("pending") ||
                normalizedDescription.contains("incomplete") ||
                normalizedDescription.contains("requires an unlocked keyring")
            ) {
                MdbxHealthGuidanceKind.INTEGRITY_ROOT_PENDING
            } else {
                MdbxHealthGuidanceKind.INTEGRITY_ROOT_STALE
            }
        }
        "commit-chain" -> MdbxHealthGuidanceKind.COMMIT_REFERENCE_MISSING
        "commit-integrity" -> {
            if (normalizedDescription.contains("cannot be verified without an unlocked keyring")) {
                MdbxHealthGuidanceKind.COMMIT_AUTHENTICATION_PENDING
            } else {
                MdbxHealthGuidanceKind.COMMIT_AUTHENTICATION_FAILED
            }
        }
        "attachment-chunks" -> MdbxHealthGuidanceKind.ATTACHMENT_STRUCTURE
        "snapshots" -> MdbxHealthGuidanceKind.SNAPSHOT_INVALID
        "orphans" -> MdbxHealthGuidanceKind.ORPHAN_RECORD
        "collection-profiles" -> MdbxHealthGuidanceKind.COLLECTION_PROFILE
        "tombstones" -> when {
            normalizedDescription.contains("typed tombstones") -> MdbxHealthGuidanceKind.TOMBSTONE_DUPLICATE
            normalizedDescription.contains("deleted without") -> MdbxHealthGuidanceKind.TOMBSTONE_MISSING
            normalizedDescription.contains("active but retains") -> MdbxHealthGuidanceKind.TOMBSTONE_STALE
            else -> MdbxHealthGuidanceKind.TOMBSTONE_STALE
        }
        "tombstone-acknowledgements" -> MdbxHealthGuidanceKind.TOMBSTONE_ACKNOWLEDGEMENT
        "purge-receipts" -> MdbxHealthGuidanceKind.PURGE_RECORD
        "stale-heads" -> {
            if (normalizedDescription.contains("last seen at")) {
                MdbxHealthGuidanceKind.INACTIVE_DEVICE
            } else {
                MdbxHealthGuidanceKind.DEVICE_REFERENCE
            }
        }
        else -> MdbxHealthGuidanceKind.UNKNOWN
    }
}

private fun guidanceFor(
    kind: MdbxHealthGuidanceKind,
    issues: List<MdbxHealthIssueDiagnostic>
): MdbxHealthGuidance {
    val severity = issues.maxBy(MdbxHealthIssueDiagnostic::severity).severity
    val category = issues.first().category
    val countSuffix = if (issues.size > 1) "（${issues.size} 项）" else ""
    val technicalDetails = issues.map(MdbxHealthIssueDiagnostic::description).distinct()

    fun guidance(
        title: String,
        summary: String,
        impact: String,
        steps: List<String>,
        action: MdbxHealthGuidanceAction
    ) = MdbxHealthGuidance(
        id = kind.name.lowercase(),
        category = category,
        severity = severity,
        title = title + countSuffix,
        summary = summary,
        impact = impact,
        steps = steps,
        action = action,
        technicalDetails = technicalDetails
    )

    return when (kind) {
        MdbxHealthGuidanceKind.FILE_UNREADABLE -> guidance(
            title = "数据库文件暂时无法读取",
            summary = "Monica 当前无法打开数据库文件，常见原因包括文件被移动、访问权限变化或远程副本尚未完成下载。",
            impact = "数据库内容当前不可用，继续同步也可能失败。",
            steps = listOf(
                "确认文件仍位于原位置，并允许 Monica 访问对应目录",
                "远程数据库先完成下载或同步，再重新检查",
                "仍无法读取时保留当前文件，并从最近的正常备份重新打开"
            ),
            action = MdbxHealthGuidanceAction.RECHECK
        )
        MdbxHealthGuidanceKind.BASIC_INTEGRITY -> guidance(
            title = "数据库基础结构异常",
            summary = "数据库内部结构未通过基础校验，可能由写入中断、文件损坏或异常复制造成。",
            impact = "继续写入可能扩大受影响范围，部分内容也可能无法读取。",
            steps = listOf(
                "先创建文件副本或导出完整备份，保留当前状态",
                "关闭其他正在使用该数据库的设备或应用分身，再重新检查",
                "异常持续存在时从正常快照恢复，或将可读取内容迁移到新数据库"
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.HEADER_VERIFICATION_PENDING -> guidance(
            title = "等待完成安全校验",
            summary = "数据库已经打开，但需要在密钥可用的解锁状态下完成头部认证。",
            impact = "当前状态通常不代表数据损坏，完整检查尚未完成。",
            steps = listOf(
                "完成 Monica 解锁并保持数据库处于打开状态",
                "点击重新检查以完成认证",
                "多次解锁后仍失败时再进入诊断维护查看详细状态"
            ),
            action = MdbxHealthGuidanceAction.RECHECK
        )
        MdbxHealthGuidanceKind.HEADER_AUTHENTICATION_FAILED -> guidance(
            title = "数据库身份校验失败",
            summary = "数据库头部的认证信息与当前内容不一致。",
            impact = "文件可能来自不完整复制、错误密钥或受损副本，继续写入具有较高数据风险。",
            steps = listOf(
                "保留当前文件副本并暂停其他设备继续写入",
                "核对打开数据库时使用的凭据和文件来源",
                "优先从已验证的快照或备份恢复"
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.INTEGRITY_ROOT_PENDING -> guidance(
            title = "完整性索引尚未完成",
            summary = "用于快速验证数据库内容的完整性索引正在首次建立，或需要解锁后继续验证。",
            impact = "数据库通常仍可使用，但当前健康检查结果尚不完整。",
            steps = listOf(
                "保持数据库解锁并等待当前操作完成",
                "避免同时从多个分身打开同一文件",
                "随后重新检查完整性状态"
            ),
            action = MdbxHealthGuidanceAction.RECHECK
        )
        MdbxHealthGuidanceKind.INTEGRITY_ROOT_STALE -> guidance(
            title = "完整性索引需要重建",
            summary = "完整性索引与当前数据库内容已经不同步。",
            impact = "快速校验结果暂时不可信，可能伴随中断写入或多进程并发访问。",
            steps = listOf(
                "先创建全量快照或导出备份",
                "关闭其他应用分身或设备上的同一数据库，再重新打开并解锁",
                "重新检查后仍异常时从正常快照恢复"
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.COMMIT_REFERENCE_MISSING -> guidance(
            title = "历史记录引用不完整",
            summary = "某个提交或分支指向了当前文件中不存在的历史节点。",
            impact = "提交历史、差异比较或恢复到旧版本时可能出现缺口。",
            steps = listOf(
                "让所有使用该数据库的设备完成一次同步",
                "打开提交历史确认最近可用的提交",
                "重新检查后仍存在时从缺口之前的正常快照恢复"
            ),
            action = MdbxHealthGuidanceAction.COMMIT_HISTORY
        )
        MdbxHealthGuidanceKind.COMMIT_AUTHENTICATION_PENDING -> guidance(
            title = "历史记录等待解锁验证",
            summary = "提交记录需要在密钥可用时完成真实性校验。",
            impact = "当前仅缺少验证条件，暂未发现内容不一致。",
            steps = listOf(
                "完成 Monica 解锁",
                "保持数据库打开并重新检查",
                "验证完成前避免依据该提交执行恢复"
            ),
            action = MdbxHealthGuidanceAction.RECHECK
        )
        MdbxHealthGuidanceKind.COMMIT_AUTHENTICATION_FAILED -> guidance(
            title = "历史记录校验失败",
            summary = "某个提交的认证标记与记录内容不一致。",
            impact = "对应历史节点可能受损，基于该节点比较或恢复可能得到错误结果。",
            steps = listOf(
                "先创建当前数据库的完整备份",
                "在提交历史中定位异常前后的正常节点",
                "使用正常快照恢复，或将当前可用条目迁移到新数据库"
            ),
            action = MdbxHealthGuidanceAction.COMMIT_HISTORY
        )
        MdbxHealthGuidanceKind.ATTACHMENT_STRUCTURE -> guidance(
            title = "附件分片不完整",
            summary = "附件记录的分片数量或顺序与实际存储内容不一致。",
            impact = "受影响附件可能无法打开、导出或同步完整。",
            steps = listOf(
                "先导出仍可正常打开的重要附件",
                "完成一次远程同步后重新检查附件",
                "异常持续存在时从包含完整附件的备份恢复"
            ),
            action = MdbxHealthGuidanceAction.ATTACHMENTS
        )
        MdbxHealthGuidanceKind.SNAPSHOT_INVALID -> guidance(
            title = "快照校验失败",
            summary = "某个快照的摘要、认证信息或内容结构未通过校验。",
            impact = "该快照不适合作为恢复来源，当前数据库内容未必受到影响。",
            steps = listOf(
                "避免使用异常快照执行恢复",
                "创建新的全量快照并确认其校验状态",
                "需要恢复时选择另一份已通过校验的快照"
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.ORPHAN_RECORD -> guidance(
            title = "内容缺少所属文件夹",
            summary = "部分条目或附件引用了当前数据库中不存在的文件夹。",
            impact = "相关内容可能无法在正常列表中显示，移动和同步也可能失败。",
            steps = listOf(
                "先完成所有设备的同步并重新检查",
                "将仍可访问的内容复制到有效文件夹或新数据库",
                "内容无法访问时从最近的正常快照恢复"
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.COLLECTION_PROFILE -> guidance(
            title = "分类规则与内容不一致",
            summary = "某个分类缺少有效配置，或包含了该分类规则不允许的内容类型。",
            impact = "相关内容可能显示在错误页面，或无法按预期编辑和同步。",
            steps = listOf(
                "完成同步后重新检查分类状态",
                "将受影响内容移动到正确分类或普通文件夹",
                "分类本身无效时先备份内容，再重新建立分类"
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.TOMBSTONE_DUPLICATE -> guidance(
            title = "删除记录重复",
            summary = "同一对象保留了多个删除标记，数据库无法唯一确认当前删除状态。",
            impact = "相关内容在同步、恢复或永久删除时可能产生冲突。",
            steps = listOf(
                "先创建全量快照或导出完整备份",
                "让所有使用该数据库的设备完成一次同步，再重新检查",
                "异常持续存在时从正常快照恢复，或将可用内容迁移到新数据库"
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.TOMBSTONE_MISSING -> guidance(
            title = "删除记录缺失",
            summary = "内容已标记为删除，但缺少对应的删除历史记录。",
            impact = "其他设备可能无法正确识别该次删除，内容可能重新出现或产生冲突。",
            steps = listOf(
                "先创建全量快照或导出完整备份",
                "让所有设备完成同步并重新检查",
                "仍然异常时从删除操作之前的正常快照恢复"
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.TOMBSTONE_STALE -> guidance(
            title = "有效内容残留删除标记",
            summary = "当前仍在使用的内容同时保留了删除标记，状态存在冲突。",
            impact = "后续同步可能把有效内容再次视为已删除。",
            steps = listOf(
                "立即备份仍可访问的相关内容",
                "暂停其他设备继续编辑并完成一次同步",
                "异常持续存在时从正常快照恢复，或复制内容到新数据库"
            ),
            action = MdbxHealthGuidanceAction.SNAPSHOTS
        )
        MdbxHealthGuidanceKind.TOMBSTONE_ACKNOWLEDGEMENT -> guidance(
            title = "设备删除确认记录异常",
            summary = "某台设备对删除记录的确认无法通过历史关系校验。",
            impact = "跨设备同步可能重复处理删除操作，或长期保留待清理记录。",
            steps = listOf(
                "确保相关设备都完成同步并退出数据库",
                "在当前设备重新同步并检查冲突",
                "异常持续存在时保留日志并从正常备份恢复"
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.PURGE_RECORD -> guidance(
            title = "永久删除证明异常",
            summary = "永久删除记录与数据库中的对象或删除标记未保持一致。",
            impact = "永久删除状态可能无法在所有设备上得到一致确认。",
            steps = listOf(
                "完成所有设备的同步并重新检查",
                "保留当前数据库和日志副本",
                "持续异常时从永久删除之前的正常备份恢复"
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.DEVICE_REFERENCE -> guidance(
            title = "设备同步位置异常",
            summary = "某台设备记录的最新提交位置缺失、落后或属于其他设备。",
            impact = "跨设备同步可能遗漏更新，提交历史也可能显示错误位置。",
            steps = listOf(
                "让所有仍在使用的设备依次完成同步",
                "在提交历史中确认最新有效节点",
                "重新检查后仍异常时从共同的正常快照重新建立同步"
            ),
            action = MdbxHealthGuidanceAction.COMMIT_HISTORY
        )
        MdbxHealthGuidanceKind.INACTIVE_DEVICE -> guidance(
            title = "存在长期未活动设备",
            summary = "数据库保留了一台较长时间未参与同步的设备记录。",
            impact = "当前属于状态提示，通常不会影响数据库内容。",
            steps = listOf(
                "确认该设备是否仍在使用此数据库",
                "仍在使用时让该设备完成一次同步",
                "已经停用时保留记录也可以继续使用，后续维护时再清理"
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
        MdbxHealthGuidanceKind.UNKNOWN -> guidance(
            title = "发现未识别的数据库异常",
            summary = "当前版本尚未为该底层诊断提供专门说明。",
            impact = "影响范围取决于技术详情中的检查类别。",
            steps = listOf(
                "先创建全量快照或导出备份",
                "关闭其他设备或应用分身后重新检查",
                "异常持续存在时导出 Monica 日志，并保留技术详情用于排查"
            ),
            action = MdbxHealthGuidanceAction.MAINTENANCE
        )
    }
}
