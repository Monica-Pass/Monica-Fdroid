package takagi.ru.monica.autofill_ng

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import takagi.ru.monica.R

/**
 * AccessibilityService 检测到当前应用已绑定密码条目时显示主动填充通知。
 */
object ActiveFillNotificationHelper {

    private const val CHANNEL_ID = "active_fill_channel"
    private const val NOTIFICATION_ID = 9528

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.autofill_active_fill_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.autofill_active_fill_channel_desc)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun canPost(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    fun showActiveFillNotification(context: Context, packageName: String, appName: String): Boolean {
        createChannel(context)
        if (!canPost(context)) {
            android.util.Log.w("ActiveFill", "Notifications unavailable, skip active fill prompt")
            return false
        }

        val intent = Intent(context, AutofillPickerActivityV2::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE, true)
            putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_TARGET_PACKAGE, packageName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle(context.getString(R.string.autofill_active_fill_notification_content_title))
            .setContentText(
                context.getString(R.string.autofill_active_fill_notification_content_text, appName)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setTimeoutAfter(15_000)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            android.util.Log.w("ActiveFill", "Notification permission not granted", e)
            false
        }
    }

    fun dismissNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
