package takagi.ru.monica.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal object MdbxMigrationLifecycle {
    suspend fun <Target, Result> withIsolatedTarget(
        createTarget: suspend () -> Target,
        cleanupTarget: suspend (Target) -> Unit,
        migrateAndVerify: suspend (Target) -> Result
    ): Result {
        val target = createTarget()
        return try {
            migrateAndVerify(target)
        } catch (migrationError: Throwable) {
            runCatching {
                withContext(NonCancellable) { cleanupTarget(target) }
            }
                .exceptionOrNull()
                ?.let(migrationError::addSuppressed)
            throw migrationError
        }
    }
}
