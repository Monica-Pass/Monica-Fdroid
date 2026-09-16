package takagi.ru.monica.rustcore

import android.os.Looper
import java.io.ByteArrayOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers

/** Service-owned matching metadata only. No credentials or PasswordEntry objects are retained. */
internal object RustAutofillCore {
    internal data class Row(
        val packages: Set<String>,
        val hosts: Set<String>,
        val roots: Set<String>,
        val labels: Set<String>,
    )

    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_FIELD_BYTES = 4096

    private fun isWorkerThread(): Boolean =
        runCatching { Looper.myLooper() != Looper.getMainLooper() }.getOrDefault(false)

    private val available by lazy {
        runCatching {
            System.loadLibrary("monica_rust_jni")
            val handle = nativeOpen(byteArrayOf(0x4d, 0x41, 0x46, 0x31, 0, 0, 0, 0))
            if (handle <= 0) false else try {
                nativeQuery(handle, "", "", "", "")?.isEmpty() == true
            } finally { nativeClose(handle) }
        }.getOrDefault(false)
    }

    fun open(rows: List<Row>): Long? {
        if (rows.size > 100_000 || !isWorkerThread() || !available) return null
        return runCatching {
            val output = ByteArrayOutputStream()
            fun number(value: Int) {
                require(output.size() <= MAX_BYTES - 4)
                repeat(4) { output.write(value ushr (it * 8) and 255) }
            }
            number(0x3146414d)
            number(rows.size)
            for (row in rows) {
                for (fields in listOf(row.packages, row.hosts, row.roots, row.labels)) {
                    number(fields.size)
                    for (field in fields) {
                        require(field.length <= MAX_FIELD_BYTES)
                        val bytes = field.toByteArray(Charsets.UTF_8)
                        require(bytes.size <= MAX_FIELD_BYTES)
                        require(output.size().toLong() + 4 + bytes.size <= MAX_BYTES)
                        number(bytes.size)
                        output.write(bytes)
                    }
                }
            }
            nativeOpen(output.toByteArray()).takeIf { it > 0 }
        }.getOrNull()
    }

    fun query(handle: Long, packageName: String, host: String, root: String, label: String): IntArray? {
        if (handle <= 0 || !isWorkerThread()) return null
        if (maxOf(packageName.length, host.length, root.length, label.length) > MAX_FIELD_BYTES) return null
        return runCatching { nativeQuery(handle, packageName, host, root, label) }.getOrNull()
    }

    fun close(handle: Long) {
        if (handle <= 0) return
        if (isWorkerThread()) {
            runCatching { nativeClose(handle) }
        } else {
            // Dropping a large index also allocates CPU time; keep lifecycle/UI callbacks free.
            Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable {
                runCatching { nativeClose(handle) }
            })
        }
    }

    @JvmStatic private external fun nativeOpen(metadata: ByteArray): Long
    @JvmStatic private external fun nativeQuery(handle: Long, packageName: String, host: String, root: String, label: String): IntArray?
    @JvmStatic private external fun nativeClose(handle: Long)
}
