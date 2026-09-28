package takagi.ru.monica.credentialexchange

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.util.DataExportImportManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.*

@RunWith(AndroidJUnit4::class)
class LocalOtpRestoreIntegrityTest {
    @Test fun parsingAnOverwriteArchiveNeverDeletesExistingRecords() = runBlocking {
        val f = TransferFixture()
        try {
            val id = f.secureItems.insertItem(SecureItem(itemType = ItemType.TOTP, title = f.prefix,
                itemData = "JBSWY3DPEHPK3PXP"))
            // Even the negative control is rolled back: never persist a clear of shared AVD data.
            try {
                f.db.withTransaction {
                    val file = java.io.File(f.root, "restore.zip")
                    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                        zip.putNextEntry(java.util.zip.ZipEntry("totp/fixture.json"))
                        zip.write(org.json.JSONObject().put("id", 1).put("title", f.prefix)
                            .put("itemData", "JBSWY3DPEHPK3PXP").toString().toByteArray())
                        zip.closeEntry()
                    }
                    val parsed = WebDavHelper(f.context).restoreFromBackupFile(file, overwrite = true,
                        restoreMonicaConfig = false).getOrThrow()
                    assertNotNull("Parsing is not a committed restore", f.secureItems.getItemById(id))
                    val result = BackupRestoreApplier.applyRestoreResult(f.context, parsed,
                        f.passwords, f.secureItems, true, "LocalOtpIntegrityTest")
                    assertEquals(1, result.secureItemImported)
                    assertNull(f.secureItems.getItemById(id))
                    assertEquals("JBSWY3DPEHPK3PXP", rows(f).single().let { parse(f, it).secret })
                    throw RollbackFixture()
                }
            } catch (_: RollbackFixture) { }
        } finally { f.close() }
    }

    private class RollbackFixture : RuntimeException()

    @Test fun damagedRecordDoesNotBlankTheListAndLaterWritesStillAppear() = runBlocking {
        val f = TransferFixture()
        val model = takagi.ru.monica.viewmodel.TotpViewModel(f.secureItems, f.passwords,
            securityManager = f.security, strings = AppLocaleStringResolver(f.context))
        try {
            val valid = f.secureItems.insertItem(SecureItem(itemType = ItemType.TOTP,
                title = f.prefix, itemData = f.security.encryptDataLegacyCompat("JBSWY3DPEHPK3PXP")))
            val damaged = f.secureItems.insertItem(SecureItem(itemType = ItemType.TOTP,
                title = f.prefix, itemData = """{"secret":{"unexpected":"object"}}"""))
            f.passwords.insertPasswordEntry(PasswordEntry(title = f.prefix, website = "", username = "", password = "",
                authenticatorKey = "C2|unreadable-password-otp"))
            val first = withTimeout(10_000) { model.parsedTotpState.first { state ->
                state.isReady && state.items.any { it.item.id == valid } && state.items.any { it.item.id == damaged }
            } }
            assertEquals("JBSWY3DPEHPK3PXP", first.items.single { it.item.id == valid }.totpData.secret)
            assertEquals("", first.items.single { it.item.id == damaged }.totpData.secret)
            val original = requireNotNull(f.secureItems.getItemById(damaged))
            f.secureItems.updateItem(original.copy(itemData = "GEZDGNBVGY3TQOJQ"))
            withTimeout(10_000) { model.parsedTotpState.first { state ->
                state.items.any { it.item.id == damaged && it.totpData.secret == "GEZDGNBVGY3TQOJQ" }
            } }
            assertNotNull(f.secureItems.getItemById(valid))
        } finally { model.viewModelScope.cancel(); f.close() }
    }

    @Test fun legacyMissingDefaultsDeduplicateWithoutDiscardingUnknownMetadata() = runBlocking {
        val f = TransferFixture()
        try {
            val minimal = """{"secret":"JBSWY3DPEHPK3PXP","issuer":"${f.prefix}"}"""
            assertEquals(1, restore(f, minimal).secureItemImported)
            val explicitDefaults = Json { encodeDefaults = true }.encodeToString(TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = f.prefix))
            assertEquals(1, restore(f, explicitDefaults).secureItemSkipped)
            val extended = org.json.JSONObject(minimal).put("futureRecovery", "synthetic metadata").toString()
            assertEquals(1, restore(f, extended).secureItemImported)
            assertEquals(1, restore(f, extended).secureItemSkipped)
            assertEquals(2, rows(f).size)
            assertTrue(rows(f).any { f.security.decryptDataIfMonicaCiphertext(it.itemData).contains("futureRecovery") })
        } finally { f.close() }
    }

    @Test fun restoringPasswordBindingsPreservesUnknownOtpFields() = runBlocking {
        val f = TransferFixture()
        try {
            val password = PasswordEntry(id = 9002, title = f.prefix, username = "alice", website = "", password = "synthetic")
            val payload = org.json.JSONObject().put("secret", "JBSWY3DPEHPK3PXP").put("issuer", f.prefix)
                .put("boundPasswordId", password.id).put("futureRecovery", org.json.JSONObject().put("value", "synthetic metadata"))
                .toString()
            assertEquals(1, restore(f, payload, listOf(password)).secureItemImported)
            val saved = org.json.JSONObject(f.security.decryptDataIfMonicaCiphertext(rows(f).single().itemData))
            assertEquals("synthetic metadata", saved.getJSONObject("futureRecovery").getString("value"))
            val bound = requireNotNull(f.passwords.getPasswordEntryById(saved.getLong("boundPasswordId")))
            assertEquals(f.prefix, bound.title)
            assertEquals(1, restore(f, payload, listOf(password)).secureItemSkipped)
        } finally { f.close() }
    }

    @Test fun restoreKeepsBothKeysForAnAccountAfterRotation() = runBlocking {
        val f = TransferFixture()
        try {
            val old = TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = f.prefix, accountName = "alice")
            val fresh = old.copy(secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
            val id = f.secureItems.insertItem(SecureItem(itemType = ItemType.TOTP, title = f.prefix,
                itemData = f.security.encryptDataLegacyCompat(Json.encodeToString(old))))
            val before = requireNotNull(f.secureItems.getItemById(id))
            val first = restore(f, Json.encodeToString(fresh))
            assertEquals("Different secret must be imported", 1, first.secureItemImported)
            assertEquals(before, f.secureItems.getItemById(id))
            val second = restore(f, Json.encodeToString(fresh))
            assertEquals(0, second.secureItemImported)
            assertEquals(1, second.secureItemSkipped)
            assertEquals(setOf(old.secret, fresh.secret), rows(f).map { parse(f, it).secret }.toSet())
        } finally { f.close() }
    }

    @Test fun restoreKeepsDifferentOtpParametersAndRecoveryDetails() = runBlocking {
        val f = TransferFixture()
        try {
            val data = TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = f.prefix, accountName = "alice")
            f.secureItems.insertItem(SecureItem(itemType = ItemType.TOTP, title = f.prefix,
                itemData = Json.encodeToString(data)))
            val changed = data.copy(digits = 8, period = 60, algorithm = "SHA256", steamRevocationCode = "synthetic-recovery")
            assertEquals(1, restore(f, Json.encodeToString(changed)).secureItemImported)
            assertEquals(2, rows(f).size)
            assertTrue(rows(f).map { parse(f, it) }.any { it == changed })
        } finally { f.close() }
    }

    @Test fun failedRestoreKeepsOriginalRowsAndRetryCanRecover() = runBlocking {
        val f = TransferFixture()
        try {
            val original = SecureItem(itemType = ItemType.TOTP, title = f.prefix, itemData = "C2|damaged")
            val id = f.secureItems.insertItem(original)
            assertEquals(1, restore(f, "C2|unreadable-backup").secureItemFailed)
            assertEquals(original.itemData, f.secureItems.getItemById(id)?.itemData)
            val valid = Json.encodeToString(TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = f.prefix))
            assertEquals(1, restore(f, valid).secureItemImported)
            assertEquals(2, rows(f).size)
            assertEquals(original.itemData, f.secureItems.getItemById(id)?.itemData)
        } finally { f.close() }
    }

    private suspend fun rows(f: TransferFixture) = f.db.secureItemDao().getActiveLocalItemsByTypeSync(ItemType.TOTP)
        .filter { it.title == f.prefix }

    private fun parse(f: TransferFixture, item: SecureItem) = requireNotNull(TotpDataResolver.parseStoredItemData(
        item.itemData, decryptIfNeeded = f.security::decryptDataIfMonicaCiphertext))

    private suspend fun restore(f: TransferFixture, data: String, passwords: List<PasswordEntry> = emptyList()): RestoreApplyStats {
        val item = DataExportImportManager.ExportItem(9001, "TOTP", f.prefix, data, "", false, "", 1000, 2000)
        val counts = ItemCounts(totp = 1, passwords = passwords.size)
        return BackupRestoreApplier.applyRestoreResult(f.context,
            RestoreResult(BackupContent(passwords, listOf(item)), RestoreReport(true, counts, counts, emptyList(), emptyList())),
            f.passwords, f.secureItems, localOnlyDedup = true, logTag = "LocalOtpIntegrityTest")
    }
}
