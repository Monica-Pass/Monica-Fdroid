package takagi.ru.monica.security

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.*
import java.io.File
import java.security.KeyStore

/** Ordered by the host upgrade script, with process death and APK replacement between phases. */
class LocalVaultUpgradeTest {
    @Test fun editEveryTypeAndEveryEmbeddedContentAfterUpgrade() = runBlocking {
        val f = LegacyVaultCorpus()
        f.verify()
        val passwordRepo = PasswordRepository(f.db.passwordEntryDao())
        val itemRepo = SecureItemRepository(f.db.secureItemDao())
        val originals = f.db.passwordEntryDao().getAllPasswordEntriesSync()
        for (original in originals) {
            val edited = original.copy(notes = original.notes + "\nEdited after upgrade",
                password = if (original.loginType in setOf("PASSWORD", "API_KEY", "WIFI"))
                    f.security.encryptData("edited synthetic secret for " + original.loginType) else original.password)
            passwordRepo.updatePasswordEntry(edited)
            assertEquals(edited, f.db.passwordEntryDao().getPasswordEntryById(original.id))
            passwordRepo.updatePasswordEntry(original)
        }
        for (original in f.db.secureItemDao().getAllItems().first()) {
            val data = org.json.JSONObject(f.security.decryptData(original.itemData))
            val field = when (original.itemType) {
                ItemType.TOTP -> "issuer"
                ItemType.BANK_CARD -> "cardholderName"
                ItemType.DOCUMENT -> "issuedBy"
                ItemType.BILLING_ADDRESS -> "streetAddress"
                ItemType.PAYMENT_ACCOUNT -> "accountName"
                ItemType.NOTE -> "content"
                else -> error("Unexpected fixture type")
            }
            data.put(field, "Edited after upgrade 中文 🔑")
            val edited = original.copy(itemData = f.security.encryptData(data.toString()), notes = "Edited notes")
            itemRepo.updateItem(edited)
            val read = checkNotNull(f.db.secureItemDao().getItemById(original.id))
            assertEquals(edited, read)
            assertEquals(data.toString(), f.security.decryptData(read.itemData))
            itemRepo.updateItem(original)
        }
        val main = originals.single { it.title == f.prefix + "Everything" }
        val dao = f.db.customFieldDao()
        val originalFields = dao.getFieldsByEntryId(main.id).first()
        fun drafts() = originalFields.map { CustomFieldDraft(id = it.id, title = it.title, value = it.value, isProtected = it.isProtected) }
        val kinds = takagi.ru.monica.data.model.PasswordContentBlocks
        for (stored in kinds.read(drafts())) {
            val block = checkNotNull(stored.block)
            val edited = block.edited(block.title + " edited", mapOf("notes" to "Edited notes after upgrade"))
            val next = kinds.put(drafts(), edited)
            for (field in next) {
                val before = originalFields.singleOrNull { it.title == field.title }
                dao.insert(CustomField(id = before?.id ?: 0, entryId = main.id, title = field.title,
                    value = field.value, isProtected = field.isProtected, sortOrder = before?.sortOrder ?: next.indexOf(field)))
            }
            val persisted = dao.getFieldsByEntryId(main.id).first().map { CustomFieldDraft(title = it.title, value = it.value) }
            assertEquals(edited.raw, kinds.read(persisted).single { it.token == stored.token }.block!!.raw)
            // Remove only new fixture chunks; restore original fixture fields for the next phase.
            val originalIds = originalFields.map { it.id }.toSet()
            dao.getFieldsByEntryId(main.id).first().filter { it.id !in originalIds }.forEach { dao.delete(it) }
            originalFields.forEach { dao.insert(it) }
        }
        for (field in originalFields.filter { takagi.ru.monica.data.model.EmbeddedWalletContent.isMetadata(it.title) }) {
            val wallet = takagi.ru.monica.data.model.EmbeddedWalletContent
            val snapshot = (wallet.read(field.value) as takagi.ru.monica.data.model.EmbeddedWalletContent.ReadResult.Available).snapshot
            val edited = snapshot.edited(snapshot.title, "Edited embedded notes", snapshot.itemData)
            dao.update(field.copy(value = edited.encode()))
            val read = wallet.read(checkNotNull(dao.getFieldById(field.id)).value) as takagi.ru.monica.data.model.EmbeddedWalletContent.ReadResult.Available
            assertEquals(snapshot.assets, read.snapshot.assets)
            assertEquals("Edited embedded notes", read.snapshot.notes)
            dao.update(field)
        }
        f.verify()
    }

