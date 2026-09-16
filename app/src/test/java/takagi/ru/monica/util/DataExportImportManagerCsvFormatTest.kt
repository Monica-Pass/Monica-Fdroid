package takagi.ru.monica.util

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class DataExportImportManagerCsvFormatTest {

    private val manager = DataExportImportManager(ContextWrapper(null))

    @Test
    fun chromeHeader_prefersChromeFormat() {
        val format = detectCsvFormat("name,url,username,password,note")

        assertEquals(DataExportImportManager.CsvFormat.CHROME_PASSWORD, format)
    }

    @Test
    fun passwordKeyboardHeader_stillDetectedAsPasswordKeyboard() {
        val format = detectCsvFormat("username,password,title,remarks,url,tag,custom")

        assertEquals(DataExportImportManager.CsvFormat.PASSWORD_KEYBOARD, format)
    }

    @Test
    fun protonPassHeader_prefersProtonFormatOverChrome() {
        val format = detectCsvFormat("type,name,url,email,username,password,note,totp,createTime,modifyTime,vault")

        assertEquals(DataExportImportManager.CsvFormat.PROTON_PASS_PASSWORD, format)
    }

    @Test
    fun protonPassLoginRow_mapsPasswordTotpVaultAndTimestamps() {
        val header = "type,name,url,email,username,password,note,totp,createTime,modifyTime,vault"
        val row = "login,标题,https://website,user@example.com,用户名或者邮箱,password,note,otpauth://totp/?secret=2fa&algorithm=SHA1&digits=6&period=30,1780131342,1780131342000,Personal"
        val item = createExportItemFromFormat(
            fields = parseCsvLine(row),
            format = DataExportImportManager.CsvFormat.PROTON_PASS_PASSWORD,
            headerIndexMap = buildHeaderIndexMap(parseCsvLine(header))
        )

        assertNotNull(item)
        requireNotNull(item)
        assertEquals("PASSWORD", item.itemType)
        assertEquals("标题", item.title)
        val credentials = CsvPasswordData.decode(item.itemData)
        assertEquals("用户名或者邮箱", credentials["username"])
        assertEquals("password", credentials["password"])
        assertEquals("https://website", credentials["website"])
        assertEquals("user@example.com", credentials["email"])
        assertEquals("note", item.notes)
        assertEquals("otpauth://totp/?secret=2fa&algorithm=SHA1&digits=6&period=30", item.importedAuthenticatorKey)
        assertEquals(1_780_131_342_000L, item.createdAt)
        assertEquals(1_780_131_342_000L, item.updatedAt)
        assertEquals(1, item.importedCustomFields.size)
        assertEquals("Proton Vault", item.importedCustomFields.first().title)
        assertEquals("Personal", item.importedCustomFields.first().value)
    }

    @Test
    fun protonPassNonLoginRows_areSkipped() {
        val header = "type,name,url,email,username,password,note,totp,createTime,modifyTime,vault"
        val row = "note,Secure note,,,,,hello,,,,Personal"
        val item = createExportItemFromFormat(
            fields = parseCsvLine(row),
            format = DataExportImportManager.CsvFormat.PROTON_PASS_PASSWORD,
            headerIndexMap = buildHeaderIndexMap(parseCsvLine(header))
        )

        assertEquals(null, item)
    }

    @Test
    fun protonPassEmail_fillsUsernameWhenUsernameIsBlank() {
        val header = "type,name,url,email,username,password,note,totp,createTime,modifyTime,vault"
        val row = "login,Email Only,https://example.com,email@example.com,,secret,,,,,"
        val item = createExportItemFromFormat(
            fields = parseCsvLine(row),
            format = DataExportImportManager.CsvFormat.PROTON_PASS_PASSWORD,
            headerIndexMap = buildHeaderIndexMap(parseCsvLine(header))
        )

        assertNotNull(item)
        requireNotNull(item)
        val credentials = CsvPasswordData.decode(item.itemData)
        assertEquals("email@example.com", credentials["username"])
        assertEquals("secret", credentials["password"])
        assertEquals("https://example.com", credentials["website"])
        assertEquals("email@example.com", credentials["email"])
        assertFalse(item.importedCustomFields.any { it.title == "Proton Vault" })
    }

    private fun detectCsvFormat(header: String): DataExportImportManager.CsvFormat {
        val method = DataExportImportManager::class.java.getDeclaredMethod(
            "detectCsvFormat",
            String::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(manager, header) as DataExportImportManager.CsvFormat
    }

    private fun parseCsvLine(line: String): List<String> {
        val method = DataExportImportManager::class.java.getDeclaredMethod(
            "parseCsvLine",
            String::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(manager, line) as List<String>
    }

    private fun buildHeaderIndexMap(headers: List<String>): Map<String, Int> {
        val method = DataExportImportManager::class.java.getDeclaredMethod(
            "buildHeaderIndexMap",
            List::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(manager, headers) as Map<String, Int>
    }

    private fun createExportItemFromFormat(
        fields: List<String>,
        format: DataExportImportManager.CsvFormat,
        headerIndexMap: Map<String, Int>
    ): DataExportImportManager.ExportItem? {
        val method = DataExportImportManager::class.java.getDeclaredMethod(
            "createExportItemFromFormat",
            List::class.java,
            DataExportImportManager.CsvFormat::class.java,
            Map::class.java,
            DataExportImportManager.PasswordKeyboardTagHandling::class.java
        )
        method.isAccessible = true
        return method.invoke(
            manager,
            fields,
            format,
            headerIndexMap,
            DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
        ) as DataExportImportManager.ExportItem?
    }
}
