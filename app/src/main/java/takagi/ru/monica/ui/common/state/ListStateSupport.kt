package takagi.ru.monica.ui.common.state

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable

internal enum class InitialListRenderState {
    Loading,
    Empty,
    Content,
}

internal fun resolveInitialListRenderState(
    isReady: Boolean,
    itemCount: Int,
): InitialListRenderState = when {
    itemCount > 0 -> InitialListRenderState.Content
    !isReady -> InitialListRenderState.Loading
    else -> InitialListRenderState.Empty
}

@Composable
internal fun rememberSaveableLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0
): LazyListState {
    return rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = initialFirstVisibleItemIndex,
            firstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberSaveableLazyStaggeredGridState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyStaggeredGridState {
    val initialState = rememberLazyStaggeredGridState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
    )
    return rememberSaveable(saver = LazyStaggeredGridState.Saver) {
        initialState
    }
}
