package takagi.ru.monica.utils

import java.io.ByteArrayInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionHelperHeaderTest {

    @Test
    fun recognizesEncryptedBackupHeaderWithoutRelyingOnExtension() {
        assertTrue(
            EncryptionHelper.hasEncryptedFileHeader(
                ByteArrayInputStream("MONICA_ENC_V1payload".toByteArray())
            )
        )
    }

    @Test
    fun rejectsPlainZipHeaderAsEncryptedBackup() {
        assertFalse(
            EncryptionHelper.hasEncryptedFileHeader(
                ByteArrayInputStream(
                    byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "payload".toByteArray()
                )
            )
        )
    }
}
