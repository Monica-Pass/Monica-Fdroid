package takagi.ru.monica.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.annotation.ChecksSdkIntAtLeast

/** Optional presentation only. Never starts work, grants permissions or changes fill results. */
object LiveUpdateNotifications {
    // Same public extras contract used by NotificationCompat.Builder in Core 1.17.0.
    // The initial API36 SDK exposes shortCriticalText but not a native request setter.
    internal const val REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    @ChecksSdkIntAtLeast(api = 36)
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 36

    fun canPostPromotedNotifications(context: Context): Boolean = isSupported() && runCatching {
        Api36.canPost(context)
    }.getOrDefault(false)

    fun isDeviceLocked(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
    }.getOrDefault(true)

    /** The original standard notification remains fully usable if an OEM declines promotion. */
    fun enhance(context: Context, notification: Notification, statusText: String): Notification {
        if (!canPostPromotedNotifications(context)) return notification
        return runCatching { Api36.enhance(context, notification, statusText) }.getOrDefault(notification)
    }

    fun settingsIntent(context: Context): Intent {
        if (isSupported()) {
            val promotion = Api36.settingsIntent(context.packageName)
            if (promotion.resolveActivity(context.packageManager) != null) return promotion
        }
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun appSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))

    @RequiresApi(36)
    private object Api36 {
        fun canPost(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
            ?.canPostPromotedNotifications() == true

        fun enhance(context: Context, notification: Notification, statusText: String): Notification =
            Notification.Builder.recoverBuilder(context, notification)
                .setOngoing(true)
                .setShortCriticalText(statusText.take(7))
                .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, true) })
                .build()

        fun settingsIntent(packageName: String): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
}
