package takagi.ru.monica.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import takagi.ru.monica.data.ApiTokenPayload
import takagi.ru.monica.data.ApiTokenMetadata
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.NativeApiToken
import takagi.ru.monica.data.NativeApiTokenSummary
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.data.NativeApiTokenAttachment
import takagi.ru.monica.data.NativeApiTokenAssets
import takagi.ru.monica.data.NativeApiTokenUpload
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.attachments.*

internal data class NativeApiTokenEditorState(
    val databaseId: Long? = null,
    val folderId: String? = null,
    val original: NativeApiToken? = null,
    val title: String = "",
    val payload: String = ApiTokenPayload.empty().toString(),
    val isFavorite: Boolean = false,
    val metadata: String = ApiTokenMetadata.empty(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val changed: Boolean = false,
    val saved: NativeApiTokenSummary? = null,
    val preparing: Boolean = false,
    val pendingAttachments: List<NativeApiTokenAttachment> = emptyList(),
    val removedAttachmentIds: Set<String> = emptySet(),
) {
    override fun toString() = "NativeApiTokenEditorState(redacted)"

    val canSave: Boolean get() = !loading && !saving && !preparing && databaseId != null &&
        ApiTokenPayload.isValidStorageName(title.trim()) && ApiTokenPayload.isValidForStorage(payload) &&
        ApiTokenMetadata.isValid(metadata) && ApiTokenPayload.text(ApiTokenPayload.decode(payload), "token").let {
            it.length < 16 || !title.contains(it)
        }
}

/** Drafts live only in memory; neither the token nor JSON is put in SavedStateHandle. */
internal class NativeApiTokenEditorViewModel(
    private val databases: MdbxViewModel,
    private val initialDatabaseId: Long?,
    private val entryId: String?,
    initialFolderId: String?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NativeApiTokenEditorState(
        databaseId = initialDatabaseId, folderId = initialFolderId, loading = entryId != null))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var prepareJob: Job? = null
    private val context = databases.getApplication<android.app.Application>()
    private val walletDrafts = EmbeddedWalletDraftStore(context)
    private val attachmentDrafts = linkedMapOf<String, EmbeddedWalletAssetDraft>()

    fun walletDraft(id: String) = walletDrafts.draft(id)

    fun installWallet(prepared: EmbeddedWalletCopyService.Prepared) {
        check(!state.value.saving)
        try {
            require(prepared.assets.assets.sumOf { it.size } <= NativeApiTokenAssets.MAX_BYTES)
            val fields = EmbeddedWalletContent.put(ApiTokenMetadata.customFields(state.value.metadata), prepared.snapshot)
            walletDrafts.add(prepared)
            changeCustomFields(fields)
        } catch (error: Throwable) { prepared.close(); throw error }
    }

    fun copyWallet(item: takagi.ru.monica.data.SecureItem) {
        if (state.value.saving || state.value.preparing) return
        mutableState.update { it.copy(preparing = true, failed = false) }
        prepareJob = viewModelScope.launch {
            try {
                val security = takagi.ru.monica.security.SecurityManager(context)
                val decoded = item.copy(itemData = security.decryptDataIfMonicaCiphertext(item.itemData),
                    notes = security.decryptDataIfMonicaCiphertext(item.notes))
                installWallet(EmbeddedWalletCopyService(context).prepare(decoded))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(failed = true) }
            } finally { mutableState.update { it.copy(preparing = false) } }
        }
    }

    fun addAttachment(uri: android.net.Uri) {
        if (state.value.saving || state.value.preparing) return
        mutableState.update { it.copy(preparing = true, failed = false) }
        prepareJob = viewModelScope.launch {
            var prepared: EmbeddedWalletAssetDraft? = null
            try {
                val info = takagi.ru.monica.attachments.facade.AttachmentUriMetadata.resolve(context, uri)
                require(info.sizeBytes <= NativeApiTokenAssets.MAX_BYTES)
                prepared = EmbeddedWalletAssetDraft.prepare(java.io.File(context.cacheDir, "native-token-drafts"), listOf(
                    EmbeddedWalletAssetDraft.Source(info.fileName, context.contentResolver.getType(uri) ?: "application/octet-stream",
                        EmbeddedWalletContent.AssetRole.ATTACHMENT, info.sizeBytes.takeIf { it >= 0 }) { output ->
                        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { NativeApiTokenAssets.readBounded(it) }
                        try { output.write(bytes) } finally { bytes.fill(0) }
                    }))
                val asset = prepared.assets.single()
                attachmentDrafts[asset.name] = prepared
                prepared = null
                mutableState.update { it.copy(pendingAttachments = it.pendingAttachments + NativeApiTokenAttachment(
                    asset.name, asset.displayName, asset.mimeType, asset.size, asset.sha256), changed = true) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(failed = true) }
            } finally { prepared?.close(); mutableState.update { it.copy(preparing = false) } }
        }
    }

    fun removeAttachment(id: String) {
        if (state.value.saving || state.value.preparing) return
        attachmentDrafts.remove(id)?.close()
        mutableState.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { asset -> asset.id == id },
            removedAttachmentIds = if (it.original?.attachments?.any { asset -> asset.id == id } == true) it.removedAttachmentIds + id else it.removedAttachmentIds,
            changed = true, failed = false) }
    }

    private fun closeDrafts() {
        walletDrafts.close(); attachmentDrafts.values.forEach { it.close() }; attachmentDrafts.clear()
    }

    override fun onCleared() { closeDrafts(); super.onCleared() }

    init {
        load()
        viewModelScope.launch {
            SessionManager.isUnlocked.drop(1).collect { unlocked ->
                if (!unlocked) {
                    loadJob?.cancel()
                    prepareJob?.cancel()
                    closeDrafts()
                    mutableState.value = NativeApiTokenEditorState(databaseId = state.value.databaseId)
                }
            }
        }
    }

    fun load() {
        val id = entryId ?: return
        val db = initialDatabaseId ?: return
        if (loadJob?.isActive == true || state.value.saving) return
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, failed = false) }
            try {
                val original = databases.readNativeApiToken(db, id)
                mutableState.value = NativeApiTokenEditorState(databaseId = db, folderId = original.summary.collectionId,
                    original = original, title = original.summary.title, payload = original.payload,
                    isFavorite = original.summary.isFavorite, metadata = original.extras?.payload ?: ApiTokenMetadata.empty())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(failed = true) }
            } finally { mutableState.update { it.copy(loading = false) } }
        }
    }

    fun selectDatabase(databaseId: Long, folderId: String?, initial: Boolean = false) {
        if (state.value.saving || (entryId != null && databaseId != initialDatabaseId)) return
        mutableState.update { it.copy(databaseId = databaseId, folderId = folderId,
            changed = it.changed || !initial, failed = false) }
    }

    fun changeTitle(title: String) {
        if (!state.value.saving) mutableState.update { it.copy(title = title, changed = true, failed = false) }
    }

    fun setFavorite(isFavorite: Boolean) {
        if (state.value.saving || state.value.loading ||
            (entryId != null && state.value.original?.payload?.let(ApiTokenPayload::decode) == null)) return
        mutableState.update { it.copy(isFavorite = isFavorite, changed = true, failed = false) }
    }

    fun changeField(field: String, value: String) {
        if (!state.value.saving) mutableState.update {
            it.copy(payload = ApiTokenPayload.update(it.payload, field, value), changed = true, failed = false)
        }
    }

    fun changeNotes(notes: String) {
        if (!state.value.saving) mutableState.update {
            it.copy(metadata = ApiTokenMetadata.withNotes(it.metadata, notes), changed = true, failed = false)
        }
    }

    fun changeCustomFields(fields: List<CustomFieldDraft>) {
        if (!state.value.saving) mutableState.update {
            it.copy(metadata = ApiTokenMetadata.withCustomFields(it.metadata, fields), changed = true, failed = false)
        }
    }

    fun selectProvider(provider: String) {
        val fields = ApiTokenPayload.decode(state.value.payload)
        val currentEndpoint = ApiTokenPayload.text(fields, "api_base")
        changeField("provider", provider)
        if (currentEndpoint.isBlank() || currentEndpoint in DEFAULT_ENDPOINTS.values) {
            DEFAULT_ENDPOINTS[provider]?.let { changeField("api_base", it) }
        }
    }

    fun importPayload(payload: String): Boolean {
        if (state.value.saving || ApiTokenPayload.decode(payload) == null) return false
        mutableState.update { it.copy(payload = payload, changed = true, failed = false) }
        return true
    }

    fun save() {
        val snapshot = state.value
        if (!snapshot.canSave || (entryId != null && snapshot.original == null)) return
        mutableState.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            try {
                val snapshots = ApiTokenMetadata.customFields(snapshot.metadata).mapNotNull {
                    (EmbeddedWalletContent.read(it.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot
                }
                val uploads = snapshot.pendingAttachments.map { asset ->
                    val draft = requireNotNull(attachmentDrafts[asset.id])
                    NativeApiTokenUpload(asset.fileName, asset.mimeType, asset.size, asset.sha256) { draft.open(asset.id) }
                } + snapshots.mapNotNull { walletDrafts.draft(it.id) }.flatMap { prepared ->
                    prepared.assets.assets.map { asset ->
                        NativeApiTokenUpload(asset.name, asset.mimeType, asset.size, asset.sha256) { prepared.assets.open(asset.name) }
                    }
                }
                val retainedAssetNames = snapshots.flatMap { it.assets }.map { it.name }.toSet()
                val previousAssetNames = ApiTokenMetadata.customFields(snapshot.original?.extras?.payload ?: ApiTokenMetadata.empty()).flatMap {
                    (EmbeddedWalletContent.read(it.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot?.assets.orEmpty()
                }.map { it.name }.toSet()
                val obsoleteAssetIds = snapshot.original?.attachments.orEmpty().filter {
                    it.fileName in previousAssetNames && it.fileName !in retainedAssetNames
                }.map { it.id }
                val saved = databases.saveNativeApiToken(snapshot.databaseId!!, snapshot.original,
                    snapshot.title.trim(), snapshot.payload,
                    if (snapshot.original != null) snapshot.folderId.orEmpty() else snapshot.folderId,
                    isFavorite = snapshot.isFavorite, metadata = ApiTokenMetadata.withCustomFields(snapshot.metadata,
                        ApiTokenMetadata.customFields(snapshot.metadata).filter { it.shouldPersist() }),
                    uploads = uploads, removedAttachmentIds = snapshot.removedAttachmentIds + obsoleteAssetIds)
                mutableState.update { it.copy(saved = saved, changed = false) }
                closeDrafts()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(failed = true) }
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    companion object {
        val DEFAULT_ENDPOINTS = mapOf("gitlab" to "https://gitlab.com/api/v4/", "github" to "https://api.github.com/")
    }
}
