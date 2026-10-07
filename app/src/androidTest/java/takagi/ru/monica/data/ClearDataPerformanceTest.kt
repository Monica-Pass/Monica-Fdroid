package takagi.ru.monica.data

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import java.util.UUID
import kotlin.system.measureTimeMillis

/** Disk-backed synthetic data, using the same repository calls as Settings. */
@RunWith(AndroidJUnit4::class)
class ClearDataPerformanceTest {
    @Test
    fun clearsThreeThousandPasswordsWithAnActiveListObserver(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "clear-data-benchmark-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
        try {
            val repository = PasswordRepository(db.passwordEntryDao())
            db.passwordEntryDao().insertPasswordEntries(List(3000) {
                PasswordEntry(title = "Synthetic $it", website = "", username = "fixture",
                    password = "synthetic-only", notes = "x".repeat(256))
            })
            val observer = launch(Dispatchers.IO) { repository.getAllPasswordEntries().collect {} }
            val progress = mutableListOf<ClearDataProgress>()
            val elapsed = measureTimeMillis {
                ClearDataUseCase(repository, SecureItemRepository(db.secureItemDao()), {})
                    .execute(ClearDataSelection(passwords = true)) { progress += it }
            }
            observer.cancelAndJoin()
            assertTrue(repository.getAllPasswordEntries().first().isEmpty())
            org.junit.Assert.assertEquals(3000, progress.last().clearedEntries)
            org.junit.Assert.assertEquals(ClearDataStatus.COMPLETED, progress.last().status)
            assertTrue("Progress must advance only in committed batches", progress.zipWithNext().all { (a, b) -> b.clearedEntries >= a.clearedEntries })
            Log.i("ClearDataBenchmark", "batched: 3000 passwords, active observer, ${elapsed}ms")
            assertTrue("Clearing 3000 local entries should not take a minute: ${elapsed}ms", elapsed < 30_000)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
