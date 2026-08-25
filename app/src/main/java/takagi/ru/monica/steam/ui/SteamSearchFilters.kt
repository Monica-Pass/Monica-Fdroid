package takagi.ru.monica.steam.ui

import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.market.SteamInventoryItemStack
import takagi.ru.monica.steam.market.SteamMarketListing
import takagi.ru.monica.steam.network.SteamConfirmation

internal fun filterSteamAccounts(
    accounts: List<SteamAccount>,
    query: String
): List<SteamAccount> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return accounts

    return accounts.filter { account ->
        account.accountName.contains(normalizedQuery, ignoreCase = true) ||
            account.displayName.contains(normalizedQuery, ignoreCase = true) ||
            account.steamId.contains(normalizedQuery, ignoreCase = true)
    }
}

internal fun filterSteamConfirmations(
    confirmations: List<SteamConfirmation>,
    query: String
): List<SteamConfirmation> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return confirmations

    return confirmations.filter { confirmation ->
        confirmation.headline.contains(normalizedQuery, ignoreCase = true) ||
            confirmation.summary.contains(normalizedQuery, ignoreCase = true) ||
            confirmation.type.contains(normalizedQuery, ignoreCase = true)
    }
}

internal fun filterSteamInventoryStacks(
    stacks: List<SteamInventoryItemStack>,
    query: String
): List<SteamInventoryItemStack> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return stacks
    return stacks.filter { stack ->
        stack.item.name.contains(normalizedQuery, ignoreCase = true) ||
            stack.item.marketHashName.contains(normalizedQuery, ignoreCase = true) ||
            stack.item.type.contains(normalizedQuery, ignoreCase = true)
    }
}

internal fun filterSteamMarketListings(
    listings: List<SteamMarketListing>,
    query: String
): List<SteamMarketListing> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return listings
    return listings.filter { listing ->
        listing.name.contains(normalizedQuery, ignoreCase = true) ||
            listing.marketHashName.contains(normalizedQuery, ignoreCase = true) ||
            listing.listingId.contains(normalizedQuery, ignoreCase = true)
    }
}

internal fun steamCommunityLanguage(languageCode: String): String {
    return when (languageCode.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "schinese"
        "zh-tw", "zh-hk", "zh-hant" -> "tchinese"
        "ja" -> "japanese"
        "ru" -> "russian"
        "vi" -> "vietnamese"
        else -> "english"
    }
}
