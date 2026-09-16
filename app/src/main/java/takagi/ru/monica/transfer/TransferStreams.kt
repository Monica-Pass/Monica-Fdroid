package takagi.ru.monica.transfer

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun InputStream.copyWithProgress(
    output: OutputStream,
    phase: TransferPhase,
    total: Long? = null,
    progress: TransferProgressReporter = TransferProgressReporter.None,
): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    var lastReport = 0L
    progress.report(TransferProgress(phase, total = total, bytes = true))
    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            copied += count
            val now = System.nanoTime()
            if (now - lastReport >= 100_000_000L) {
                progress.report(TransferProgress(phase, copied, total, bytes = true))
                lastReport = now
            }
        }
        progress.report(TransferProgress(phase, copied, total ?: copied, bytes = true))
        return copied
    } finally { buffer.fill(0) }
}
