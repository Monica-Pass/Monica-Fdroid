package takagi.ru.monica.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.NativeApiTokenSummary

/** Only changes to the vault itself invalidate metadata, not access times or UI status. */
internal data class NativeApiTokenSource(val id: Long, val path: String, val syncedAt: Long?)

internal fun LocalMdbxDatabase.nativeApiTokenSource() =
    NativeApiTokenSource(id, filePath, lastSyncedAt)

internal data class NativeApiTokenListState(
    val entries: Map<NativeApiTokenSource, List<NativeApiTokenSummary>> = emptyMap(),
    val loading: Set<NativeApiTokenSource> = emptySet(),
    val failed: Set<NativeApiTokenSource> = emptySet(),
) {
    fun rowsFor(source: NativeApiTokenSource): List<NativeApiTokenSummary> = entries[source]
        ?: entries.entries.firstOrNull { it.key.id == source.id && it.key.path == source.path }?.value.orEmpty()
}

/** Metadata only: survives navigation, coalesces reads and replaces each result atomically. */
internal class NativeApiTokenListStore(
    private val scope: CoroutineScope,
    private val read: suspend (Long) -> List<NativeApiTokenSummary>,
) {
    private val mutableState = MutableStateFlow(NativeApiTokenListState())
    val state = mutableState.asStateFlow()
    private val jobs = mutableMapOf<NativeApiTokenSource, Job>()
    private val refreshAfterRead = mutableSetOf<NativeApiTokenSource>()

    fun request(sources: List<NativeApiTokenSource>, refresh: Boolean = false) {
        scope.launch {
            for (source in sources) {
                if (jobs[source]?.isActive == true) continue
                if (!refresh && (source in state.value.entries || source in state.value.failed)) continue
                load(source)
            }
        }
    }

    fun invalidate(databaseId: Long) {
        scope.launch {
            val sources = (state.value.entries.keys + state.value.failed + state.value.loading)
                .associateBy { it.id }.values.filter { it.id == databaseId }
            for (source in sources) {
                if (jobs[source]?.isActive == true) refreshAfterRead.add(source) else load(source)
            }
        }
    }

    private fun load(source: NativeApiTokenSource) {
        // A replaced database must never inherit the previous file's cached rows.
        val staleSources = (state.value.entries.keys + jobs.keys).filter { it.id == source.id && it != source }
        val replacedFiles = staleSources.filter { it.path != source.path }.toSet()
        staleSources.forEach { jobs.remove(it)?.cancel(); refreshAfterRead.remove(it) }
        mutableState.update {
            it.copy(entries = it.entries - replacedFiles,
                loading = (it.loading - staleSources.toSet()) + source,
                failed = it.failed - staleSources.toSet() - source)
        }
        jobs[source] = scope.launch {
            try {
                do {
                    refreshAfterRead.remove(source)
                    val rows = read(source.id)
                    mutableState.update { it.copy(
                        entries = it.entries.filterKeys { key -> key.id != source.id } + (source to rows),
                        failed = it.failed - source) }
                } while (source in refreshAfterRead)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the last successful snapshot; a transient read error is not an empty vault.
                mutableState.update { it.copy(failed = it.failed + source) }
            } finally {
                mutableState.update { it.copy(loading = it.loading - source) }
            }
        }
    }
}
