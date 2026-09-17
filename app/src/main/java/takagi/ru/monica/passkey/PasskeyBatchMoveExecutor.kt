package takagi.ru.monica.passkey

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import takagi.ru.monica.data.PasskeyEntry
import kotlin.coroutines.coroutineContext

/** Source deletion is permitted only after the entire destination batch is durable. */
internal object PasskeyBatchMoveExecutor {
    suspend fun execute(
        entries: List<PasskeyEntry>,
        persistTarget: suspend (List<Long>) -> Result<Unit>,
        deleteSource: suspend (PasskeyEntry) -> Result<Unit>,
        onCompleted: (PasskeyEntry, Result<Unit>) -> Unit,
    ) {
        if (entries.isEmpty()) return
        coroutineContext.ensureActive()
        val persisted = runCatching { persistTarget(entries.map { it.id }).getOrThrow() }
        (persisted.exceptionOrNull() as? CancellationException)?.let { throw it }
        for (entry in entries) {
            coroutineContext.ensureActive()
            val result = if (persisted.isFailure) persisted else runCatching { deleteSource(entry).getOrThrow() }
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            onCompleted(entry, result)
        }
    }
}
