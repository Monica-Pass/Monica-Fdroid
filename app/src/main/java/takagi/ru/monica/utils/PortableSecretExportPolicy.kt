package takagi.ru.monica.utils

internal class PortableSecretExportException(
    val entryTitle: String,
    cause: Throwable
) : IllegalStateException(
    "无法读取“${entryTitle.ifBlank { "未命名条目" }}”的敏感数据，已取消备份。请先解锁 Monica 后重试。",
    cause
)

/** Backups must contain portable values, never ciphertext bound to the current installation. */
internal object PortableSecretExportPolicy {
    fun resolve(
        storedValue: String,
        entryTitle: String,
        decryptIfNeeded: (String) -> String
    ): String {
        if (storedValue.isEmpty()) return ""
        return try {
            decryptIfNeeded(storedValue)
        } catch (error: Throwable) {
            throw PortableSecretExportException(entryTitle, error)
        }
    }
}
