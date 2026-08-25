package takagi.ru.monica.repository

import takagi.ru.monica.mdbx.MdbxDiagLogger

/** Loads the UniFFI library through Android's class-loader before JNA registers it. */
internal object Mdbx2NativeRuntime {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary(LIBRARY_NAME)
                loaded = true
                MdbxDiagLogger.append("[MDBX2][native] preload succeeded")
            } catch (error: Throwable) {
                MdbxDiagLogger.append(
                    "[MDBX2][native] preload failed chain=${error.toDiagnosticChain()}"
                )
                throw error
            }
        }
    }

    private const val LIBRARY_NAME = "mdbx_ffi"
}

internal fun Throwable.toDiagnosticChain(): String = generateSequence(this) { it.cause }
    .take(5)
    .joinToString(" <- ") { cause ->
        val message = cause.message
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.take(240)
            ?.takeIf { it.isNotBlank() }
        if (message == null) cause::class.java.simpleName
        else "${cause::class.java.simpleName}:$message"
    }
