package takagi.ru.monica.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DatabaseFilterChipRowsTest {
    @Test fun preservesOrderAndWrapsOnlyAfterTheAvailableWidthIsExceeded() {
        assertArrayEquals(intArrayOf(), databaseFilterChipRowEnds(intArrayOf(), 100, 8))
        assertArrayEquals(intArrayOf(2, 3, 5), databaseFilterChipRowEnds(intArrayOf(40, 52, 101, 1, 91), 100, 8))
        assertArrayEquals(intArrayOf(1, 2), databaseFilterChipRowEnds(intArrayOf(Int.MAX_VALUE, 1), Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun manyDatabaseNamesAreNeverDroppedOrDuplicated() {
        val random = Random(136)
        repeat(100) {
            val widths = IntArray(1_000) { random.nextInt(40, 500) }
            val available = random.nextInt(160, 600)
            val ends = databaseFilterChipRowEnds(widths, available, 8)
            var start = 0
            for (end in ends) {
                assertTrue(end > start)
                val width = (start until end).sumOf { widths[it].coerceAtMost(available).toLong() } + (end - start - 1) * 8L
                assertTrue(width <= available)
                if (end < widths.size) assertTrue(width + 8L + widths[end].coerceAtMost(available) > available)
                start = end
            }
            assertEquals(widths.size, start)
        }
    }
}
