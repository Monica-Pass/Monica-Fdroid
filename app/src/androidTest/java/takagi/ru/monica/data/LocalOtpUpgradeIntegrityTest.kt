package takagi.ru.monica.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.model.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator

@RunWith(AndroidJUnit4::class)
class LocalOtpUpgradeIntegrityTest {
    @Test fun schema77UpgradeAndSecondOpenRetainEveryLocalOtpAndPasswordField() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val security = SecurityManager(context)
        val template = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val name = "otp-upgrade-${java.util.UUID.randomUUID()}.db"
        try {
            val samples = OtpType.entries.mapIndexed { index, type ->
                val data = TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = "Upgrade", accountName = "$type",
                    otpType = type, counter = 19, pin = "1234")
                val payload = Json.encodeToString(data)
                SecureItem(id = index + 1L, itemType = ItemType.TOTP, title = "$type",
                    itemData = if (index % 2 == 0) payload else security.encryptDataLegacyCompat(payload))
            } + SecureItem(id = 99, itemType = ItemType.TOTP, title = "trash", itemData = "OLD", isDeleted = true)
            samples.forEach { template.secureItemDao().insertItem(it) }
            val password = PasswordEntry(id = 55, title = "bound", username = "alice", website = "", password = "ciphertext-unchanged",
                authenticatorKey = security.encryptDataLegacyCompat("JBSWY3DPEHPK3PXP"))
            template.passwordEntryDao().insert(password)
            template.customFieldDao().insert(CustomField(entryId = 55, title = "Recovery", value = "synthetic recovery"))
            val fields = template.customFieldDao().getFieldsByEntryIdSync(55)
            val source = template.openHelper.writableDatabase
            val schema = mutableListOf<String>()
            source.query("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL AND type IN ('table', 'index') ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END").use { c ->
                while (c.moveToNext()) {
                    val table = c.getString(0)
                    if (table in setOf("android_metadata", "room_master_table", "sqlite_sequence") || table.endsWith("key_file_fingerprint")) continue
                    var sql = c.getString(1)
                    if (table == "local_keepass_databases") listOf("key_file_internal_path", "key_file_name", "key_file_fingerprint").forEach {
                        sql = sql.replace(", `$it` TEXT", "")
                    }
                    schema += sql
                }
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(77) {
                    override fun onCreate(db: SupportSQLiteDatabase) { schema.forEach(db::execSQL) }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
            try {
                val old = helper.writableDatabase
                for (table in listOf("password_entries", "secure_items", "custom_fields")) source.query("SELECT * FROM $table").use { c ->
                    val sql = "INSERT INTO $table (${c.columnNames.joinToString { "`$it`" }}) VALUES (${c.columnNames.joinToString { "?" }})"
                    while (c.moveToNext()) {
                        val args = Array<Any?>(c.columnCount) { i -> when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                            else -> c.getString(i)
                        } }
                        old.execSQL(sql, args)
                    }
                }
                assertEquals(77, old.version)
            } finally { helper.close() }
            repeat(2) {
                val upgraded = Room.databaseBuilder(context, PasswordDatabase::class.java, name)
                    .addMigrations(PasswordDatabase.MIGRATION_77_78).build()
                try {
                    assertEquals(samples, upgraded.secureItemDao().getItemsByIds(samples.map { it.id }).sortedBy { it.id })
                    assertEquals(password, upgraded.passwordEntryDao().getPasswordEntryById(55))
                    assertEquals(fields, upgraded.customFieldDao().getFieldsByEntryIdSync(55))
                    assertEquals(5, upgraded.secureItemDao().getActiveLocalItemsByTypeSync(ItemType.TOTP).size)
                    for (item in samples.filterNot { it.isDeleted }) {
                        val parsed = requireNotNull(TotpDataResolver.parseStoredItemData(item.itemData,
                            decryptIfNeeded = security::decryptDataIfMonicaCiphertext))
                        assertTrue(TotpGenerator.generateOtp(parsed, currentSeconds = 1_700_000_000).isNotEmpty())
                    }
                    assertEquals(78, upgraded.openHelper.writableDatabase.version)
                } finally { upgraded.close() }
            }
        } finally { template.close(); context.deleteDatabase(name) }
    }
}
