package takagi.ru.monica.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class ClearDataViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun repeatedConfirmationAndDismissalCannotRestartOrHideRunningWork() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val vm = ClearDataViewModel { _, report ->
            calls++
            report(ClearDataProgress(ClearDataPhase.PASSWORDS, 500, 1000))
            finish.await()
            report(ClearDataProgress(ClearDataPhase.PASSWORDS, 1000, 1000, ClearDataStatus.COMPLETED))
        }
        val selection = ClearDataSelection(passwords = true)
        vm.start(selection)
        vm.start(selection)
        dispatcher.scheduler.runCurrent()
        vm.dismiss()
        assertEquals(500, vm.progress.value!!.clearedEntries)
        finish.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        vm.start(selection)
        assertEquals(1, calls)
        assertEquals(ClearDataStatus.COMPLETED, vm.progress.value!!.status)
        vm.dismiss()
        assertNull(vm.progress.value)
    }

    @Test fun failurePreservesCommittedCountAndAllowsExplicitRetry() = runTest(dispatcher) {
        var attempts = 0
        val vm = ClearDataViewModel { _, report ->
            attempts++
            report(ClearDataProgress(ClearDataPhase.NOTES, 500, 800))
            throw IllegalStateException("test-only failure")
        }
        vm.start(ClearDataSelection(notes = true))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ClearDataStatus.FAILED, vm.progress.value!!.status)
        assertEquals(500, vm.progress.value!!.clearedEntries)
        vm.dismiss()
        vm.start(ClearDataSelection(notes = true))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, attempts)
    }

    @Test fun emptySelectionDoesNotStartWork() {
        val vm = ClearDataViewModel { _, _ -> error("Should not execute") }
        vm.start(ClearDataSelection())
        assertNull(vm.progress.value)
    }
}
