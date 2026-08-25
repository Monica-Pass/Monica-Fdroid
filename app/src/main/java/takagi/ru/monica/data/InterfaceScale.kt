package takagi.ru.monica.data

import kotlin.math.roundToInt

object InterfaceScale {
    const val MIN_PERCENT = 80
    const val DEFAULT_PERCENT = 100
    const val MAX_PERCENT = 120

    fun normalizePercent(percent: Int?): Int =
        (percent ?: DEFAULT_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun calculateDensity(baseDensity: Float, percent: Int): Float =
        (baseDensity * normalizePercent(percent) / DEFAULT_PERCENT.toFloat())
            .coerceAtLeast(0.1f)

    fun calculateEffectiveDpi(baseDensityDpi: Int, percent: Int): Int =
        (baseDensityDpi.coerceAtLeast(1) *
            normalizePercent(percent) / DEFAULT_PERCENT.toFloat())
            .roundToInt()
}
