package takagi.ru.monica.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import takagi.ru.monica.MainActivity
import takagi.ru.monica.R
import takagi.ru.monica.utils.AppLocaleStringResolver
import java.util.UUID

enum class ExportJobStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class ExportJobState(
    val id: String,
    val sourceKey: String,
    val formatKey: String = "ZIP_BACKUP",
    val progress: TransferProgress = TransferProgress(),
    val status: ExportJobStatus = ExportJobStatus.RUNNING,
    val message: String? = null,
)

/** Only this process owns the request. Passwords are never put in an Intent or a WorkManager database. */
object DatabaseExportJobs {
    internal class Request(val id: String, val uri: Uri,
        val run: suspend (TransferProgressReporter) -> Result<String>)
    private val mutableState = MutableStateFlow<ExportJobState?>(null)
    val state = mutableState.asStateFlow()
    private var pending: Request? = null

    @Synchronized
    fun start(context: Context, sourceKey: String, uri: Uri, formatKey: String = "ZIP_BACKUP",
        run: suspend (TransferProgressReporter) -> Result<String>): Boolean {
        if (mutableState.value?.status == ExportJobStatus.RUNNING) return false
        val id = UUID.randomUUID().toString()
        pending = Request(id, uri, run)
        mutableState.value = ExportJobState(id, sourceKey, formatKey)
        try {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context.applicationContext, DatabaseExportService::class.java).putExtra("operation", id))
        } catch (error: Exception) {
            pending = null
            mutableState.value = mutableState.value?.copy(status = ExportJobStatus.FAILED,
                message = AppLocaleStringResolver(context).get(R.string.transfer_background_start_failed))
            return false
        }
        return true
    }

    @Synchronized internal fun take(id: String?): Request? = pending?.takeIf { it.id == id }?.also { pending = null }
    @Synchronized internal fun update(id: String, progress: TransferProgress) {
        mutableState.value?.takeIf { it.id == id && it.status == ExportJobStatus.RUNNING }?.let {
            mutableState.value = it.copy(progress = progress)
        }
    }
    @Synchronized internal fun finish(id: String, status: ExportJobStatus, message: String?) {
        mutableState.value?.takeIf { it.id == id }?.let { mutableState.value = it.copy(status = status, message = message) }
    }
    @Synchronized fun dismiss() {
        if (mutableState.value?.status != ExportJobStatus.RUNNING) mutableState.value = null
    }
}

class DatabaseExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var lastNotification = 0L
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private val strings by lazy { AppLocaleStringResolver(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = DatabaseExportJobs.take(intent?.getStringExtra("operation"))
        if (request == null) {
            if (work?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        val startupError = runCatching {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL,
                strings.get(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION_ID, notification(TransferProgress(), running = true))
        }.exceptionOrNull()
        work = scope.launch {
            var status = ExportJobStatus.FAILED
            var message: String? = null
            try {
                if (startupError != null) {
                    throw IllegalStateException(strings.get(R.string.transfer_background_start_failed), startupError)
                }
                val result = request.run(TransferProgressReporter { progress ->
                    ensureActive()
                    DatabaseExportJobs.update(request.id, progress)
                    val now = System.nanoTime()
                    if (now - lastNotification >= 500_000_000L) {
                        lastNotification = now
                        // Notification permission/settings must not invalidate a completed archive.
                        runCatching { notifications.notify(NOTIFICATION_ID, notification(progress, running = true)) }
                    }
                })
                message = result.getOrThrow()
                ensureActive()
                status = ExportJobStatus.SUCCEEDED
            } catch (cancelled: CancellationException) {
                status = ExportJobStatus.CANCELLED
                message = strings.get(R.string.transfer_cancelled)
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: strings.get(R.string.export_data_error)
            } finally {
                if (status != ExportJobStatus.SUCCEEDED) removeIncompleteDocument(request.uri)
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    runCatching {
                        notifications.notify(NOTIFICATION_ID, notification(TransferProgress(), running = false,
                            succeeded = status == ExportJobStatus.SUCCEEDED))
                    }
                    // Publish completion only after cleaning up the old foreground notification.
                    // A newly started export can then safely reuse the same service/notification.
                    work = null
                    DatabaseExportJobs.finish(request.id, status, message)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun removeIncompleteDocument(uri: Uri) {
        if (android.provider.DocumentsContract.isDocumentUri(this, uri)) runCatching {
            android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
        }
    }

    private fun notification(progress: TransferProgress, running: Boolean, succeeded: Boolean = false): android.app.Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle(strings.get(if (running) R.string.exporting else if (succeeded)
                R.string.transfer_export_done else R.string.export_data_error))
            .setContentText(if (running) strings.get(progress.phase.labelRes()) else strings.get(R.string.transfer_open_result))
            .setContentIntent(intent).setOnlyAlertOnce(true).setOngoing(running).setAutoCancel(!running)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply { if (running) setProgress(100, ((progress.fraction ?: 0f) * 100).toInt(), progress.fraction == null) }
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        work?.cancel()
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "database_transfers"
        private const val NOTIFICATION_ID = 4318
    }
}
