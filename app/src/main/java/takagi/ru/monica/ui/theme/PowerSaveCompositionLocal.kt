package takagi.ru.monica.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import takagi.ru.monica.perf.PowerSavePolicy

val LocalPowerSavePolicy: ProvidableCompositionLocal<PowerSavePolicy> =
    compositionLocalOf { PowerSavePolicy.NORMAL }
