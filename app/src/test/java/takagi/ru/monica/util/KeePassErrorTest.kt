package takagi.ru.monica.util

import takagi.ru.monica.localization.xmlTestStrings

import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import takagi.ru.monica.utils.KeePassErrorCode
import takagi.ru.monica.utils.KeePassOperationException
import takagi.ru.monica.utils.toOneDriveUserMessage
import takagi.ru.monica.utils.toKeePassOperationException
import java.io.IOException

class KeePassErrorTest {

    @Test
    fun invalidKey_mapsToInvalidCredential() {
        val ex = CryptoError.InvalidKey("Wrong key used for decryption.")
            .toKeePassOperationException(xmlTestStrings("zh"))
        assertEquals(KeePassErrorCode.INVALID_CREDENTIAL, ex.code)
    }

    @Test
    fun unsupportedVersion_mapsToFormatUnsupported() {
        val ex = FormatError.UnsupportedVersion("File version is not supported.")
            .toKeePassOperationException(xmlTestStrings("zh"))
        assertEquals(KeePassErrorCode.FORMAT_UNSUPPORTED, ex.code)
    }

    @Test
    fun securityException_mapsToPermissionDenied() {
        val ex = SecurityException("Permission denied")
            .toKeePassOperationException(xmlTestStrings("zh"))
        assertEquals(KeePassErrorCode.URI_PERMISSION_DENIED, ex.code)
    }

    @Test
    fun outOfMemory_mapsToKdfMemoryInsufficient() {
        val ex = OutOfMemoryError("Argon2 memory")
            .toKeePassOperationException(xmlTestStrings("zh"))
        assertEquals(KeePassErrorCode.KDF_MEMORY_INSUFFICIENT, ex.code)
    }

    @Test
    fun ioException_mapsToReadWriteFailed() {
        val ex = IOException("Disk error")
            .toKeePassOperationException(xmlTestStrings("zh"))
        assertEquals(KeePassErrorCode.IO_READ_WRITE_FAILED, ex.code)
    }

    @Test
    fun keyFileUnavailable_keepsDedicatedRecoveryCode() {
        val original = KeePassOperationException(
            KeePassErrorCode.KEY_FILE_UNAVAILABLE,
            "密钥文件不可访问"
        )
        val mapped = original.toKeePassOperationException(xmlTestStrings("zh"))

        assertSame(original, mapped)
        assertEquals(KeePassErrorCode.KEY_FILE_UNAVAILABLE, mapped.code)
    }

    @Test
    fun oneDriveRedirectConflict_mapsToActionableMessage() {
        val raw = IllegalStateException(
            "More than one app is listening for the URL scheme defined for " +
                "BrowserTabActivity in the AndroidManifest. " +
                "The package name of this other app is: takagi.ru.monica.steamapp"
        )
        val error = RuntimeException("OneDrive sync failed", raw)
        val expected = "旧版 Monica Steam 占用了 OneDrive 登录回调，请更新 Monica Steam 后重试。数据库文件未损坏。"

        val mapped = error.toKeePassOperationException(xmlTestStrings("zh"))

        assertEquals(KeePassErrorCode.ONEDRIVE_REDIRECT_CONFLICT, mapped.code)
        assertEquals(expected, mapped.message)
        assertEquals(expected, error.toOneDriveUserMessage(xmlTestStrings("zh")))
    }

    @Test
    fun legacyKdbMessage_mapsToLegacyUnsupported() {
        val ex = IllegalStateException("legacy kdb file is not supported")
            .toKeePassOperationException(xmlTestStrings("zh"))
        assertEquals(KeePassErrorCode.LEGACY_KDB_UNSUPPORTED, ex.code)
    }

    @Test
    fun mappedException_keepsOriginalInstance() {
        val original = KeePassOperationException(
            KeePassErrorCode.INVALID_CREDENTIAL,
            "数据库密码或密钥文件不正确"
        )
        val mapped = original.toKeePassOperationException(xmlTestStrings("zh"))
        assertSame(original, mapped)
    }
}
