package takagi.ru.monica.data.dedup

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Diagnostic benchmark of the screenshot's 854 sources -> 848 local writes; no user vault is used. */
@RunWith(AndroidJUnit4::class)
class DedupLocalPerformanceInstrumentedTest {
    @Test fun diskMergeWithAndWithoutAnActiveListObserver(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val security = SecurityManager(context)
        for (observeList in listOf(false, true)) {
            val name = "dedup-performance-${UUID.randomUUID()}.db"
            val reads = AtomicInteger()
            val db = Room.databaseBuilder(context, PasswordDatabase::class.java, name)
                .setQueryCallback({ sql, _ ->
                    if (sql.startsWith("SELECT * FROM password_entries")) reads.incrementAndGet()
                }, Executor { it.run() }).build()
            try {
                db.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(id = 1, name = "Synthetic source", filePath = "/synthetic-only.kdbx"))
                val passwords = PasswordRepository(db.passwordEntryDao())
                val entries = withContext(Dispatchers.IO) {
                    List(848) { index -> PasswordEntry(
                        title = "Synthetic $index", website = "https://fixture-$index.invalid", username = "fixture-$index",
                        password = security.encryptData("synthetic-secret-$index"), keepassDatabaseId = 1,
                    ) }
                }
                db.passwordEntryDao().insertPasswordEntries(entries + entries.take(6))
                val service = DedupMergeService(passwords, SecureItemRepository(db.secureItemDao()),
                    PasskeyRepository(db.passkeyDao()), CustomFieldRepository(db.customFieldDao()),
                    db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(), db.bitwardenVaultDao(),
                    security, AppLocaleStringResolver(context), db,
                    DedupAttachmentSupport(context, db))
                val observer = if (observeList) launch(Dispatchers.IO) { passwords.getAllPasswordEntries().collect {} } else null
                try {
                    val started = SystemClock.elapsedRealtime()
                    val plan = withContext(Dispatchers.Default) { service.buildPlan(setOf("keepass:1"), DedupMergeTarget.MonicaLocal) }
                    val scanned = SystemClock.elapsedRealtime()
                    assertEquals(854, plan.totalSourcePasswords)
                    assertEquals(848, plan.writableItems)
                    var firstProgressAt = 0L
                    reads.set(0)
                    val result = withContext(Dispatchers.IO) {
                        service.executePlan(plan) { progress ->
                            if (firstProgressAt == 0L) firstProgressAt = SystemClock.elapsedRealtime()
                            if (progress.completedItems % 200 == 0) Log.i("DedupBenchmark", "observer=$observeList committed=${progress.completedItems}/848 elapsed=${SystemClock.elapsedRealtime() - scanned}ms")
                        }
                    }
                    val finished = SystemClock.elapsedRealtime()
                    val executionReads = reads.get()
                    assertEquals(848, result.insertedPasswords)
                    assertEquals(0, result.failedItems)
                    val all = db.passwordEntryDao().getActiveEntries().first()
                    assertEquals(854, all.count { it.keepassDatabaseId == 1L })
                    assertEquals(848, all.count { it.keepassDatabaseId == null })
                    Log.i("DedupBenchmark", "RESULT observer=$observeList sources=854 inserted=848 scan=${scanned-started}ms firstProgress=${firstProgressAt-scanned}ms execution=${finished-scanned}ms passwordFullReads=$executionReads")
                    assertTrue("A merge must not requery the complete vault for nearly every entry: $executionReads reads", executionReads < 60)
                } finally { observer?.cancelAndJoin() }
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }
}
