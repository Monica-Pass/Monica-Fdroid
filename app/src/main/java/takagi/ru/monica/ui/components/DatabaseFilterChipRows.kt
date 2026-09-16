package takagi.ru.monica.ui.components

/** Exclusive row ends, preserving provider and database order. Widths are already screen-clamped. */
internal fun databaseFilterChipRowEnds(widths: IntArray, availableWidth: Int, spacing: Int): IntArray {
    require(availableWidth > 0 && spacing >= 0)
    if (widths.isEmpty()) return IntArray(0)
    val ends = IntArray(widths.size)
    var rowCount = 0
    var rowWidth = 0L
    for (index in widths.indices) {
        require(widths[index] >= 0)
        val width = widths[index].coerceAtMost(availableWidth).toLong()
        if (index > 0 && rowWidth + spacing.toLong() + width > availableWidth) {
            ends[rowCount++] = index
            rowWidth = width
        } else {
            rowWidth += (if (index == 0) 0L else spacing.toLong()) + width
        }
    }
    ends[rowCount++] = widths.size
    return ends.copyOf(rowCount)
}
