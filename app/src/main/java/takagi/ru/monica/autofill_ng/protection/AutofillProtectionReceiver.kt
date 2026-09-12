package takagi.ru.monica.autofill_ng.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutofillProtectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AutofillProtection.restoreIfEnabled(context)
        }
    }
}
