package takagi.ru.monica.ui.screens

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import takagi.ru.monica.credentialexchange.ImportDestination

/** Same in-app request flow as the existing KeePass manager; no Intent payload contains vault content. */
internal object DatabaseManagerNavigation {
    private val channel = Channel<String>(Channel.CONFLATED)
    val requests = channel.receiveAsFlow()
    fun open(database: ImportDestination) { channel.trySend(database.key) }
}
