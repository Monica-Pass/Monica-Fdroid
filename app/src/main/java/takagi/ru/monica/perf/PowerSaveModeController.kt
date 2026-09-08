package takagi.ru.monica.perf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle-owned bridge from Android power-saver state to Compose. */
class PowerSaveModeController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _policy = MutableStateFlow(PowerSavePolicy.fromPowerSaveMode(powerManager.isPowerSaveMode))
    val policy: StateFlow<PowerSavePolicy> = _policy

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                _policy.value = PowerSavePolicy.fromPowerSaveMode(powerManager.isPowerSaveMode)
            }
        }
    }

    init {
        appContext.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
    }

    override fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }
}
