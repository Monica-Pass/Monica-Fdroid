package takagi.ru.monica.steam.market

import takagi.ru.monica.steam.network.SteamConfirmation

fun mergeSteamInventoryStacks(
    existing: List<SteamInventoryItemStack>,
    incoming: List<SteamInventoryItem>
): List<SteamInventoryItemStack> {
    val stacks = existing.associateBy { it.item.stackKey }.toMutableMap()
    val order = existing.map { it.item.stackKey }.toMutableList()
    incoming.forEach { item ->
        val assetIds = List(item.amount.coerceAtLeast(1)) { item.assetId }
        val current = stacks[item.stackKey]
        if (current == null) {
            stacks[item.stackKey] = SteamInventoryItemStack(item = item, assetIds = assetIds)
            order += item.stackKey
        } else {
            stacks[item.stackKey] = current.copy(assetIds = current.assetIds + assetIds)
        }
    }
    return order.mapNotNull(stacks::get)
}

fun findNewSteamMarketConfirmations(
    preExistingIds: Set<String>,
    latest: List<SteamConfirmation>
): List<SteamConfirmation> {
    return latest.filter { confirmation ->
        confirmation.isMarketListingConfirmation() && confirmation.id !in preExistingIds
    }
}

fun removeCancelledSteamMarketListings(
    existing: List<SteamMarketListing>,
    cancelledListingIds: Set<String>
): List<SteamMarketListing> {
    if (cancelledListingIds.isEmpty()) return existing
    return existing.filterNot { it.listingId in cancelledListingIds }
}

fun SteamConfirmation.isMarketListingConfirmation(): Boolean {
    val normalized = type.trim().lowercase()
    return normalized == "3" || normalized.contains("market") || normalized.contains("sell")
}
