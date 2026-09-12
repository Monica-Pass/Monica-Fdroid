package takagi.ru.monica.autofill_ng.protection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import takagi.ru.monica.R

class AutofillProtectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AutofillProtection.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AutofillProtection.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.autofill_protection_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        try {
            val notification = notification(AutofillProtection.snapshot(this))
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: RuntimeException) {
            Log.w("AutofillProtection", "Could not start foreground protection", error)
            AutofillProtection.runtime.value = ProtectionRuntime.STOPPED
            stopSelf()
            return START_NOT_STICKY
        }
        AutofillProtection.runtime.value = ProtectionRuntime.RUNNING
        if (monitor == null) {
            monitor = scope.launch {
                AutofillProtection.observe(this@AutofillProtectionService).collectLatest { state ->
                    if (!state.backgroundEnabled) {
                        stopSelf()
                        return@collectLatest
                    }
                    manager.notify(NOTIFICATION_ID, notification(state))
                    if (state.shouldRecoverAutomatically(
                            System.currentTimeMillis(), AutofillProtection.lastRecoveryAttempt(this@AutofillProtectionService),
                        )) {
                        // Let Android finish binding first. No polling, wake locks or repeated restart timers.
                        delay(12_000)
                        ShizukuAccessibilityRecovery.recover(this@AutofillProtectionService)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        AutofillProtection.runtime.value = ProtectionRuntime.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(state: AutofillProtectionState): Notification {
        val openSettings = PendingIntent.getActivity(
            this, 0, Intent(this, AutofillProtectionActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutofillProtectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val message = when (state.accessibility) {
            AccessibilityConnection.CONNECTED -> R.string.autofill_protection_notification_connected
            AccessibilityConnection.DISCONNECTED -> R.string.autofill_protection_notification_disconnected
            AccessibilityConnection.DISABLED -> R.string.autofill_protection_notification_disabled
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle(getString(R.string.autofill_protection_notification_title))
            .setContentText(getString(message))
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.autofill_protection_stop), stop)
            .build()
    }

    companion object {
        internal const val CHANNEL_ID = "autofill_protection"
        internal const val NOTIFICATION_ID = 9530
        internal const val ACTION_STOP = "takagi.ru.monica.action.STOP_AUTOFILL_PROTECTION"
    }
}
