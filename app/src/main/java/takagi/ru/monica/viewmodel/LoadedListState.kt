package takagi.ru.monica.viewmodel

data class LoadedListState<out T>(
    val items: List<T> = emptyList(),
    val isReady: Boolean = false,
)
