package takagi.ru.monica.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.PasswordWebsiteCodec

fun interface CommonFieldSuggestionSource {
    fun observe(field: CommonSuggestionField): Flow<CommonFieldSuggestionIndex>
}

/** Read-only, focus-scoped suggestions. No database opening, sync, writes or persisted history. */
class CommonFieldSuggestionRepository(
    private val database: PasswordDatabase,
    private val decrypt: (String) -> String,
    private val unlocked: StateFlow<Boolean> = SessionManager.isUnlocked,
    private val nameTemplates: Flow<List<String>> = flowOf(emptyList()),
    private val billingTemplate: Flow<takagi.ru.monica.data.model.BillingAddress> = flowOf(takagi.ru.monica.data.model.BillingAddress()),
) : CommonFieldSuggestionSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(field: CommonSuggestionField): Flow<CommonFieldSuggestionIndex> = unlocked.flatMapLatest { open ->
        if (!open) flowOf(CommonFieldSuggestionIndex(emptyList(), field))
        else observeAccessible(field)
    }

    private fun observeAccessible(field: CommonSuggestionField): Flow<CommonFieldSuggestionIndex> {
        val sources = combine(
            database.localKeePassDatabaseDao().getAllDatabases(),
            database.bitwardenVaultDao().getAllVaultsFlow(),
            database.localMdbxDatabaseDao().getAvailableDatabases(),
        ) { keepass, bitwarden, mdbx -> buildSet {
            add("local")
            keepass.filter { !it.encryptedPassword.isNullOrBlank() || !it.keyFileUri.isNullOrBlank() || !it.keyFileInternalPath.isNullOrBlank() }
                .forEach { add("keepass:${it.id}") }
            bitwarden.filterNot { it.isLocked }.forEach { add("bitwarden:${it.id}") }
            mdbx.forEach { add("mdbx:${it.id}") }
        } }
        val itemFlows = field.walletKinds().map { database.secureItemDao().getItemsByType(it.itemType) }
        val items = if (itemFlows.isEmpty()) flowOf(emptyList()) else combine(itemFlows) { it.flatMap { rows -> rows } }
        val templates = if (field == CommonSuggestionField.FULL_NAME) nameTemplates else billingTemplate.map { listOfNotNull(field.addressValue(it)) }
        val metadata = if (field.metadataTitles().isEmpty()) flowOf(emptyList()) else
            database.customFieldDao().observeCommonSuggestionFields(field.metadataTitles())
        return combine(sources, database.passwordEntryDao().observeCommonSuggestionOwners(), items, metadata, templates) { access, owners, secureItems, fields, names ->
            val readableOwners = owners.filter {
                commonSuggestionSourceAccessible(it.keepassDatabaseId, it.bitwardenVaultId, it.mdbxDatabaseId, access)
            }.associateBy { it.id }
            val values = buildList {
                addAll(names)
                readableOwners.values.forEach { owner -> field.ownerValue(owner)?.let { plaintext(it) }?.let(::add) }
                if (field == CommonSuggestionField.WEBSITE) readableOwners.values.forEach { addAll(PasswordWebsiteCodec.parse(it.website)) }
                secureItems.filter { !it.isDeleted && commonSuggestionSourceAccessible(it.keepassDatabaseId, it.bitwardenVaultId, it.mdbxDatabaseId, access) }
                    .forEach { item -> plaintext(item.itemData)?.let { field.walletValue(it, item.itemType) }?.let(::add) }
                fields.filter { it.entryId in readableOwners }.forEach { item ->
                    plaintext(item.value)?.let { field.metadataValue(item.title, it) }?.let(::add)
                }
            }
            CommonFieldSuggestionIndex(if (unlocked.value) values else emptyList(), field)
        }.flowOn(Dispatchers.Default).catch { emit(CommonFieldSuggestionIndex(emptyList(), field)) }
    }

    private fun plaintext(value: String): String? = try { decrypt(value) }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
