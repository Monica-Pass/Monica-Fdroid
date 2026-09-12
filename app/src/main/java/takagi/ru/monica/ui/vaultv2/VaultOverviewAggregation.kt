package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.rustcore.RustVaultOverviewCore

internal const val OVERVIEW_HEADER = 6
internal const val OVERVIEW_ROW_WIDTH = 8
internal const val OVERVIEW_PREVIEW_LIMIT = 8
// Device measurements include JNI and result validation; smaller batches stay cheaper in Kotlin.
internal const val OVERVIEW_NATIVE_THRESHOLD = 1024

internal data class VaultOverviewAggregation(
    val visible: IntArray,
    val favorites: IntArray,
    val cards: IntArray,
    val items: IntArray,
    val typeCounts: IntArray,
    val sourceCounts: IntArray,
    val folderCounts: IntArray,
)

internal fun aggregateVaultOverview(
    metadata: LongArray,
    nativeProject: (LongArray) -> IntArray? = RustVaultOverviewCore::project,
): VaultOverviewAggregation {
    val rowCount = (metadata.size - OVERVIEW_HEADER) / OVERVIEW_ROW_WIDTH
    if (rowCount >= OVERVIEW_NATIVE_THRESHOLD) {
        nativeProject(metadata)?.let { decodeVaultOverviewAggregation(it, metadata) }?.let { return it }
    }
    return aggregateVaultOverviewKotlin(metadata)
}

/** Input is produced by the snapshot builder. Keep the same stable Top-K ordering as Rust. */
internal fun aggregateVaultOverviewKotlin(metadata: LongArray): VaultOverviewAggregation {
    val rowCount = (metadata.size - OVERVIEW_HEADER) / OVERVIEW_ROW_WIDTH
    val types = IntArray(metadata[5].toInt())
    val sources = IntArray(metadata[1].toInt())
    val folders = IntArray(metadata[2].toInt())
    val selected = metadata[3].toInt()
    val recommendations = metadata[4].toInt()
    val visible = IntArray(rowCount)
    val favorites = IntArray(rowCount)
    val cards = IntArray(OVERVIEW_PREVIEW_LIMIT)
    val items = IntArray(OVERVIEW_PREVIEW_LIMIT)
    var visibleCount = 0
    var favoriteCount = 0
    var cardCount = 0
    var itemCount = 0
    fun comesBefore(left: Int, right: Int): Boolean {
        val a = OVERVIEW_HEADER + left * OVERVIEW_ROW_WIDTH
        val b = OVERVIEW_HEADER + right * OVERVIEW_ROW_WIDTH
        val pinA = metadata[a + 4].takeIf { it >= 0 } ?: Long.MAX_VALUE
        val pinB = metadata[b + 4].takeIf { it >= 0 } ?: Long.MAX_VALUE
        return when {
            pinA != pinB -> pinA < pinB
            metadata[a + 5] != metadata[b + 5] -> metadata[a + 5] > metadata[b + 5]
            metadata[a + 6] != metadata[b + 6] -> metadata[a + 6] > metadata[b + 6]
            else -> left < right
        }
    }
    for (index in 0 until rowCount) {
        val start = OVERVIEW_HEADER + index * OVERVIEW_ROW_WIDTH
        val source = metadata[start].toInt()
        if (source < 0) continue
        sources[source]++
        if (selected != -1 && source != selected) continue
        visible[visibleCount++] = index
        types[metadata[start + 1].toInt()]++
        val folder = metadata[start + 2].toInt()
        if (folder >= 0) folders[folder]++
        if (metadata[start + 3] == 1L) favorites[favoriteCount++] = index
        val card = metadata[start + 7] == 1L
        val recommend = recommendations and (if (card) 1 else 2) != 0
        if (metadata[start + 4] < 0 && (!recommend || metadata[start + 5] == 0L)) continue
        val best = if (card) cards else items
        val count = if (card) cardCount else itemCount
        var insertion = 0
        while (insertion < count && !comesBefore(index, best[insertion])) insertion++
        if (insertion < OVERVIEW_PREVIEW_LIMIT) {
            for (i in minOf(count, OVERVIEW_PREVIEW_LIMIT - 1) downTo insertion + 1) best[i] = best[i - 1]
            best[insertion] = index
            if (card) cardCount = minOf(count + 1, OVERVIEW_PREVIEW_LIMIT)
            else itemCount = minOf(count + 1, OVERVIEW_PREVIEW_LIMIT)
        }
    }
    return VaultOverviewAggregation(visible.copyOf(visibleCount), favorites.copyOf(favoriteCount),
        cards.copyOf(cardCount), items.copyOf(itemCount), types, sources, folders)
}

