package takagi.ru.monica.ui.common.pull

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

/** A search belongs to one uninterrupted pull that did not scroll list content. */
internal class PullSearchGestureState(
    private val canTriggerSearch: () -> Boolean,
    private val onSearchThresholdReached: () -> Unit,
    private val onSearchTriggered: () -> Unit,
) {
    var isGestureActive = false
        private set
    private var scrolledContent = false
    private var releasePending = false
    private var searchReady = false

    val canPull: Boolean get() = isGestureActive && !scrolledContent
    val canRelease: Boolean get() = (isGestureActive || releasePending) && !scrolledContent

    fun beginGesture() {
        isGestureActive = true
        scrolledContent = false
        releasePending = false
        searchReady = false
    }

    fun onScroll(contentConsumed: Boolean = false) {
        if (!isGestureActive) return
        if (contentConsumed) {
            scrolledContent = true
            searchReady = false
        }
    }

    fun updatePull(inSearchRange: Boolean) {
        val wasReady = searchReady
        searchReady = inSearchRange && canPull && canTriggerSearch()
        if (searchReady && !wasReady) {
            onSearchThresholdReached()
        }
    }

    fun endTouch() {
        // The pointer observer sees Up before nested scroll dispatches onPreFling.
        releasePending = canPull
        isGestureActive = false
    }

    fun releaseSearch() {
        val shouldOpen = canRelease && searchReady && canTriggerSearch()
        cancelGesture()
        if (shouldOpen) onSearchTriggered()
    }

    fun cancelGesture() {
        isGestureActive = false
        releasePending = false
        searchReady = false
    }
}

/** Observe touch lifetime without consuming events or competing with the child scrollable. */
internal fun Modifier.observePullSearchGesture(gesture: PullSearchGestureState): Modifier =
    pointerInput(gesture) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.beginGesture()
            var released = false
            try {
                var event: PointerEvent
                do {
                    event = awaitPointerEvent(PointerEventPass.Initial)
                    // The nested scroll connection owns the distance and content-scroll checks.
                } while (event.changes.any { it.pressed })
                // Android cancellation arrives as consumed Up changes, not a search release.
                released = event.changes.any { it.changedToUp() }
            } finally {
                if (released) gesture.endTouch() else gesture.cancelGesture()
            }
        }
    }
