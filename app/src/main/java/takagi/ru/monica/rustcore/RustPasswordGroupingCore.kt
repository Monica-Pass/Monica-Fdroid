package takagi.ru.monica.rustcore

/** Bounded, stateless metadata projection; called once per background list snapshot. */
internal object RustPasswordGroupingCore {
    const val MAX_ENTRIES = 200_000
    const val HEADER = 4
    const val WIDTH = 6

    private val available by lazy {
        runCatching {
            System.loadLibrary("monica_rust_jni")
            nativeProject(intArrayOf(1, 0, 0, 0))?.contentEquals(intArrayOf(1, 0)) == true
        }.getOrDefault(false)
    }

    fun project(metadata: IntArray): IntArray? {
        if (metadata.size !in HEADER..(HEADER + MAX_ENTRIES * WIDTH) || !available) return null
        return runCatching { nativeProject(metadata) }.getOrNull()
    }

    @JvmStatic private external fun nativeProject(metadata: IntArray): IntArray?
}