/** Validate dimensions, counts and index membership before any native result can index an item. */
internal fun decodeVaultOverviewAggregation(output: IntArray, metadata: LongArray): VaultOverviewAggregation? {
    if (metadata.size < OVERVIEW_HEADER || output.size < 5 || output[0] != 1) return null
    val rowCount = (metadata.size - OVERVIEW_HEADER) / OVERVIEW_ROW_WIDTH
    val sourceCount = metadata[1].toInt()
    val folderCount = metadata[2].toInt()
    val typeCount = metadata[5].toInt()
    if (sourceCount !in 0..4096 || folderCount !in 0..200_000 || typeCount !in 1..64) return null
    val visibleCount = output[1]
    val favoriteCount = output[2]
    val cardCount = output[3]
    val itemCount = output[4]
    if (visibleCount !in 0..rowCount || favoriteCount !in 0..visibleCount ||
        cardCount !in 0..minOf(OVERVIEW_PREVIEW_LIMIT, visibleCount) ||
        itemCount !in 0..minOf(OVERVIEW_PREVIEW_LIMIT, visibleCount)) return null
    val expected = 5L + typeCount + sourceCount + folderCount + visibleCount + favoriteCount + cardCount + itemCount
    if (expected != output.size.toLong()) return null
    var cursor = 5
    fun take(size: Int): IntArray = output.copyOfRange(cursor, cursor + size).also { cursor += size }
    val types = take(typeCount)
    val sources = take(sourceCount)
    val folders = take(folderCount)
    if (types.any { it !in 0..visibleCount } || types.sumOf(Int::toLong) != visibleCount.toLong() ||
        sources.any { it !in 0..rowCount } || sources.sumOf(Int::toLong) !in visibleCount.toLong()..rowCount.toLong() ||
        folders.any { it !in 0..visibleCount } || folders.sumOf(Int::toLong) > visibleCount) return null
    val visible = take(visibleCount)
    val favorites = take(favoriteCount)
    val cards = take(cardCount)
    val items = take(itemCount)
    fun IntArray.hasOrderedIndices(): Boolean = indices.all {
        this[it] in 0 until rowCount && (it == 0 || this[it] > this[it - 1])
    }
    if (!visible.hasOrderedIndices() || !favorites.hasOrderedIndices()) return null
    var visibleCursor = 0
    for (favorite in favorites) {
        while (visibleCursor < visible.size && visible[visibleCursor] < favorite) visibleCursor++
        if (visibleCursor == visible.size || visible[visibleCursor] != favorite ||
            metadata[OVERVIEW_HEADER + favorite * OVERVIEW_ROW_WIDTH + 3] != 1L) return null
    }
    fun validPreview(indices: IntArray, card: Boolean): Boolean =
        indices.toSet().size == indices.size && indices.all {
            visible.binarySearch(it) >= 0 && (metadata[OVERVIEW_HEADER + it * OVERVIEW_ROW_WIDTH + 7] == 1L) == card
        }
    if (!validPreview(cards, true) || !validPreview(items, false)) return null
    return VaultOverviewAggregation(visible, favorites, cards, items, types, sources, folders)
}
