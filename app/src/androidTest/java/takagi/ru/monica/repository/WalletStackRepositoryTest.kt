package takagi.ru.monica.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletStackRepositoryTest {
    @get:Rule val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: WalletStackRepository
    private lateinit var store: DataStore<Preferences>

    @Before fun setup() {
        file = File(temp.root, "stacks.preferences_pb")
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { file }
        repository = WalletStackRepository(store)
    }

    @After fun tearDown() = runBlocking<Unit> { scope.coroutineContext.job.cancelAndJoin() }

    @Test fun membersOrderAndBrowsedCoverSurviveReopeningTheStore() = runBlocking<Unit> {
        val id = repository.create(listOf(7, 3, 7, 9))
        repository.setCover(id, 9)
        scope.coroutineContext.job.cancelAndJoin()
        openStore()
        val stack = repository.stacks.first().single()
        assertEquals(id, stack.id)
        assertEquals(listOf(7L, 3L, 9L), stack.memberIds)
        assertEquals(9L, stack.coverId)
    }

    @Test fun addingCardsTransfersMembershipAndDissolvesOneCardRemainder() = runBlocking<Unit> {
        val first = repository.create(listOf(1, 2))
        val second = repository.create(listOf(3, 4))
        repository.addMembers(second, listOf(2, 5, 5))
        val stacks = repository.stacks.first()
        assertFalse(stacks.any { it.id == first })
        assertEquals(listOf(3L, 4L, 2L, 5L), stacks.single().memberIds)
    }

    @Test fun regroupingSelectedCardsPreservesTheUntouchedRemainder() = runBlocking<Unit> {
        val old = repository.create(listOf(1, 2, 3, 4))
        repository.create(listOf(2, 4, 5))
        val stacks = repository.stacks.first()
        assertEquals(listOf(1L, 3L), stacks.single { it.id == old }.memberIds)
        val members = stacks.flatMap { it.memberIds }
        assertEquals(members.distinct(), members)
    }

    @Test fun managingOrderRemovesMembershipWithoutAddingUnrelatedCards() = runBlocking<Unit> {
        val id = repository.create(listOf(1, 2, 3))
        repository.setCover(id, 2)
        repository.updateStack(id, listOf(3, 1, 999))
        val stack = repository.stacks.first().single()
        assertEquals(listOf(3L, 1L), stack.memberIds)
        assertEquals(3L, stack.coverId)
        repository.updateStack(id, listOf(1))
        assertTrue(repository.stacks.first().isEmpty())
    }

    @Test fun coverOutsideGroupIsIgnoredAndDissolveRemovesOnlyItsGroup() = runBlocking<Unit> {
        val first = repository.create(listOf(1, 2))
        val second = repository.create(listOf(3, 4))
        repository.setCover(first, 3)
        assertEquals(1L, repository.stacks.first().single { it.id == first }.coverId)
        repository.dissolve(first)
        assertEquals(second, repository.stacks.first().single().id)
    }

    @Test fun concurrentOverlappingGroupingWritesKeepMembershipUnique() = runBlocking<Unit> {
        (1L..8L).map { id -> async { repository.create(listOf(id, id + 1, id + 2)) } }.awaitAll()
        val stacks = repository.stacks.first()
        val members = stacks.flatMap { it.memberIds }
        assertEquals(members.size, members.distinct().size)
        assertTrue(stacks.all { it.memberIds.size >= 2 && it.coverId in it.memberIds })
    }

    @Test fun stacksSavedWithNamesKeepTheirMembershipAndCover() = runBlocking<Unit> {
        store.edit {
            it[stringPreferencesKey("stacks_v1")] =
                """[{"id":"legacy","name":"Travel","memberIds":[7,3,9],"coverId":9}]"""
        }
        val old = repository.stacks.first().single()
        assertEquals("legacy", old.id)
        assertEquals(listOf(7L, 3L, 9L), old.memberIds)
        assertEquals(9L, old.coverId)
        repository.updateStack(old.id, listOf(9, 7))
        scope.coroutineContext.job.cancelAndJoin()
        openStore()
        assertEquals(listOf(9L, 7L), repository.stacks.first().single().memberIds)
        assertEquals(9L, repository.stacks.first().single().coverId)
    }
}
