package takagi.ru.monica.transfer

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only chooses a page. MainActivity still requires the normal vault authentication. */
object DataTaskNavigation {
    const val EXTRA = "data_task_result"
    private val requested = MutableStateFlow<DataTaskKind?>(null)
    val pending = requested.asStateFlow()
    fun receive(context: Context, intent: Intent?) {
        if (intent?.hasExtra(EXTRA) != true) return
        DatabaseExportJobs.recoverInterrupted(context.applicationContext)
        requested.value = DatabaseExportJobs.state.value?.kind
        intent.removeExtra(EXTRA)
    }
    fun consumed() { requested.value = null }
}
