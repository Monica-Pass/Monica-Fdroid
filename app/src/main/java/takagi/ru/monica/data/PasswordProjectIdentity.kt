package takagi.ru.monica.data

import java.util.UUID

/** Only an explicit editor-created identity may combine passwords. Never infer it from content. */
fun PasswordEntry.explicitPasswordGroupId(): String? =
    passwordGroupId?.takeIf { it.isNotBlank() }
        ?: replicaGroupId?.takeIf { mdbxDatabaseId == null && runCatching { UUID.fromString(it) }.isSuccess }

fun PasswordEntry.passwordProjectKey(): String {
    val source = when {
        mdbxDatabaseId != null -> "mdbx:$mdbxDatabaseId"
        keepassDatabaseId != null -> "keepass:$keepassDatabaseId"
        bitwardenVaultId != null -> "bitwarden:$bitwardenVaultId"
        else -> "local"
    }
    return "$source|${explicitPasswordGroupId()?.let { "group:$it" } ?: "entry:$id"}"
}
