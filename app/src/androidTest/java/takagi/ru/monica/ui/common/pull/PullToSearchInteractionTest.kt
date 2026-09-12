package takagi.ru.monica.ui.common.pull

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.repository.BitwardenRepository

@RunWith(AndroidJUnit4::class)
class PullToSearchInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var expanded by mutableStateOf(false)

    @After
    fun resumeClockForDisposal() {
        compose.mainClock.autoAdvance = true
    }

    private fun show(
        initialIndex: Int = 0,
        empty: Boolean = false,
        bitwarden: Boolean = false,
        observeTouches: Boolean = true,
    ) {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                val pull = if (bitwarden) {
                    val context = LocalContext.current
                    val repository = remember { BitwardenRepository.getInstance(context) }
                    val action = rememberPullActionState(
                        isBitwardenDatabaseView = true,
                        isSearchExpanded = expanded,
                        searchTriggerDistance = with(density) { 48.dp.toPx() },
                        syncTriggerDistance = with(density) { 72.dp.toPx() },
                        maxDragDistance = with(density) { 100.dp.toPx() },
                        bitwardenRepository = repository,
                        // An unavailable test vault keeps this interaction test offline.
                        bitwardenVaultId = Long.MIN_VALUE,
                        onSearchTriggered = { expanded = true },
                    )
                    PullToSearchStateHandle(
                        currentOffset = action.currentOffset,
                        nestedScrollConnection = action.nestedScrollConnection,
                        gestureModifier = action.gestureModifier,
                        onVerticalDrag = action.onVerticalDrag,
                        onDragEnd = action.onDragEnd,
                        onDragCancel = action.onDragCancel,
                    )
                } else {
                    rememberPullToSearchState(
                        isSearchExpanded = expanded,
                        searchTriggerDistance = with(density) { PullSearchDefaults.TriggerDistance.toPx() },
                        maxDragDistance = with(density) { 100.dp.toPx() },
                        onSearchTriggered = { expanded = true },
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    Text(if (expanded) "Search open" else "Search closed")
                    val listModifier = Modifier.fillMaxSize()
                        .offset { IntOffset(0, pull.currentOffset.toInt()) }
                        .then(if (observeTouches) pull.gestureModifier else Modifier)
                        .testTag("search-list")
                    if (empty) {
                        Box(listModifier.pointerInput(expanded) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, amount -> pull.onVerticalDrag(amount) },
                                onDragEnd = pull.onDragEnd,
                                onDragCancel = pull.onDragCancel,
                            )
                        })
                    } else {
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                        // Keep platform stretch rendering out of the gesture timing checks.
                        LazyColumn(modifier = listModifier, state = listState, overscrollEffect = null) {
                            items(40, key = { it }) { index ->
                                Text("Item $index", Modifier.fillMaxWidth().height(72.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun pullDown(distanceFraction: Float = 0.6f) {
        compose.onNodeWithTag("search-list").performTouchInput {
            down(Offset(centerX, height * 0.15f))
            repeat(12) { step ->
                moveTo(
                    Offset(centerX, height * (0.15f + distanceFraction * (step + 1) / 12f)),
                    delayMillis = 16,
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun release() {
        compose.onNodeWithTag("search-list").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(64)
    }

    @Test
    fun quickPullAtTheTopOpensSearchOnReleaseWithoutWaiting() {
        show()
        pullDown()
        compose.runOnIdle { assertFalse(expanded) }
        release()
        compose.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun holdingAPullWaitsForReleaseSoDeeperPullActionsRemainAvailable() {
        show()
        pullDown()
        compose.mainClock.advanceTimeBy(1_800)
        compose.runOnIdle { assertFalse(expanded) }
        release()
        compose.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun scrollingBackToTheTopDoesNotSearchButTheNextPullDoes() {
        show(initialIndex = 1)
        pullDown()
        release()
        compose.runOnIdle { assertFalse(expanded) }
        compose.mainClock.advanceTimeBy(300)
        pullDown()
        release()
        compose.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun aCancelledTouchDoesNotOpenSearchLater() {
        show()
        pullDown()
        compose.onNodeWithTag("search-list").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertFalse(expanded) }
    }

    @Test
    fun anEmptyListAlsoOpensSearchOnAQuickPullRelease() {
        show(empty = true)
        pullDown()
        compose.runOnIdle { assertFalse(expanded) }
        release()
        compose.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun emptyContentCanUseTheDragCallbacksWithoutNestedScroll() {
        show(empty = true, observeTouches = false)
        pullDown()
        compose.runOnIdle { assertFalse(expanded) }
        release()
        compose.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun aShortPullDoesNotOpenSearch() {
        show()
        pullDown(distanceFraction = 0.08f)
        release()
        compose.runOnIdle { assertFalse(expanded) }
    }

    @Test
    fun anOrdinaryTopSwipeDoesNotOpenSearchButADeliberatePullDoes() {
        show()
        pullDown(distanceFraction = 0.25f)
        release()
        compose.runOnIdle { assertFalse(expanded) }
        compose.mainClock.advanceTimeBy(300)
        pullDown()
        release()
        compose.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun retreatingBeforeReleaseDoesNotOpenSearch() {
        show()
        pullDown()
        compose.onNodeWithTag("search-list").performTouchInput {
            moveBy(Offset(0f, -height * 0.6f), delayMillis = 200)
        }
        release()
        compose.runOnIdle { assertFalse(expanded) }
    }

    @Test
    fun aQuickPullInABitwardenViewOpensSearchWithoutWaiting() {
        show(bitwarden = true)
        pullDown()
        compose.runOnIdle { assertFalse(expanded) }
        release()
        compose.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun aDeeperBitwardenPullDoesNotOpenSearchBeforeRelease() {
        show(bitwarden = true)
        pullDown()
        compose.mainClock.advanceTimeBy(1_800)
        compose.runOnIdle { assertFalse(expanded) }
        release()
        compose.runOnIdle { assertTrue(expanded) }
    }
}
