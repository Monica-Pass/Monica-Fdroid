package takagi.ru.monica.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentOwnerMigrationTest {
    @Test
    fun migration76To77PreservesPasswordRowsAndAddsSecureItemCascade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(76) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                }
            )
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = helper.writableDatabase
        try {
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL("CREATE TABLE password_entries (id INTEGER PRIMARY KEY NOT NULL)")
            database.execSQL("CREATE TABLE secure_items (id INTEGER PRIMARY KEY NOT NULL)")
            database.execSQL("INSERT INTO password_entries(id) VALUES (11)")
            database.execSQL("INSERT INTO secure_items(id) VALUES (22)")
            database.execSQL(
                """
                CREATE TABLE attachments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    parent_password_id INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    sha256_hex TEXT,
                    wrapped_cek TEXT,
                    local_path TEXT,
                    bitwarden_attachment_id TEXT,
                    bitwarden_url TEXT,
                    bitwarden_file_key_enc TEXT,
                    keepass_binary_ref TEXT,
                    download_state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    is_deleted INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER,
                    FOREIGN KEY(parent_password_id) REFERENCES password_entries(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO attachments (
                    id, parent_password_id, source, file_name, mime_type, size_bytes,
                    sha256_hex, wrapped_cek, local_path, download_state,
                    created_at, updated_at, is_deleted
                ) VALUES (5, 11, 'LOCAL', 'legacy.pdf', 'application/pdf', 321,
                    'hash', 'wrapped', 'blob.enc', 'DOWNLOADED', 100, 200, 0)
                """.trimIndent()
            )

            PasswordDatabase.MIGRATION_76_77.migrate(database)

            database.query(
                """
                SELECT parent_password_id, parent_secure_item_id, file_name, size_bytes,
                       sha256_hex, wrapped_cek, local_path
                FROM attachments WHERE id = 5
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(11L, cursor.getLong(0))
                assertTrue(cursor.isNull(1))
                assertEquals("legacy.pdf", cursor.getString(2))
                assertEquals(321L, cursor.getLong(3))
                assertEquals("hash", cursor.getString(4))
                assertEquals("wrapped", cursor.getString(5))
                assertEquals("blob.enc", cursor.getString(6))
            }

            database.execSQL(
                """
                INSERT INTO attachments (
                    parent_password_id, parent_secure_item_id, source, file_name, mime_type,
                    size_bytes, download_state, created_at, updated_at, is_deleted
                ) VALUES (NULL, 22, 'LOCAL', 'secure.txt', 'text/plain', 1,
                    'DOWNLOADED', 1, 1, 0)
                """.trimIndent()
            )
            database.execSQL("DELETE FROM secure_items WHERE id = 22")
            database.query("SELECT COUNT(*) FROM attachments WHERE parent_secure_item_id = 22").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        } finally {
            helper.close()
        }
    }
}
