package takagi.ru.monica.steam.token.identity.domain

internal data class SteamIdentityFormats(
    val steamId64: String,
    val steamId3: String,
    val steamId2: String,
    val accountId: String,
    val communityProfileUrl: String,
)

internal object SteamIdentityConverter {
    fun fromSteamId64(value: String): SteamIdentityFormats? {
        val normalized = value.trim()
        if (normalized.length != STEAM_ID64_LENGTH || normalized.any { !it.isDigit() }) {
            return null
        }

        val steamId64 = normalized.toLongOrNull() ?: return null
        val accountId = steamId64 - STEAM_ID64_INDIVIDUAL_BASE
        if (accountId !in 0L..STEAM_ACCOUNT_ID_MAX) return null

        return SteamIdentityFormats(
            steamId64 = steamId64.toString(),
            steamId3 = "[U:1:$accountId]",
            steamId2 = "STEAM_0:${accountId % 2}:${accountId / 2}",
            accountId = accountId.toString(),
            communityProfileUrl = "https://steamcommunity.com/profiles/$steamId64/",
        )
    }

    private const val STEAM_ID64_LENGTH = 17
    private const val STEAM_ID64_INDIVIDUAL_BASE = 76_561_197_960_265_728L
    private const val STEAM_ACCOUNT_ID_MAX = 4_294_967_295L
}
