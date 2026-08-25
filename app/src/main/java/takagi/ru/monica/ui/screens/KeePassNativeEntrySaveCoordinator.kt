package takagi.ru.monica.ui.screens

import android.net.Uri
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassNativeEntryPresentationUpdate
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassNativeGroupIdentity
import takagi.ru.monica.keepass.KeePassTemplateEngine
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

internal data class KeePassNativeEntrySaveOutcome(
    val savedEntry: KeePassNativeEntryRecord? = null,
    val createdEntry: KeePassNativeEntryRecord? = null,
    val failure: Throwable? = null,
)

internal suspend fun saveKeePassNativeManagerEntry(
    viewModel: LocalKeePassViewModel,
    databaseId: Long,
    editingEntry: KeePassNativeEntryRecord?,
    creatingParent: KeePassNativeGroupIdentity?,
    templateMode: Boolean,
    fields: List<KeePassFieldChange>,
    presentation: KeePassNativeEntryPresentationUpdate?,
    pendingAttachments: List<Uri>,
    revisionToken: String,
): KeePassNativeEntrySaveOutcome {
    val persistedFields = fields.withTemplateMarkerIfNeeded(templateMode)
    return if (editingEntry != null) {
        saveExistingKeePassNativeEntry(
            viewModel = viewModel,
            databaseId = databaseId,
            editingEntry = editingEntry,
            fields = persistedFields,
            presentation = presentation,
            pendingAttachments = pendingAttachments,
            revisionToken = revisionToken,
        )
    } else {
        createKeePassNativeEntry(
            viewModel = viewModel,
            databaseId = databaseId,
            creatingParent = creatingParent,
            fields = persistedFields,
            presentation = presentation,
            pendingAttachments = pendingAttachments,
            revisionToken = revisionToken,
        )
    }
}

private suspend fun saveExistingKeePassNativeEntry(
    viewModel: LocalKeePassViewModel,
    databaseId: Long,
    editingEntry: KeePassNativeEntryRecord,
    fields: List<KeePassFieldChange>,
    presentation: KeePassNativeEntryPresentationUpdate?,
    pendingAttachments: List<Uri>,
    revisionToken: String,
): KeePassNativeEntrySaveOutcome {
    val fieldResult = if (presentation == null) {
        viewModel.replaceNativeEntryFields(
            databaseId = databaseId,
            entryUuid = editingEntry.identity.entryUuid,
            fields = fields,
            expectedRevisionToken = revisionToken,
        )
    } else {
        viewModel.replaceNativeEntryFieldsAndPresentation(
            databaseId = databaseId,
            entryUuid = editingEntry.identity.entryUuid,
            fields = fields,
            presentation = presentation,
            expectedRevisionToken = revisionToken,
        )
    }
    val fieldFailure = fieldResult.exceptionOrNull()
    if (fieldFailure != null) return KeePassNativeEntrySaveOutcome(failure = fieldFailure)
    val savedFields = fieldResult.getOrThrow()
    if (pendingAttachments.isEmpty()) {
        return KeePassNativeEntrySaveOutcome(savedEntry = savedFields)
    }

    val browserResult = viewModel.openNativeBrowser(databaseId)
    val browserFailure = browserResult.exceptionOrNull()
    if (browserFailure != null) return KeePassNativeEntrySaveOutcome(failure = browserFailure)
    val attachmentResult = viewModel.addNativeAttachments(
        databaseId = databaseId,
        entryUuid = editingEntry.identity.entryUuid,
        sourceUris = pendingAttachments,
        expectedRevisionToken = browserResult.getOrThrow().sourceRevision.sha256,
    )
    return attachmentResult.fold(
        onSuccess = { saved -> KeePassNativeEntrySaveOutcome(savedEntry = saved) },
        onFailure = { failure -> KeePassNativeEntrySaveOutcome(failure = failure) },
    )
}

private suspend fun createKeePassNativeEntry(
    viewModel: LocalKeePassViewModel,
    databaseId: Long,
    creatingParent: KeePassNativeGroupIdentity?,
    fields: List<KeePassFieldChange>,
    presentation: KeePassNativeEntryPresentationUpdate?,
    pendingAttachments: List<Uri>,
    revisionToken: String,
): KeePassNativeEntrySaveOutcome {
    val parent = creatingParent ?: return KeePassNativeEntrySaveOutcome(
        failure = IllegalStateException("KeePass parent group is unavailable"),
    )
    val createResult = if (pendingAttachments.isEmpty()) {
        viewModel.createNativeEntry(
            databaseId = databaseId,
            parentGroupUuid = parent.groupUuid,
            fields = fields,
            expectedRevisionToken = revisionToken,
        )
    } else {
        viewModel.createNativeEntryWithAttachments(
            databaseId = databaseId,
            parentGroupUuid = parent.groupUuid,
            fields = fields,
            sourceUris = pendingAttachments,
            expectedRevisionToken = revisionToken,
        )
    }
    val createFailure = createResult.exceptionOrNull()
    if (createFailure != null) return KeePassNativeEntrySaveOutcome(failure = createFailure)
    val created = createResult.getOrThrow()
    if (presentation == null) {
        return KeePassNativeEntrySaveOutcome(savedEntry = created, createdEntry = created)
    }

    val browserResult = viewModel.openNativeBrowser(databaseId)
    val browserFailure = browserResult.exceptionOrNull()
    if (browserFailure != null) {
        return KeePassNativeEntrySaveOutcome(createdEntry = created, failure = browserFailure)
    }
    val presentationResult = viewModel.replaceNativeEntryPresentation(
        databaseId = databaseId,
        entryUuid = created.identity.entryUuid,
        update = presentation,
        expectedRevisionToken = browserResult.getOrThrow().sourceRevision.sha256,
    )
    return presentationResult.fold(
        onSuccess = { saved ->
            KeePassNativeEntrySaveOutcome(savedEntry = saved, createdEntry = created)
        },
        onFailure = { failure ->
            KeePassNativeEntrySaveOutcome(createdEntry = created, failure = failure)
        },
    )
}

private fun List<KeePassFieldChange>.withTemplateMarkerIfNeeded(
    templateMode: Boolean,
): List<KeePassFieldChange> {
    if (!templateMode) return this
    val markerExists = any { field ->
        field.name.equals(KeePassTemplateEngine.TEMPLATE_MARKER_FIELD, ignoreCase = true)
    }
    if (markerExists) return this
    return this + KeePassFieldChange(
        name = KeePassTemplateEngine.TEMPLATE_MARKER_FIELD,
        value = KeePassTemplateEngine.TEMPLATE_MARKER_VALUE,
    )
}
