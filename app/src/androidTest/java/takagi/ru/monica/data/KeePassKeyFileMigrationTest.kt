package takagi.ru.monica.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeePassKeyFileMigrationTest {
    @Test
    fun migration77To78AddsPrivateKeyFileMetadataAndIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(77) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }
            )
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val database = helper.writableDatabase
        try {
            database.execSQL(
                """
                CREATE TABLE local_keepass_databases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )

            PasswordDatabase.MIGRATION_77_78.migrate(database)

            val columns = buildSet {
                database.query("PRAGMA table_info(local_keepass_databases)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("key_file_internal_path" in columns)
            assertTrue("key_file_name" in columns)
            assertTrue("key_file_fingerprint" in columns)

            val indices = buildSet {
                database.query("PRAGMA index_list(local_keepass_databases)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("index_local_keepass_databases_key_file_fingerprint" in indices)
        } finally {
            helper.close()
        }
    }
}
