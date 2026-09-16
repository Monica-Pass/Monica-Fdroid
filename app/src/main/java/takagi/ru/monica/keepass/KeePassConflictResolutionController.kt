package takagi.ru.monica.keepass

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class KeePassConflictResolutionState(
    val databaseId: Long,
    val databaseName: String,
    val preview: KeePassRemoteConflictPreview? = null,
    val loading: Boolean = true,
    val resolving: Boolean = false,
    val error: String? = null
)

/** Owns the review across navigation; a failed write always requires a fresh preview. */
internal class KeePassConflictResolutionController(
    private val scope: CoroutineScope,
    private val inspect: suspend (Long) -> Result<KeePassRemoteConflictPreview>,
    private val resolve: suspend (
        Long, KeePassConflictDecision, String, String, Map<String, KeePassConflictResolutionSide>
    ) -> Result<KeePassRemoteConflictResolution>,
    private val formatError: (Throwable) -> String,
    private val onResolved: suspend (Long, KeePassRemoteConflictResolution) -> Unit = { _, _ -> }
) {
    private val mutableState = MutableStateFlow<KeePassConflictResolutionState?>(null)
    val state = mutableState.asStateFlow()
    private val mutableResolutionVersion = MutableStateFlow(0L)
    val resolutionVersion = mutableResolutionVersion.asStateFlow()
    private var reviewJob: Job? = null
    private var generation = 0L

    fun open(databaseId: Long, databaseName: String) {
        if (mutableState.value?.resolving == true) return
        reviewJob?.cancel()
        mutableState.value = KeePassConflictResolutionState(databaseId, databaseName)
        refresh()
    }

    fun refresh() {
        val current = mutableState.value ?: return
        if (current.resolving) return
        reviewJob?.cancel()
        val request = ++generation
        mutableState.value = current.copy(preview = null, loading = true, error = null)
        reviewJob = scope.launch {
            val result = try {
                safely { inspect(current.databaseId) }
            } catch (cancelled: CancellationException) {
                if (request == generation) mutableState.value = failedReview(current, cancelled)
                throw cancelled
            }
            if (request != generation) return@launch
            mutableState.value = current.copy(
                preview = result.getOrNull(),
                loading = false,
                error = result.exceptionOrNull()?.let(formatError)
            )
        }
    }

    fun dismiss() {
        if (mutableState.value?.resolving == true) return
        ++generation
        reviewJob?.cancel()
        mutableState.value = null
    }

    fun submit(
        decision: KeePassConflictDecision,
        selections: Map<String, KeePassConflictResolutionSide>
    ) {
        if (decision == KeePassConflictDecision.CANCEL) {
            dismiss()
            return
        }
        val current = mutableState.value ?: return
        val preview = current.preview ?: return
        if (current.loading || current.resolving) return
        if (decision == KeePassConflictDecision.MERGE &&
            preview.snapshot.items.any { item -> item.details.any { it.id !in selections } }
        ) return

        val reviewedSelections = selections.toMap()
        mutableState.value = current.copy(resolving = true, error = null)
        reviewJob = scope.launch {
            val result = try {
                safely {
                    resolve(
                        current.databaseId,
                        decision,
                        preview.localRevision.sha256,
                        preview.remoteRevision.sha256,
                        reviewedSelections
                    )
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = failedReview(current, cancelled)
                throw cancelled
            }
            val resolved = result.getOrNull()
            if (resolved != null) {
                mutableState.value = null
                if (!resolved.cancelled) {
                    mutableResolutionVersion.value += 1
                    onResolved(current.databaseId, resolved)
                }
            } else {
                // The remote write may have succeeded before its response was lost.
                // Do not resubmit selections against an old pair of revisions.
                mutableState.value = failedReview(current, result.exceptionOrNull())
            }
        }
    }

    private fun failedReview(current: KeePassConflictResolutionState, error: Throwable?) = current.copy(
        preview = null, loading = false, resolving = false, error = error?.let(formatError)
    )

    private suspend fun <T> safely(block: suspend () -> Result<T>): Result<T> = try {
        block().also { result ->
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
