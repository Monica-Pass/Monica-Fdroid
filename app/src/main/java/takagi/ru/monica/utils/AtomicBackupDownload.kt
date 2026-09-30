package takagi.ru.monica.utils

import android.util.AtomicFile
import java.io.File
import java.io.OutputStream

/** A failed download must not replace a previously complete restore archive. */
internal inline fun writeBackupAtomically(destination: File, write: (OutputStream) -> Unit) {
    val atomic = AtomicFile(destination)
    val stream = atomic.startWrite()
    try {
        write(stream)
        atomic.finishWrite(stream)
    } catch (error: Throwable) {
        atomic.failWrite(stream)
        throw error
    }
}
