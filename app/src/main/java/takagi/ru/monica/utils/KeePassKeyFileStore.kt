package takagi.ru.monica.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import takagi.ru.monica.data.LocalKeePassDatabase

/**
 * Keeps an optional encrypted copy of a KeePass key file inside Monica's private
 * no-backup directory. The original SAF URI is never removed or replaced.
 */
class KeePassKeyFileStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, ROOT_DIRECTORY)
    private val securityManager = takagi.ru.monica.security.SecurityManager(appContext)

    fun copyFromUri(uri: Uri, displayName: String? = null): StoredKeyFile {
        val bytes = appContext.contentResolver.readKeePassKeyFileBytes(
            uri = uri,
            unavailableMessage = "无法读取 KeePass 密钥文件",
        )
        return copyBytes(bytes, displayName)
    }

    fun copyBytes(bytes: ByteArray, displayName: String? = null): StoredKeyFile = synchronized(IO_LOCK) {
        require(bytes.isNotEmpty()) { "密钥文件为空" }

        val fingerprint = fingerprint(bytes)
        val relativePath = relativePathForFingerprint(fingerprint)
        val target = fileForRelativePath(relativePath)
        val existingCopyIsValid = target.isFile && runCatching {
            KeePassKeyFileStore.fingerprint(readInternal(relativePath)) == fingerprint
        }.getOrDefault(false)
        if (!existingCopyIsValid) {
            val encrypted = securityManager.encryptDataLegacyCompat(
                Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
            replaceEncryptedFile(target, encrypted)
        }

        StoredKeyFile(
            relativePath = relativePath,
            fileName = sanitizeDisplayName(displayName),
            fingerprint = fingerprint,
            sizeBytes = bytes.size,
        )
    }

    fun readInternal(relativePath: String): ByteArray = synchronized(IO_LOCK) {
        val target = fileForRelativePath(relativePath)
        check(target.isFile) { "内部密钥文件不存在" }
        val encrypted = target.readText(Charsets.UTF_8)
        val encoded = securityManager.decryptData(encrypted)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.isNotEmpty()) { "内部密钥文件为空" }
        bytes
    }

    fun read(database: LocalKeePassDatabase): ByteArray? {
        database.keyFileInternalPath?.takeIf { it.isNotBlank() }?.let { return readInternal(it) }
        return database.keyFileUri?.takeIf { it.isNotBlank() }?.let { uri ->
            appContext.contentResolver.readKeePassKeyFileBytes(
                uri = Uri.parse(uri),
                unavailableMessage = "无法读取 KeePass 密钥文件",
            )
        }
    }

    fun exportInternal(relativePath: String, targetUri: Uri) {
        val bytes = readInternal(relativePath)
        appContext.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: error("无法写入目标文件")
    }

    fun deleteInternal(relativePath: String): Boolean = synchronized(IO_LOCK) {
        val target = fileForRelativePath(relativePath)
        !target.exists() || target.delete()
    }

    private fun replaceEncryptedFile(target: File, encrypted: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        val previous = File(target.parentFile, ".${target.name}.previous")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encrypted.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            if (target.exists()) {
                if (previous.exists()) check(previous.delete()) { "无法清理旧密钥文件临时副本" }
                check(target.renameTo(previous)) { "无法替换损坏的内部密钥文件" }
            }

            try {
                check(temporary.renameTo(target)) { "无法保存内部密钥文件" }
            } catch (error: Throwable) {
                if (!target.exists() && previous.exists()) previous.renameTo(target)
                throw error
            }
            previous.delete()
        } finally {
            temporary.delete()
            if (target.exists()) previous.delete()
        }
    }

    private fun fileForRelativePath(relativePath: String): File {
        val normalized = relativePath.replace('\\', '/')
        require(normalized.startsWith("$ROOT_DIRECTORY/")) { "非法密钥文件路径" }
        require(!normalized.contains("../") && !normalized.endsWith("/..")) {
            "非法密钥文件路径"
        }
        val target = File(appContext.noBackupFilesDir, normalized)
        check(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "非法密钥文件路径"
        }
        return target
    }

    data class StoredKeyFile(
        val relativePath: String,
        val fileName: String,
        val fingerprint: String,
        val sizeBytes: Int,
    )

    companion object {
        private const val ROOT_DIRECTORY = "keepass_keyfiles"
        private val IO_LOCK = Any()

        fun relativePathForFingerprint(fingerprint: String): String {
            val normalized = fingerprint.trim().lowercase()
            require(normalized.matches(Regex("[0-9a-f]{16,128}"))) {
                "非法密钥文件指纹"
            }
            return "$ROOT_DIRECTORY/$normalized.bin"
        }

        fun sanitizeDisplayName(value: String?): String {
            return value.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001F]"), "_")
                .trim()
                .take(120)
                .ifBlank { "keyfile" }
        }

        fun fingerprint(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
