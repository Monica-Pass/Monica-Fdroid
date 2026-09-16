package takagi.ru.monica.utils

import takagi.ru.monica.R
import takagi.ru.monica.data.BackupReport

internal fun backupExportMessage(report: BackupReport, strings: StringResolver): String {
    val summary = strings.get(
        R.string.export_message_backup_summary,
        report.successItems.passwords,
        report.successItems.images
    )
    val credentialsSkipped = strings.get(R.string.backup_credentials_skipped)
    val hasWarning = report.connectionCredentialsSkipped || report.warnings.any {
        // Keep compatibility with a report produced before these messages were localized.
        it == credentialsSkipped || it.contains("WebDAV 连接凭证")
    }
    return if (hasWarning) "$summary\n$credentialsSkipped" else summary
}
