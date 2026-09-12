package takagi.ru.monica.ui.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlin.math.roundToInt

internal data class ExpressiveScrollbarTarget(val index: Int, val offset: Int)

/** A fixed coordinate system for one drag, unaffected by newly measured rows. */
internal class ExpressiveScrollbarDragAxis(
    private val starts: DoubleArray,
    private val maxScrollPx: Double,
    private val lastItemSizePx: Double,
) {
    val itemCount: Int get() = starts.size

    fun target(progress: Float, tracker: ExpressiveScrollbarAxisTracker? = null): ExpressiveScrollbarTarget {
        if (starts.isEmpty() || progress <= 0f) return ExpressiveScrollbarTarget(0, 0)
        val distance = progress.coerceIn(0f, 1f) * maxScrollPx
        // Asking for the last row also lets LazyColumn clamp against its real end padding.
        if (progress >= 1f) return ExpressiveScrollbarTarget(
            starts.lastIndex, (distance - starts.last()).coerceAtLeast(0.0).roundToInt()
        )
        var low = 0
        var high = starts.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (starts[middle] <= distance) low = middle else high = middle - 1
        }
        val estimatedStride = if (low < starts.lastIndex) starts[low + 1] - starts[low] else lastItemSizePx
        val actualStride = tracker?.measuredStride(low)?.toDouble() ?: estimatedStride
        // Keep the drag's row boundaries fixed, but fit the within-row fraction to its
        // measured height. Otherwise a short newly measured row can scroll past itself
        // and then jump backward when the next virtual row boundary is crossed.
        val fraction = (distance - starts[low]).coerceAtLeast(0.0) / estimatedStride.coerceAtLeast(1.0)
        return ExpressiveScrollbarTarget(low, (fraction * actualStride).roundToInt())
    }
}

/** Measured strides with logarithmic prefix queries, independent of how far we have scrolled. */
internal class ExpressiveScrollbarAxisTracker {
    private var trackedSpacingPx = Int.MIN_VALUE
    private var strides = FloatArray(0)
    private var sizes = FloatArray(0)
    private var measuredSums = DoubleArray(1)
    private var measuredCounts = IntArray(1)
    private var strideSum = 0.0
    private var strideCount = 0
    private var sizeSum = 0.0
    private var sizeCount = 0

    fun resetIfNeeded(totalItems: Int, spacingPx: Int) {
        if (strides.size == totalItems && trackedSpacingPx == spacingPx) return
        trackedSpacingPx = spacingPx
        strides = FloatArray(totalItems) { Float.NaN }
        sizes = FloatArray(totalItems) { Float.NaN }
        measuredSums = DoubleArray(totalItems + 1)
        measuredCounts = IntArray(totalItems + 1)
        strideSum = 0.0
        strideCount = 0
        sizeSum = 0.0
        sizeCount = 0
    }

    fun observe(layout: LazyListLayoutInfo) {
        resetIfNeeded(layout.totalItemsCount, layout.mainAxisItemSpacing)
        val visible = layout.visibleItemsInfo
        for (position in visible.indices) {
            val item = visible[position]
            if (item.index !in sizes.indices) continue
            val size = item.size.toFloat()
            val previousSize = sizes[item.index]
            if (previousSize.isNaN()) {
                sizeCount++
                sizeSum += size
            } else {
                sizeSum += size - previousSize
            }
            sizes[item.index] = size
            if (position == visible.lastIndex) continue
            val next = visible[position + 1]
            val stride = (next.offset - item.offset).toFloat()
            if (next.index != item.index + 1 || stride <= 0f) continue
            val previousStride = strides[item.index]
            if (previousStride == stride) continue
            val newMeasurement = previousStride.isNaN()
            val delta = if (newMeasurement) stride.toDouble() else (stride - previousStride).toDouble()
            strides[item.index] = stride
            strideSum += delta
            if (newMeasurement) strideCount++
            var treeIndex = item.index + 1
            while (treeIndex < measuredSums.size) {
                measuredSums[treeIndex] += delta
                if (newMeasurement) measuredCounts[treeIndex]++
                treeIndex += treeIndex and -treeIndex
            }
        }
    }

    fun distanceBefore(index: Int): Float {
        var cursor = index.coerceIn(0, strides.size)
        var sum = 0.0
        var count = 0
        while (cursor > 0) {
            sum += measuredSums[cursor]
            count += measuredCounts[cursor]
            cursor -= cursor and -cursor
        }
        return (sum + (index.coerceIn(0, strides.size) - count) * stride().toDouble()).toFloat()
    }

    fun itemSize(index: Int): Float = sizes.getOrNull(index)?.takeUnless { it.isNaN() }
        ?: if (sizeCount == 0) 1f else (sizeSum / sizeCount).toFloat().coerceAtLeast(1f)

    fun measuredStride(index: Int): Float? = strides.getOrNull(index)?.takeUnless { it.isNaN() }
        ?: sizes.getOrNull(index)?.takeUnless { it.isNaN() }?.let { size ->
            size + if (index == sizes.lastIndex) 0 else trackedSpacingPx.coerceAtLeast(0)
        }

    fun stride(): Float = if (strideCount == 0) {
        (itemSize(-1) + trackedSpacingPx.coerceAtLeast(0)).coerceAtLeast(1f)
    } else {
        (strideSum / strideCount).toFloat().coerceAtLeast(1f)
    }

    fun dragAxis(layout: LazyListLayoutInfo): ExpressiveScrollbarDragAxis {
        observe(layout)
        val average = stride().toDouble()
        var distance = 0.0
        val starts = DoubleArray(strides.size) { index ->
            val start = distance
            val measured = strides[index]
            distance += if (measured.isNaN()) average else measured.toDouble()
            start
        }
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val total = if (starts.isEmpty()) 0.0 else starts.last() + itemSize(starts.lastIndex)
        val scrollable = (total + layout.beforeContentPadding + layout.afterContentPadding - viewport).coerceAtLeast(0.0)
        return ExpressiveScrollbarDragAxis(starts, scrollable, itemSize(starts.lastIndex).toDouble())
    }
}
