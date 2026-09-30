package takagi.ru.monica.ui.vaultv2

/** Section ends are ordered, including leading controls and section headers. */
private fun vaultV2SectionForLazyIndex(
    sectionLayouts: List<VaultV2SectionLayout>,
    lazyIndex: Int,
): VaultV2SectionLayout? {
    if (sectionLayouts.isEmpty()) return null
    var low = 0
    var high = sectionLayouts.lastIndex
    while (low < high) {
        val middle = (low + high) ushr 1
        val section = sectionLayouts[middle]
        val end = section.firstItemLazyIndex + section.items.lastIndex.coerceAtLeast(0)
        if (lazyIndex <= end) high = middle else low = middle + 1
    }
    return sectionLayouts[low]
}

internal fun vaultV2ItemIndexForLazyIndex(
    sectionLayouts: List<VaultV2SectionLayout>,
    lazyIndex: Int,
): Int {
    val section = vaultV2SectionForLazyIndex(sectionLayouts, lazyIndex) ?: return 0
    return section.itemStartIndex +
        (lazyIndex - section.firstItemLazyIndex).coerceIn(0, section.items.lastIndex.coerceAtLeast(0))
}

internal fun vaultV2SectionTitleForLazyIndex(
    sectionLayouts: List<VaultV2SectionLayout>,
    lazyIndex: Int,
): String? = vaultV2SectionForLazyIndex(sectionLayouts, lazyIndex)?.title
