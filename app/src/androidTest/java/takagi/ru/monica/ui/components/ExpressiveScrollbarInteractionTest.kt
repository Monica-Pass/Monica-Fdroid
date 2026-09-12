package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpressiveScrollbarInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val density get() = context.resources.displayMetrics.density

    @After fun resumeClock() { compose.mainClock.autoAdvance = true }

    private fun show(heights: List<Int> = List(120) { 80 }): LazyListState {
        val state = LazyListState()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(600.dp)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        items(heights.size, key = { it }) { index ->
                            Text("Synthetic vault entry $index", Modifier.fillMaxWidth().height(heights[index].dp))
                        }
                    }
                    ExpressiveLazyListScrollbar(
                        listState = state,
                        modifier = Modifier.align(Alignment.CenterEnd).testTag("scrollbar"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        return state
    }

    private fun settlePointerFrame() {
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }

    private fun save(name: String, result: JSONObject) {
        File(context.getExternalFilesDir("scroll-performance"), name).writeText(result.toString(2))
    }

    @Test fun slowThumbDragMovesWithinRowsInsteadOfJumpingWholeCards() {
        val state = show()
        val scrollbar = compose.onNodeWithTag("scrollbar")
        scrollbar.performTouchInput {
            down(Offset(centerX, 20f * density))
            moveBy(Offset(0f, 28f * density))
        }
        settlePointerFrame()
        val positions = mutableListOf<Int>()
        val offsets = mutableListOf<Int>()
        repeat(40) {
            scrollbar.performTouchInput { moveBy(Offset(0f, 1.5f)) }
            settlePointerFrame()
            compose.runOnIdle {
                offsets += state.firstVisibleItemScrollOffset
                positions += (state.firstVisibleItemIndex * 80f * density).toInt() + state.firstVisibleItemScrollOffset
            }
        }
        scrollbar.performTouchInput { up() }
        settlePointerFrame()
        val deltas = positions.zipWithNext { before, after -> after - before }
        val continuousFrames = deltas.count { it > 0 }
        save("thumb-drag.json", JSONObject()
            .put("positions", JSONArray(positions))
            .put("offsets", JSONArray(offsets))
            .put("advancingSamples", continuousFrames)
            .put("samples", positions.size)
            .put("largestJumpPx", deltas.maxOrNull() ?: 0))
        assertTrue("Small thumb movements should move the list every frame: $positions", continuousFrames >= 36)
        assertTrue("Scrolling must retain fractional row offsets: $offsets", offsets.count { it > 0 } >= 36)
        assertTrue("Small thumb movement jumped an entire card: $deltas", deltas.all { it in 0..(40f * density).toInt() })
    }

    @Test fun releasingAtTheTrackEndsKeepsTheLastRequestedPosition() {
        val state = show()
        val scrollbar = compose.onNodeWithTag("scrollbar")
        scrollbar.performTouchInput {
            down(Offset(centerX, 20f * density))
            moveBy(Offset(0f, 28f * density))
        }
        settlePointerFrame()
        scrollbar.performTouchInput {
            moveTo(Offset(centerX, height - 1f))
            up()
        }
        settlePointerFrame()
        compose.runOnIdle { assertFalse("Release discarded the final bottom position", state.canScrollForward) }
        scrollbar.performTouchInput {
            down(Offset(centerX, height - 20f * density))
            moveBy(Offset(0f, -28f * density))
        }
        settlePointerFrame()
        scrollbar.performTouchInput {
            moveTo(Offset(centerX, 0f))
            up()
        }
        settlePointerFrame()
        compose.runOnIdle { assertFalse("Release discarded the final top position", state.canScrollBackward) }
    }

    @Test fun mixedHeightRowsDoNotJumpBackwardAsTheThumbCrossesNewlyMeasuredCards() {
        val heights = List(160) { listOf(64, 128, 80, 180)[it % 4] }
        val starts = heights.map { (it * density).roundToInt() }.runningFold(0, Int::plus)
        val state = show(heights)
        val scrollbar = compose.onNodeWithTag("scrollbar")
        scrollbar.performTouchInput {
            down(Offset(centerX, 20f * density))
            moveBy(Offset(0f, 28f * density))
        }
        settlePointerFrame()
        val positions = mutableListOf<Int>()
        repeat(100) {
            scrollbar.performTouchInput { moveBy(Offset(0f, 0.75f)) }
            settlePointerFrame()
            compose.runOnIdle { positions += starts[state.firstVisibleItemIndex] + state.firstVisibleItemScrollOffset }
        }
        scrollbar.performTouchInput { up() }
        settlePointerFrame()
        val deltas = positions.zipWithNext { before, after -> after - before }
        save("mixed-thumb-drag.json", JSONObject().put("positions", JSONArray(positions))
            .put("smallestStepPx", deltas.minOrNull()).put("largestStepPx", deltas.maxOrNull()))
        assertTrue("Dragging down must not move mixed-height cards backward: $deltas", deltas.all { it >= 0 })
        assertTrue("Small movements must remain continuous with mixed-height cards: $deltas", deltas.all { it <= 40f * density })
    }
}
