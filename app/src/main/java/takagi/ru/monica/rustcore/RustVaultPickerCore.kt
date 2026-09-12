package takagi.ru.monica.rustcore

import android.os.Looper

/** Sheet-owned display metadata. No password, full card number, note body or artwork crosses JNI. */
internal object RustVaultPickerCore {
    private fun isWorkerThread(): Boolean =
        runCatching { Looper.myLooper() != Looper.getMainLooper() }.getOrDefault(false)

    private val available by lazy {
        runCatching {
            System.loadLibrary("monica_rust_jni")
            val handle = nativeOpen(byteArrayOf(0x4d, 0x4f, 0x50, 0x31, 0, 0, 0, 0))
            if (handle <= 0) false else try {
                nativeFilter(handle, "", -1)?.isEmpty() == true
            } finally {
                nativeClose(handle)
            }
        }.getOrDefault(false)
    }

    fun open(metadata: ByteArray): Long? {
        if (!isWorkerThread() || metadata.size !in 8..64 * 1024 * 1024 || !available) return null
        return runCatching { nativeOpen(metadata).takeIf { it > 0 } }.getOrNull()
    }

    fun filter(handle: Long, normalizedQuery: String, source: Int): IntArray? {
        if (!isWorkerThread() || handle <= 0 || source < -1) return null
        return runCatching { nativeFilter(handle, normalizedQuery, source) }.getOrNull()
    }

    fun close(handle: Long) {
        if (handle > 0) runCatching { nativeClose(handle) }
    }

    @JvmStatic private external fun nativeOpen(metadata: ByteArray): Long
    @JvmStatic private external fun nativeFilter(handle: Long, query: String, source: Int): IntArray?
    @JvmStatic private external fun nativeClose(handle: Long)
}
