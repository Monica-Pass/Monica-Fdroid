package takagi.ru.monica.ui

internal class TotpCountdownVibrationGate {
    private var lastVibrationSecond: Long? = null

    fun shouldVibrate(epochSecond: Long, remainingSeconds: Collection<Int>): Boolean {
        if (remainingSeconds.none { it in 1..5 }) return false
        if (lastVibrationSecond == epochSecond) return false
        lastVibrationSecond = epochSecond
        return true
    }
}
