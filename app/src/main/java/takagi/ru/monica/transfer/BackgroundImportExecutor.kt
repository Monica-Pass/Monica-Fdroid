package takagi.ru.monica.transfer

import android.content.Context
import takagi.ru.monica.R
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Evaluates arguments on the page, then transfers ownership to the service before suspending. */
class BackgroundImportExecutor(context: Context, private val destinationKey: String) {
    private val context = context.applicationContext
    private suspend fun run(block: suspend () -> Result<Int>): Result<Int> =
        DatabaseExportJobs.await(context, destinationKey, DataTaskKind.IMPORT,
            describe = { AppLocaleStringResolver(context).get(R.string.import_data_success_normal, it) }) {
            block()
        }

    suspend fun <A> run(operation: suspend (A) -> Result<Int>, a: A) = run { operation(a) }
    suspend fun <A, B> run(operation: suspend (A, B) -> Result<Int>, a: A, b: B) = run { operation(a, b) }
    suspend fun <A, B, C> run(operation: suspend (A, B, C) -> Result<Int>, a: A, b: B, c: C) =
        run { operation(a, b, c) }
}
