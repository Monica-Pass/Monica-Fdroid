package takagi.ru.monica.viewmodel

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import takagi.ru.monica.data.ClearDataProgress
import takagi.ru.monica.data.ClearDataSelection
import takagi.ru.monica.data.ClearDataStatus

/** Activity-owned: rotation or recomposition must neither cancel nor repeat deletion. */
class ClearDataViewModel(
    private val execute: suspend (ClearDataSelection, (ClearDataProgress) -> Unit) -> Unit,
) : ViewModel() {
    private val mutableProgress = MutableStateFlow<ClearDataProgress?>(null)
    val progress = mutableProgress.asStateFlow()

    @MainThread
    fun start(selection: ClearDataSelection) {
        if (mutableProgress.value != null || selection.isEmpty) return
        mutableProgress.value = ClearDataProgress()
        viewModelScope.launch {
            try {
                execute(selection) { mutableProgress.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the last committed count; do not expose vault paths or secret payloads.
                mutableProgress.value = mutableProgress.value?.copy(status = ClearDataStatus.FAILED)
            }
        }
    }

    @MainThread
    fun dismiss() {
        if (mutableProgress.value?.isRunning == false) mutableProgress.value = null
    }
}
