package takagi.ru.monica.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import takagi.ru.monica.data.NativeApiToken
import takagi.ru.monica.data.NativeApiTokenSummary
import takagi.ru.monica.security.SessionManager

internal data class NativeApiTokenDetailState(
    val summary: NativeApiTokenSummary? = null,
    val token: NativeApiToken? = null,
    val loading: Boolean = true,
    val deleting: Boolean = false,
    val failed: Boolean = false,
    val deleted: Boolean = false,
)

internal class NativeApiTokenDetailViewModel(
    private val databases: MdbxViewModel,
    private val databaseId: Long,
    private val entryId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NativeApiTokenDetailState(
        summary = databases.cachedNativeApiTokenSummary(databaseId, entryId)
    ))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var refreshOnResume = false

    init {
        refresh()
        viewModelScope.launch {
            SessionManager.isUnlocked.drop(1).collect { unlocked ->
                if (!unlocked) { loadJob?.cancel(); mutableState.value = NativeApiTokenDetailState() }
            }
        }
    }

    fun onResume() {
        // init already starts the first read, before the navigation transition finishes.
        if (refreshOnResume) refresh()
        refreshOnResume = true
    }

    fun onStop() {
        // The editor can be opened before this destination's first resume.
        refreshOnResume = true
    }

    fun refresh() {
        if (loadJob?.isActive == true || state.value.deleting || state.value.deleted) return
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, failed = false) }
            try {
                val token = databases.readNativeApiToken(databaseId, entryId)
                mutableState.update { it.copy(summary = token.summary, token = token) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(failed = true) }
            } finally { mutableState.update { it.copy(loading = false) } }
        }
    }

    fun delete() {
        val snapshot = state.value
        val token = snapshot.token ?: return
        if (snapshot.deleting || snapshot.loading) return
        mutableState.update { it.copy(deleting = true, failed = false) }
        viewModelScope.launch {
            try {
                databases.deleteNativeApiToken(token)
                mutableState.value = NativeApiTokenDetailState(loading = false, deleted = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(failed = true) }
            } finally { mutableState.update { it.copy(deleting = false) } }
        }
    }
}
