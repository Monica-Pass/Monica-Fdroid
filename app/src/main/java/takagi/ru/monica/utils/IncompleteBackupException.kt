package takagi.ru.monica.utils

import android.content.Context
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupReport

/** Keep item details out of exception messages/logs, but available to the local UI. */
class IncompleteBackupException(val report: BackupReport, message: String) : Exception(message) {
    fun displayMessage(context: Context): String = buildString {
        append(message)
        report.failedItems.take(3).forEach { item ->
            append("\n\n")
            append(item.title.replace('\n', ' ').take(100))
            append(": ")
            append(item.reason)
        }
        if (report.failedItems.size > 3) {
            append("\n")
            append(context.getString(R.string.backup_report_more_items, report.failedItems.size - 3))
        }
    }
}

internal fun requireCompleteBackup(report: BackupReport, message: String) {
    if (!report.success || report.failedItems.isNotEmpty()) throw IncompleteBackupException(report, message)
}
