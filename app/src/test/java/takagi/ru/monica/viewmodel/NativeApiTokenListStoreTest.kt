package takagi.ru.monica.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.NativeApiTokenSummary

@OptIn(ExperimentalCoroutinesApi::class)
class NativeApiTokenListStoreTest {
    private val source = NativeApiTokenSource(1, "/test/vault.mdbx", null)
    private val first = listOf(NativeApiTokenSummary(1, "entry", "folder", "Work", "gitlab"))

    @Test fun navigatingBackAndRefreshingNeverEmitsAnEmptyLoadedList() = runTest {
        var reads = 0
        val refreshed = CompletableDeferred<List<NativeApiTokenSummary>>()
        val store = NativeApiTokenListStore(this) { if (++reads == 1) first else refreshed.await() }
        store.request(listOf(source)); runCurrent()
        assertEquals(first, store.state.value.rowsFor(source))
        store.request(listOf(source)); runCurrent()
        assertEquals(1, reads)
        store.request(listOf(source), refresh = true); runCurrent()
        assertEquals(first, store.state.value.rowsFor(source))
        assertTrue(source in store.state.value.loading)
        val updated = listOf(first.single().copy(title = "github"))
        refreshed.complete(updated); runCurrent()
        assertEquals(updated, store.state.value.rowsFor(source))
        assertTrue(store.state.value.loading.isEmpty())
    }

    @Test fun transientFailureKeepsTheLastSnapshotAndCanBeRetried() = runTest {
        var fail = false
        val store = NativeApiTokenListStore(this) { if (fail) error("test read failure") else first }
        store.request(listOf(source)); runCurrent()
        fail = true; store.request(listOf(source), refresh = true); runCurrent()
        assertEquals(first, store.state.value.rowsFor(source))
        assertTrue(source in store.state.value.failed)
        fail = false; store.request(listOf(source), refresh = true); runCurrent()
        assertTrue(store.state.value.failed.isEmpty())
    }

    @Test fun accessTimesAndStatusChangesDoNotTriggerNativeReads() = runTest {
        var reads = 0
        val store = NativeApiTokenListStore(this) { reads++; first }
        val db = LocalMdbxDatabase(id = 1, name = "Work", filePath = source.path, lastAccessedAt = 1)
        store.request(listOf(db.nativeApiTokenSource())); runCurrent()
        store.request(listOf(db.copy(lastAccessedAt = 2, name = "Renamed", lastSyncStatus = "SYNCING").nativeApiTokenSource()))
        runCurrent()
        assertEquals(1, reads)
    }

    @Test fun simultaneousConsumersShareOneReadAndAMutationRequestsOneFollowUp() = runTest {
        var reads = 0
        val pending = CompletableDeferred<List<NativeApiTokenSummary>>()
        val updated = listOf(first.single().copy(title = "updated"))
        val store = NativeApiTokenListStore(this) { if (++reads == 1) pending.await() else updated }
        store.request(listOf(source)); store.request(listOf(source), refresh = true); runCurrent()
        assertEquals(1, reads)
        store.invalidate(1); store.invalidate(1); runCurrent()
        pending.complete(first); runCurrent()
        assertEquals(2, reads)
        assertEquals(updated, store.state.value.rowsFor(source))
    }

    @Test fun syncRetainsRowsButReplacingTheDatabaseFileDoesNot() = runTest {
        var reads = 0
        val pending = CompletableDeferred<List<NativeApiTokenSummary>>()
        val store = NativeApiTokenListStore(this) { if (++reads == 1) first else pending.await() }
        store.request(listOf(source)); runCurrent()
        val synced = source.copy(syncedAt = 100)
        store.request(listOf(synced)); runCurrent()
        assertEquals(first, store.state.value.rowsFor(synced))
        val replacement = source.copy(path = "/test/another.mdbx")
        store.request(listOf(replacement)); runCurrent()
        assertTrue(store.state.value.rowsFor(replacement).isEmpty())
        pending.complete(emptyList()); runCurrent()
    }
}
