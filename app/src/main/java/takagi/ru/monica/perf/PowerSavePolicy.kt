package takagi.ru.monica.perf

/**
 * Central policy for graceful battery-saver degradation.
 * Essential/user-triggered work remains enabled in both modes.
 */
data class PowerSavePolicy(
    val isPowerSaveMode: Boolean,
    val allowContinuousDecorativeMotion: Boolean,
    val allowBackgroundPrewarm: Boolean,
    val useReducedDecorativeElevation: Boolean
) {
    companion object {
        fun fromPowerSaveMode(enabled: Boolean): PowerSavePolicy = PowerSavePolicy(
            isPowerSaveMode = enabled,
            allowContinuousDecorativeMotion = !enabled,
            allowBackgroundPrewarm = !enabled,
            useReducedDecorativeElevation = enabled
        )

        val NORMAL = fromPowerSaveMode(false)
        val POWER_SAVE = fromPowerSaveMode(true)
    }
}
