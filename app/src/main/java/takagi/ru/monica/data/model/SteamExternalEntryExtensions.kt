package takagi.ru.monica.data.model

import takagi.ru.monica.data.PasswordEntry

const val LOGIN_TYPE_STEAM_MAFILE: String = "STEAM_MAFILE"

fun PasswordEntry.isExternalSteamMaFileEntry(): Boolean =
    loginType.trim().equals(LOGIN_TYPE_STEAM_MAFILE, ignoreCase = true)
