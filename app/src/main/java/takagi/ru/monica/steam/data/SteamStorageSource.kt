package takagi.ru.monica.steam.data

sealed interface SteamStorageSource {
    data object Local : SteamStorageSource
    data class Mdbx(val databaseId: Long) : SteamStorageSource
    data class KeePass(val databaseId: Long) : SteamStorageSource
    data class Bitwarden(val vaultId: Long) : SteamStorageSource
}
