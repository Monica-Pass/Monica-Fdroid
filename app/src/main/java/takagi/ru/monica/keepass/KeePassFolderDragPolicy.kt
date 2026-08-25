package takagi.ru.monica.keepass

/** Pure interaction policy for the long-press folder drag mode. */
internal object KeePassFolderDragPolicy {
    fun canBatchDrop(
        sources: Set<KeePassNativeGroupIdentity>,
        target: KeePassNativeGroupIdentity,
        browser: KeePassNativeBrowserSnapshot,
    ): Boolean {
        if (sources.isEmpty()) return false
        if (sources.size != sources.distinct().size) return false
        if (sources.any { source -> !canDrop(source, target, browser) }) return false
        return sources.none { source ->
            sources.any { other ->
                source != other && source in browser.descendantGroupIdentities(other)
            }
        }
    }

    fun canDrop(
        source: KeePassNativeGroupIdentity,
        target: KeePassNativeGroupIdentity,
        browser: KeePassNativeBrowserSnapshot,
    ): Boolean {
        if (source.databaseId != target.databaseId || source == target) return false
        if (target in browser.descendantGroupIdentities(source)) return false
        return browser.group(source) != null && browser.group(target) != null
    }

    fun targetIds(
        source: KeePassNativeGroupIdentity,
        browser: KeePassNativeBrowserSnapshot,
    ): Set<KeePassNativeGroupIdentity> = browser.groups
        .asSequence()
        .map { it.identity }
        .filter { canDrop(source, it, browser) }
        .toSet()
}
