package takagi.ru.monica.utils

internal class KeePassPortableSecretExportException(
    val entryTitle: String,
    cause: Throwable
) : IllegalStateException(
    "无法读取“${entryTitle.ifBlank { "未命名条目" }}”的敏感数据，已取消 KDBX 导出。请先解锁 Monica 后重试。",
    cause
)

/** KDBX 必须保存可跨设备读取的真实值，不能混入 Monica 的设备绑定密文。 */
internal object KeePassPortableSecretExportPolicy {
    fun resolve(
        storedValue: String,
        entryTitle: String,
        decrypt: (String) -> String
    ): String {
        if (storedValue.isEmpty()) return ""
        return try {
            decrypt(storedValue)
        } catch (error: Throwable) {
            throw KeePassPortableSecretExportException(entryTitle, error)
        }
    }
}
