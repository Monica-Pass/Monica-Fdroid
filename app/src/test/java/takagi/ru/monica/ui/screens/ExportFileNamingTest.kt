package takagi.ru.monica.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileNamingTest {

    @Test
    fun plainZipUsesRegularZipExtension() {
        val spec = exportDocumentSpec(
            selectedOption = ExportOption.ZIP_BACKUP,
            currentTimeMillis = 0L,
            encryptedZip = false,
        )

        assertTrue(spec.fileName.endsWith(".zip"))
        assertFalse(spec.fileName.endsWith(".enc.zip"))
        assertTrue(spec.mimeType == "application/zip")
    }

    @Test
    fun encryptedZipUsesEncZipExtension() {
        val spec = exportDocumentSpec(
            selectedOption = ExportOption.ZIP_BACKUP,
            currentTimeMillis = 0L,
            encryptedZip = true,
        )

        assertTrue(spec.fileName.endsWith(".enc.zip"))
        assertTrue(spec.mimeType == "application/zip")
    }
}