    @Test fun migrateAndVerifyEveryOldItem() = runBlocking {
        val f = LegacyVaultCorpus()
        f.verify()
        val before = f.snapshot().toString()
        withContext(Dispatchers.IO) {
            f.security.migrateProtectedDeviceCiphertexts()
            PortableLocalCipherMigration.run(f.db, f.security)
            PortableLocalCipherMigration.run(takagi.ru.monica.steam.data.SteamDatabase.getDatabase(f.context), f.security, steam = true)
            PasswordHistoryManager(f.context).exportHistoryJson()
            CommonAccountPreferences(f.context).defaultEmail.first()
            KeePassKeyFileStore(f.context).migrateDeviceEncryptedCopies()
        }
        assertEquals(before, f.snapshot().toString())
        assertEquals(0, PortableLocalCipherMigration.run(f.db, f.security))
        assertTrue(LocalVaultRecovery(f.context).available())
        f.verify()
        File(f.root, "migration-passed").writeText("old APK -> new APK; all columns, signatures and attachment bytes match")
    }

    @Test fun loseOnlyFixtureUsersKeystoreKeys() = runBlocking {
        val f = LegacyVaultCorpus()
        f.authorizeFixture()
        check(File(f.root, "migration-passed").isFile)
        f.verify()
        check(android.os.Process.myUid() / 100_000 >= 11)
        // This app UID belongs to the isolated upgrade corpus, never Owner/User 10.
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS,
            "monica_data_key_v2_compat", "monica_data_key_v2", "monica_mdk_wrap_key").forEach { alias ->
            if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        }
        SecurityManager.clearRuntimeUnlockCache()
        SessionManager.markLocked()
        assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
    }

    @Test fun recoverAllLocalDataAfterKeystoreLoss() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(android.os.Process.myUid() / 100_000 >= 11)
        check(InstrumentationRegistry.getArguments().getString("isolatedRecoveryUser") == "yes")
        val original = File(context.applicationInfo.dataDir, "shared_prefs/monica_secure_prefs.xml")
        val bytes = original.readBytes()
        val recovery = LocalVaultRecovery(context)
        assertFalse(recovery.recover("incorrect-password"))
        assertArrayEquals(bytes, original.readBytes())
        assertTrue(recovery.recover("Synthetic-upgrade-corpus-316!"))
        assertArrayEquals(bytes, original.readBytes())
        LegacyVaultCorpus().verify()
    }

    @Test fun fullLocalBackupRestoreAfterRecovery() = runBlocking {
        val f = LegacyVaultCorpus()
        f.verify()
        val helper = WebDavHelper(f.context)
        // Trash is exported by the helper's separate trash path. Do not send it
        // again as a live password. Include the archived records as the app does.
        val passwords = PasswordRepository(f.db.passwordEntryDao()).getAllLocalPasswordEntries()
        val items = f.db.secureItemDao().getAllItems().first()
        val (archive, report) = helper.createBackupZip(passwords.distinctBy { it.id }, items.distinctBy { it.id },
            BackupPreferences(), backupEncryptionPassword = "Synthetic-backup-316!").getOrThrow()
        assertTrue(report.success)
        assertTrue("No item may be silently skipped", report.failedItems.isEmpty() && report.skippedItems.isEmpty())
        val saved = File(f.root, "full-backup.enc.zip")
        archive.copyTo(saved, overwrite = true)
        assertTrue(helper.restoreFromBackupFile(saved, "wrong", restoreMonicaConfig = false, importDataOnly = true).isFailure)
        val restored = helper.restoreFromBackupFile(saved, "Synthetic-backup-316!", overwrite = true,
            restoreMonicaConfig = false, importDataOnly = false).getOrThrow()
        assertTrue(restored.report.failedItems.isEmpty())
        val originalFields = f.db.customFieldDao().getAllFieldsSync()
        File(f.root, "before-restore.json").writeText(f.snapshot().toString())
        val before = portableRows(f)
        val applied = BackupRestoreApplier.applyRestoreResult(f.context, restored,
            PasswordRepository(f.db.passwordEntryDao(), passwordHistoryDao = f.db.passwordHistoryDao()),
            SecureItemRepository(f.db.secureItemDao()), true, "SyntheticUpgradeTest")
        assertEquals(0, applied.passwordFailed + applied.secureItemFailed + applied.passkeyFailed + applied.steamAccountFailed)
        File(f.root, "after-restore.json").writeText(f.snapshot().toString())
        assertTrue("Portable values differ; inspect before-restore.json and after-restore.json", before == portableRows(f))
        assertEquals(originalFields.size, f.db.customFieldDao().getAllFieldsSync().size)
        f.verifyPayloads()
        File(f.root, "backup-restore-passed").writeText("Encrypted full backup restored through BackupRestoreApplier")
    }

    @Test fun verifyRestoredPayloadsAfterProcessRestart() = runBlocking {
        val f = LegacyVaultCorpus()
        check(File(f.root, "backup-restore-passed").exists())
        f.verifyPayloads()
    }

    /** Retry the immutable archive produced before an earlier restore regression. */
    @Test fun restoreSavedFullBackupAndCompareOriginalCorpus() = runBlocking {
        val f = LegacyVaultCorpus()
        f.authorizeFixture()
        assertTrue(f.security.unlockVaultWithPassword(f.password))
        val before = portableRows(org.json.JSONObject(File(f.root, "before-restore.json").readText()))
        val restored = WebDavHelper(f.context).restoreFromBackupFile(
            File(f.root, "full-backup.enc.zip"), "Synthetic-backup-316!", overwrite = true,
            restoreMonicaConfig = false, importDataOnly = false).getOrThrow()
        assertTrue(restored.report.failedItems.isEmpty())
        val applied = BackupRestoreApplier.applyRestoreResult(f.context, restored,
            PasswordRepository(f.db.passwordEntryDao(), passwordHistoryDao = f.db.passwordHistoryDao()),
            SecureItemRepository(f.db.secureItemDao()), true, "SyntheticUpgradeRetest")
        assertEquals(0, applied.passwordFailed + applied.secureItemFailed + applied.passkeyFailed + applied.steamAccountFailed)
        File(f.root, "after-restore.json").writeText(f.snapshot().toString())
        assertEquals(before, portableRows(f))
        f.verifyPayloads()
        File(f.root, "backup-restore-passed").writeText("Saved encrypted archive restored and all original payloads verified")
    }

    @Test fun captureRestoredCorpusForFieldAudit() = runBlocking {
        val f = LegacyVaultCorpus()
        f.authorizeFixture()
        assertTrue(f.security.unlockVaultWithPassword(f.password))
        File(f.root, "after-restore.json").writeText(f.snapshot().toString())
    }

    private fun portableRows(f: LegacyVaultCorpus): String {
        return portableRows(f.snapshot())
    }

    private fun portableRows(json: org.json.JSONObject): String {
        val passwords = json.getJSONArray("password_entries")
        val items = json.getJSONArray("secure_items")
        val pwTitles = (0 until passwords.length()).associate { passwords.getJSONObject(it).getLong("id") to passwords.getJSONObject(it).getString("title") }
        val itemTitles = (0 until items.length()).associate { items.getJSONObject(it).getLong("id") to items.getJSONObject(it).getString("title") }
        val out = sortedMapOf<String, String>()
        listOf("password_entries", "secure_items", "custom_fields", "password_history_entries", "passkeys", "attachments").forEach { table ->
            val array = json.getJSONArray(table)
            val fieldOrder = mutableMapOf<Long, Int>()
            val rows = (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                if (table == "custom_fields") {
                    val parent = row.getLong("entry_id")
                    // Backup preserves the ordered array, not arbitrary gaps/ties in sortOrder.
                    row.put("sort_order", fieldOrder.getOrDefault(parent, 0))
                    fieldOrder[parent] = fieldOrder.getOrDefault(parent, 0) + 1
                }
                when (table) {
                    "custom_fields", "password_history_entries" -> row.put("entry_id", pwTitles[row.getLong("entry_id")])
                    "attachments" -> {
                        if (!row.isNull("parent_password_id")) row.put("parent_password_id", pwTitles[row.getLong("parent_password_id")])
                        if (!row.isNull("parent_secure_item_id")) row.put("parent_secure_item_id", itemTitles[row.getLong("parent_secure_item_id")])
                        listOf("local_path", "wrapped_cek", "created_at", "updated_at", "downloaded_at").forEach(row::remove)
                    }
                    "passkeys" -> {
                        if (!row.isNull("bound_password_id")) row.put("bound_password_id", pwTitles[row.getLong("bound_password_id")])
                        row.remove("is_backed_up")
                    }
                }
                row.remove("id")
                row.toString()
            }.sorted()
            out[table] = rows.joinToString("\n")
        }
        return out.toString()
    }
}
