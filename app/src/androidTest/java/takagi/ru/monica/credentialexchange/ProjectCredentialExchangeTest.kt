package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.data.model.StorageTarget

/** Validate the actual export with an independent JSON reader, not Monica's CXF decoder. */
class ProjectCredentialExchangeTest {
    @Test fun localExportPreservesEveryAccountPasswordAndOtp() = runBlocking { exercise(ImportDestinationKind.LOCAL) }
    @Test fun mdbxExportReadsEveryAccountAndOtpFromNativeFile() = runBlocking { exercise(ImportDestinationKind.MDBX) }
    @Test fun keepassExportReadsEveryAccountAndOtpFromNativeFile() = runBlocking { exercise(ImportDestinationKind.KEEPASS) }
    @Test fun bitwardenExportPreservesEveryAccountAndOtp() = runBlocking { exercise(ImportDestinationKind.BITWARDEN) }

    private suspend fun exercise(kind: ImportDestinationKind) {
        val f = TransferFixture()
        val vm = PasswordViewModel(f.passwords, f.security, customFieldRepository = CustomFieldRepository(f.db.customFieldDao()),
            context = f.context, localKeePassDatabaseDao = f.db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(f.context))
        try {
            val destination = when (kind) {
                ImportDestinationKind.LOCAL -> ImportDestination(kind)
                ImportDestinationKind.MDBX -> f.mdbx()
                ImportDestinationKind.KEEPASS -> f.keepass()
                ImportDestinationKind.BITWARDEN -> f.bitwarden()
            }
            val target = when (kind) {
                ImportDestinationKind.LOCAL -> StorageTarget.MonicaLocal(null)
                ImportDestinationKind.MDBX -> StorageTarget.Mdbx(destination.databaseId!!)
                ImportDestinationKind.KEEPASS -> StorageTarget.KeePass(destination.databaseId!!, null)
                ImportDestinationKind.BITWARDEN -> StorageTarget.Bitwarden(destination.databaseId, null)
            }
            val title = "${f.prefix}-export"
            val groups = listOf(
                ProjectCredentialGroup.Group(username = f.rawUsername, otp = "JBSWY3DPEHPK3PXP",
                    passwords = listOf(ProjectCredentialGroup.Password(value = f.rawPassword), ProjectCredentialGroup.Password(value = "second-password"))),
                ProjectCredentialGroup.Group(label = "Work", username = "work@example.invalid",
                    otp = "otpauth://totp/Work:work%40example.invalid?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Work&algorithm=SHA256&digits=8&period=60",
                    passwords = listOf(ProjectCredentialGroup.Password(value = "work-password-1"), ProjectCredentialGroup.Password(value = "work-password-2"))),
                // Same username is intentional: it must not be merged or assigned the Work OTP.
                ProjectCredentialGroup.Group(username = "work@example.invalid", otp = "",
                    passwords = listOf(ProjectCredentialGroup.Password(value = "M DK|literal-not-cipher"))))
            val done = CompletableDeferred<Long?>()
            vm.savePasswordsAcrossTargets(emptyList(), PasswordEntry(title = title, username = "", password = "", website = f.website,
                notes = "共享备注\nShared notes"), emptyList(), listOf(target), projectCredentials = groups, onComplete = { done.complete(it) })
            requireNotNull(withTimeout(60_000) { done.await() })
            val before = f.importedPasswords(destination)
            assertEquals(5, before.size)
            val exported = CredentialExchangeExporter(f.context).prepare(destination, setOf("basic-auth", "totp", "note"))
            val items = JSONObject(exported.json).getJSONArray("accounts").getJSONObject(0).getJSONArray("items")
            val ours = (0 until items.length()).map(items::getJSONObject).filter { it.getString("title") == title }
            assertEquals(5, ours.size)
            assertEquals(5, ours.map { it.getString("id") }.distinct().size)
            val expected = ProjectCredentialGroup.rows(groups)
            ours.forEach { item ->
                val credentials = item.getJSONArray("credentials")
                val fields = (0 until credentials.length()).map(credentials::getJSONObject)
                val auth = fields.single { it.getString("type") == "basic-auth" }
                assertEquals("concealed-string", auth.getJSONObject("password").getString("fieldType"))
                assertEquals("string", auth.getJSONObject("username").getString("fieldType"))
                val password = auth.getJSONObject("password").getString("value")
                val row = expected.single { it.password.value == password }
                assertEquals(row.username, auth.getJSONObject("username").getString("value"))
                val otp = fields.singleOrNull { it.getString("type") == "totp" }
                if (row.otp.isEmpty()) assertNull(otp) else {
                    requireNotNull(otp)
                    val work = row.username == "work@example.invalid"
                    assertEquals(if (work) "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" else "JBSWY3DPEHPK3PXP", otp.getString("secret"))
                    assertEquals(if (work) "sha256" else "sha1", otp.getString("algorithm"))
                    assertEquals(if (work) 8 else 6, otp.getInt("digits"))
                    assertEquals(if (work) 60 else 30, otp.getInt("period"))
                    assertEquals(row.username.trim(), otp.getString("username"))
                }
                assertEquals(f.website, item.getJSONObject("scope").getJSONArray("urls").getString(0))
                assertEquals("共享备注\nShared notes", fields.single { it.getString("type") == "note" }.getJSONObject("content").getString("value"))
                assertFalse(item.toString().contains(ProjectCredentialGroup.FIELD))
            }
            val withoutOtp = CredentialExchangeExporter(f.context).prepare(destination, setOf("basic-auth"))
            assertFalse(withoutOtp.json.contains("\"type\":\"totp\""))
            assertTrue(withoutOtp.skippedOtps >= 4)
            assertEquals(before, f.importedPasswords(destination))
        } finally { vm.viewModelScope.cancel(); f.close() }
    }

    @Test fun encryptedLegacyUsernameIsPlaintextOnlyInExportAndUnsupportedOtpIsReported() = runBlocking {
        val f = TransferFixture()
        try {
            val id = f.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = f.prefix, website = "",
                username = f.security.encryptData(f.rawUsername), password = f.security.encryptData(f.rawPassword),
                authenticatorKey = f.security.encryptData("otpauth://hotp/Test?secret=JBSWY3DPEHPK3PXP&counter=3")))
            val result = CredentialExchangeExporter(f.context).prepare(ImportDestination(ImportDestinationKind.LOCAL), setOf("basic-auth", "totp"))
            val items = JSONObject(result.json).getJSONArray("accounts").getJSONObject(0).getJSONArray("items")
            val item = (0 until items.length()).map(items::getJSONObject).single { it.getString("title") == f.prefix }
            val auth = item.getJSONArray("credentials").getJSONObject(0)
            assertEquals(f.rawUsername, auth.getJSONObject("username").getString("value"))
            assertEquals(f.rawPassword, auth.getJSONObject("password").getString("value"))
            assertEquals(1, item.getJSONArray("credentials").length())
            assertTrue(result.skippedOtps >= 1)
            assertNotEquals(f.rawUsername, f.db.passwordEntryDao().getPasswordEntryById(id)!!.username)
            listOf("not a secret!", "otpauth://totp/Test?secret=JBSWY3DPEHPK3PXP&algorithm=SHA3", "steam://eHl6")
                .forEach { assertNull(CxfTotpExport.fromPayload(it, "", "")) }
        } finally { f.close() }
    }
}
