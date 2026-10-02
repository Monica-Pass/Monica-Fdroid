package takagi.ru.monica.data

import androidx.room.ColumnInfo

/** Wallet-only projection. Never load login secrets, notes, histories or attachment bodies. */
data class WalletPasswordRow(
    val id: Long,
    val title: String,
    val isFavorite: Boolean,
    val categoryId: Long?,
    val keepassDatabaseId: Long?,
    val keepassGroupPath: String?,
    @ColumnInfo(name = "mdbx_database_id") val mdbxDatabaseId: Long?,
    @ColumnInfo(name = "mdbx_folder_id") val mdbxFolderId: String?,
    @ColumnInfo(name = "bitwarden_vault_id") val bitwardenVaultId: Long?,
    @ColumnInfo(name = "bitwarden_folder_id") val bitwardenFolderId: String?,
    val creditCardNumber: String,
    val creditCardHolder: String,
    val creditCardExpiry: String,
    val creditCardCVV: String,
    val addressLine: String,
    val city: String,
    val state: String,
    val zipCode: String,
    val country: String,
    val email: String,
    val phone: String,
)

internal const val WALLET_PASSWORD_COLUMNS = "id, title, isFavorite, categoryId, keepassDatabaseId, " +
    "keepassGroupPath, mdbx_database_id, mdbx_folder_id, bitwarden_vault_id, bitwarden_folder_id, " +
    "creditCardNumber, creditCardHolder, creditCardExpiry, creditCardCVV, addressLine, city, state, zipCode, country, email, phone"
