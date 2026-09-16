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
) {
    override fun toString() = "NativeApiTokenEditorState(redacted)"

    val canSave: Boolean get() = !loading && !saving && databaseId != null &&
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

    init {
        load()
        viewModelScope.launch {
            SessionManager.isUnlocked.drop(1).collect { unlocked ->
                if (!unlocked) {
                    loadJob?.cancel()
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
                val saved = databases.saveNativeApiToken(snapshot.databaseId!!, snapshot.original,
                    snapshot.title.trim(), snapshot.payload,
                    if (snapshot.original != null) snapshot.folderId.orEmpty() else snapshot.folderId,
                    isFavorite = snapshot.isFavorite, metadata = ApiTokenMetadata.withCustomFields(snapshot.metadata,
                        ApiTokenMetadata.customFields(snapshot.metadata).filter { it.shouldPersist() }))
                mutableState.update { it.copy(saved = saved, changed = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(failed = true) }
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    companion object {
        val DEFAULT_ENDPOINTS = mapOf("gitlab" to "https://gitlab.com/api/v4/", "github" to "https://api.github.com/")
    }
}
