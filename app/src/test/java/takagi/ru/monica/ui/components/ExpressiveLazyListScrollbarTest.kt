package takagi.ru.monica.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveLazyListScrollbarTest {
    @Test
    fun dragAxis_keepsPixelOffsetsBetweenRowBoundaries() {
        val axis = ExpressiveScrollbarAxisTracker().dragAxis(
            layoutInfo(10, 0, listOf(100, 100, 100), spacingPx = 0, viewportPx = 300)
        )
        assertEquals(ExpressiveScrollbarTarget(3, 50), axis.target(0.5f))
        assertEquals(ExpressiveScrollbarTarget(3, 51), axis.target(0.501f))
        assertEquals(ExpressiveScrollbarTarget(3, 57), axis.target(0.51f))
    }

    @Test
    fun dragAxis_handlesDifferentHeightsSpacingAndTrackEnds() {
        val axis = ExpressiveScrollbarAxisTracker().dragAxis(
            layoutInfo(5, 0, listOf(100, 200, 80, 300, 120), spacingPx = 10, viewportPx = 250)
        )
        assertEquals(ExpressiveScrollbarTarget(1, 185), axis.target(0.5f))
        assertEquals(ExpressiveScrollbarTarget(2, 34), axis.target(0.6f))
        assertEquals(ExpressiveScrollbarTarget(0, 0), axis.target(-1f))
        assertEquals(ExpressiveScrollbarTarget(4, 0), axis.target(1f))
        assertEquals(axis.target(1f), axis.target(2f))
    }

    @Test
    fun dragAxis_scrollsWithinASingleOversizedRowAndHandlesEmptyLists() {
        val tracker = ExpressiveScrollbarAxisTracker()
        val axis = tracker.dragAxis(layoutInfo(1, 0, listOf(2_000), viewportPx = 500))
        assertEquals(ExpressiveScrollbarTarget(0, 750), axis.target(0.5f))
        assertEquals(ExpressiveScrollbarTarget(0, 1_500), axis.target(1f))
        val empty = tracker.dragAxis(layoutInfo(0, 0, emptyList()))
        assertEquals(ExpressiveScrollbarTarget(0, 0), empty.target(1f))
    }

    @Test
    fun dragAxis_doesNotShiftWhenNewRowsAreMeasuredDuringTheGesture() {
        val tracker = ExpressiveScrollbarAxisTracker()
        val axis = tracker.dragAxis(layoutInfo(100, 0, listOf(100, 100, 100), spacingPx = 0, viewportPx = 300))
        val target = axis.target(0.5f)
        tracker.observe(layoutInfo(100, 50, listOf(350, 350, 350), spacingPx = 0, viewportPx = 300))
        assertEquals(target, axis.target(0.5f))
    }

    @Test
    fun dragAxis_fitsOffsetsToNewlyMeasuredHeightsWithoutCrossingBackOverARowBoundary() {
        val tracker = ExpressiveScrollbarAxisTracker()
        val axis = tracker.dragAxis(layoutInfo(10, 0, listOf(100, 100, 100), spacingPx = 0, viewportPx = 100))
        tracker.observe(layoutInfo(10, 4, listOf(50, 200, 100), spacingPx = 0, viewportPx = 100))
        assertEquals(ExpressiveScrollbarTarget(4, 25), axis.target(0.5f, tracker))
        val actualStarts = listOf(100, 100, 100, 100, 50, 200, 100, 100, 100, 100).runningFold(0, Int::plus)
        val positions = (400..650).map { distance ->
            val target = axis.target(distance / 900f, tracker)
            actualStarts[target.index] + target.offset
        }
        assertTrue(positions.zipWithNext { before, after -> after >= before }.all { it })
    }

    @Test
    fun dragUsesPixelCalibratedMetricsAndSnapsTheHandleToTheFinger() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/ExpressiveLazyListScrollbar.kt"
        ).readText()

        assertTrue(source.contains("ExpressiveScrollbarAxisTracker"))
        assertTrue(source.contains("requestScrollToItem(target.index, target.offset)"))
        assertTrue(source.contains("withFrameNanos"))
        assertTrue(source.contains("displayedProgress.snapTo(dragProgress)"))
    }

    @Test
    fun tracker_observeIsIdempotentForTheSameLayout() {
        val tracker = ExpressiveScrollbarAxisTracker()
        val layout = layoutInfo(totalItems = 100, firstIndex = 10, sizes = listOf(200, 200, 320, 200))

        tracker.observe(layout)
        val strideAfterFirst = tracker.stride()
        val distanceAfterFirst = tracker.distanceBefore(90)

        repeat(8) { tracker.observe(layout) }

        assertEquals(strideAfterFirst, tracker.stride(), 0.001f)
        assertEquals(distanceAfterFirst, tracker.distanceBefore(90), 0.001f)
    }

    @Test
    fun tracker_strideStaysStableWhileTallAndShortCardsAlternate() {
        val tracker = ExpressiveScrollbarAxisTracker()
        // A fling through the vault alternates plain rows with taller TOTP rows. A history
        // dependent estimate swings with whichever mix is on screen and the handle twitches.
        val shortWindow = layoutInfo(totalItems = 100, firstIndex = 20, sizes = listOf(200, 200, 200, 200))
        val tallWindow = layoutInfo(totalItems = 100, firstIndex = 40, sizes = listOf(320, 320, 320, 320))

        tracker.observe(shortWindow)
        tracker.observe(tallWindow)
        val strideAfterBoth = tracker.stride()

        repeat(6) {
            tracker.observe(shortWindow)
            tracker.observe(tallWindow)
        }

        assertEquals(strideAfterBoth, tracker.stride(), 0.001f)
    }

    @Test
    fun tracker_distanceBeforeIncreasesMonotonically() {
        val tracker = ExpressiveScrollbarAxisTracker()
        tracker.observe(layoutInfo(totalItems = 60, firstIndex = 5, sizes = listOf(200, 340, 200, 260)))

        var previous = -1f
        for (index in 0..59) {
            val distance = tracker.distanceBefore(index)
            assertTrue("distanceBefore($index) = $distance not > $previous", distance > previous)
            previous = distance
        }
    }

    @Test
    fun tracker_resetsWhenItemCountChanges() {
        val tracker = ExpressiveScrollbarAxisTracker()
        tracker.observe(layoutInfo(totalItems = 100, firstIndex = 0, sizes = listOf(400, 400, 400)))
        val tallStride = tracker.stride()

        tracker.observe(layoutInfo(totalItems = 40, firstIndex = 0, sizes = listOf(120, 120, 120)))

        assertTrue("stale stride $tallStride should not survive a list change", tracker.stride() < tallStride)
    }

    @Test
    fun tracker_updatesMeasuredPrefixesAndResetsChangedSpacing() {
        val tracker = ExpressiveScrollbarAxisTracker()
        tracker.observe(layoutInfo(10, 0, listOf(100, 200, 300), spacingPx = 10))
        assertEquals(110f, tracker.distanceBefore(1), 0.001f)
        assertEquals(320f, tracker.distanceBefore(2), 0.001f)
        tracker.observe(layoutInfo(10, 0, listOf(140, 160, 300), spacingPx = 10))
        assertEquals(150f, tracker.distanceBefore(1), 0.001f)
        assertEquals(320f, tracker.distanceBefore(2), 0.001f)
        tracker.observe(layoutInfo(10, 0, listOf(140, 160, 300), spacingPx = 20))
        assertEquals(160f, tracker.distanceBefore(1), 0.001f)
        assertEquals(340f, tracker.distanceBefore(2), 0.001f)
        assertEquals(0f, tracker.distanceBefore(-1), 0.001f)
    }

    private fun layoutInfo(
        totalItems: Int,
        firstIndex: Int,
        sizes: List<Int>,
        spacingPx: Int = 8,
        viewportPx: Int = 1_600,
    ): LazyListLayoutInfo {
        var offset = 0
        val items = sizes.mapIndexed { position, size ->
            val info = FakeItemInfo(index = firstIndex + position, offset = offset, size = size)
            offset += size + spacingPx
            info
        }
        return FakeLayoutInfo(
            visibleItemsInfo = items,
            totalItemsCount = totalItems,
            viewportEndOffset = viewportPx,
            mainAxisItemSpacing = spacingPx,
        )
    }

    private class FakeItemInfo(
        override val index: Int,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo {
        override val key: Any get() = index
        override val contentType: Any? get() = null
    }

    private class FakeLayoutInfo(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val totalItemsCount: Int,
        override val viewportEndOffset: Int,
        override val mainAxisItemSpacing: Int,
    ) : LazyListLayoutInfo {
        override val viewportStartOffset: Int get() = 0
        override val viewportSize: IntSize get() = IntSize(1_000, viewportEndOffset)
        override val orientation: Orientation get() = Orientation.Vertical
        override val reverseLayout: Boolean get() = false
        override val beforeContentPadding: Int get() = 0
        override val afterContentPadding: Int get() = 0
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
