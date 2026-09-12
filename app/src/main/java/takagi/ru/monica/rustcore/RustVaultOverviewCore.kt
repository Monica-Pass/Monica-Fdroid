package takagi.ru.monica.rustcore

import android.os.Looper

/** A numeric metadata batch, never credentials, titles, card images or animation state. */
internal object RustVaultOverviewCore {
    private val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("monica_rust_jni")
            nativeProject(longArrayOf(1, 1, 0, 0, 3, 7))
                ?.contentEquals(IntArray(13).apply { this[0] = 1 }) == true
        }.getOrDefault(false)
    }

    fun project(metadata: LongArray): IntArray? {
        // Also refuse to load the library on the UI thread. JVM tests use the Kotlin path.
        if (runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(true)) return null
        if (metadata.size !in 6..1_600_006 || !available) return null
        return runCatching { nativeProject(metadata) }.getOrNull()
    }

    @JvmStatic
    private external fun nativeProject(metadata: LongArray): IntArray?
}
