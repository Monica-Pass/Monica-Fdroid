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
class MdbxEngineMigrationTest {
    @Test
    fun migration73To74AddsLegacyEngineDefault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(73) {
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
            database.execSQL(
                """
                CREATE TABLE local_mdbx_databases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL("INSERT INTO local_mdbx_databases(name) VALUES ('Legacy')")

            PasswordDatabase.MIGRATION_73_74.migrate(database)

            val columns = database.query("PRAGMA table_info(local_mdbx_databases)").use { cursor ->
                buildMap {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    while (cursor.moveToNext()) {
                        put(cursor.getString(nameIndex), cursor.getString(defaultIndex))
                    }
                }
            }
            assertTrue("engine_type" in columns)
            assertEquals("'KOTLIN_MDBX1'", columns["engine_type"])
            database.query("SELECT engine_type FROM local_mdbx_databases").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("KOTLIN_MDBX1", cursor.getString(0))
            }
        } finally {
            helper.close()
        }
    }
}
