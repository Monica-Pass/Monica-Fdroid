package takagi.ru.monica.transfer

import android.content.Context
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.BackupReport
import takagi.ru.monica.data.FailedItem
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.sync.*
import takagi.ru.monica.utils.*

/** The request owns application-scoped dependencies and immutable options, never a screen. */
fun startWebDavBackup(context: Context, preferences: BackupPreferences, skippedPasskeys: List<FailedItem>,
    passwordRepository: PasswordRepository, secureItemRepository: SecureItemRepository): Boolean {
    val context = context.applicationContext
    return DatabaseExportJobs.startTask(context, "local", DataTaskKind.WEBDAV_BACKUP) { progress ->
        val webDavHelper = WebDavHelper(context)
            val backupTarget = SyncTarget.Backup(SyncBackupProvider.WEBDAV)
            val taskId = SyncDiagnostics.nextTaskId("backup-webdav-screen")
            val targetLog = backupTarget.stableKey.value
            val triggerLog = "WEBDAV_SCREEN_MANUAL"
                val syncResult = SyncTaskRunner.requestAndAwait(
                    cancelWhenWaiterCancelled = true,
                    request = SyncRequest(
                        requestId = taskId,
                        target = backupTarget,
                        trigger = SyncTrigger.MANUAL,
                        createdAtMillis = System.currentTimeMillis(),
                        priority = SyncPriority.MANUAL,
                        mode = SyncMode.FOREGROUND,
                        networkPolicy = SyncNetworkPolicy.REQUIRED
                    )
                ) {
                    SyncDiagnostics.queued(taskId, targetLog, triggerLog)
                    val startedAt = SyncDiagnostics.start(taskId, targetLog, triggerLog)
                    try {
                        // 获取 Monica 本地密码数据
                        val localPasswords = passwordRepository.getAllLocalPasswordEntries()

                        // WebDAV 是跨端备份；如果 Android 本机密钥不可用，不能把设备密文写进备份。
                        val securityManager = takagi.ru.monica.security.SecurityManager(context)
                        var failedPasswordDecryptCount = 0
                        val decryptedPasswords = localPasswords.map { entry ->
                            try {
                                entry.copy(password = securityManager.decryptData(entry.password))
                            } catch (e: Exception) {
                                android.util.Log.w("WebDavBackupScreen", "无法解密密码条目: ${e.message}")
                                failedPasswordDecryptCount++
                                entry.copy(password = "")
                            }
                        }
                        if (failedPasswordDecryptCount > 0) {
                            throw IllegalStateException(
                                context.getString(R.string.legacy_ui_backup_decrypt_failed, failedPasswordDecryptCount)
                            )
                        }

                        // 获取 Monica 本地其他数据(TOTP、银行卡、证件、笔记)
                        val localSecureItems = secureItemRepository.getAllLocalItems()

                        // 创建并上传永久备份
                        val report = webDavHelper.createAndUploadBackup(
                            passwords = decryptedPasswords,
                            secureItems = localSecureItems,
                            preferences = preferences,
                            isPermanent = true, // Manual backups are permanent
                            isManualTrigger = true,
                            contentScope = BackupContentScope.MONICA_LOCAL_ONLY,
                            skippedPasskeys = skippedPasskeys,
                            progress = progress
                        ).getOrThrow()

                        SyncDiagnostics.success(
                            taskId = taskId,
                            target = targetLog,
                            trigger = triggerLog,
                            startedAt = startedAt,
                            detail = "passwords=${decryptedPasswords.size} secureItems=${localSecureItems.size} hasIssues=${report.hasIssues()}"
                        )
                        report
                    } catch (error: Exception) {
                        SyncDiagnostics.failed(taskId, targetLog, triggerLog, startedAt, error)
                        throw error
                    }
                }

        val result: Result<BackupReport> = when (syncResult) {
            is SyncTaskAwaitResult.Completed -> Result.success(syncResult.value)
            is SyncTaskAwaitResult.Failed -> Result.failure(syncResult.error)
            is SyncTaskAwaitResult.Blocked -> Result.failure(IllegalStateException(syncResult.error.redactedMessage
                ?: context.getString(R.string.webdav_network_unreachable)))
            is SyncTaskAwaitResult.Canceled -> throw kotlinx.coroutines.CancellationException(syncResult.reason)
            else -> Result.failure(IllegalStateException(context.getString(R.string.webdav_backup_in_progress)))
        }
        (result.getOrNull() ?: (result.exceptionOrNull() as? IncompleteBackupException)?.report)
            ?.let(DatabaseExportJobs::reportBackup)
        result.map { report ->
            when {
                report.skippedItems.isNotEmpty() -> context.getString(R.string.passkey_partial_saved)
                report.hasIssues() -> report.getSummary(context)
                else -> context.getString(R.string.webdav_backup_success)
            }
        }
    }
}
