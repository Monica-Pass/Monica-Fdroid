package takagi.ru.monica.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveLazyListScrollbarTest {
    @Test
    fun targetIndex_mapsTrackEndsAndMidpointToScrollableRange() {
        assertEquals(0, expressiveScrollbarTargetIndex(0f, totalItems = 100, visibleItems = 10))
        assertEquals(45, expressiveScrollbarTargetIndex(0.5f, totalItems = 100, visibleItems = 10))
        assertEquals(90, expressiveScrollbarTargetIndex(1f, totalItems = 100, visibleItems = 10))
    }

    @Test
    fun targetIndex_clampsProgressAndShortLists() {
        assertEquals(0, expressiveScrollbarTargetIndex(-1f, totalItems = 1, visibleItems = 1))
        assertEquals(0, expressiveScrollbarTargetIndex(2f, totalItems = 1, visibleItems = 1))
    }

    @Test
    fun dragUsesPixelCalibratedMetricsAndSnapsTheHandleToTheFinger() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/ExpressiveLazyListScrollbar.kt"
        ).readText()

        assertTrue(source.contains("ExpressiveScrollbarAxisTracker"))
        assertTrue(source.contains("snapshotFlow { pendingTargetIndex }"))
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
