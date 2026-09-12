package takagi.ru.monica.ime

internal val MonicaImeUiState.activeEntryCount: Int
    get() = when (activePanel) {
        MonicaImePanel.PASSWORDS -> entries.size
        MonicaImePanel.AUTHENTICATORS -> authenticatorEntries.size
        MonicaImePanel.DOCUMENTS -> cardWalletEntries.size
        MonicaImePanel.KEYBOARD, MonicaImePanel.GENERATOR -> 0
    }

internal fun MonicaImeUiState.startVaultSearch(): MonicaImeUiState {
    if (!unlocked || !activePanel.isVaultContentPanel()) return this
    return copy(
        isSearchEditing = true,
        keyboardMode = MonicaKeyboardMode.LETTERS,
        isUppercase = false
    )
}

internal fun MonicaImeUiState.selectVaultPanel(
    panel: MonicaImePanel,
    isLoading: Boolean
): MonicaImeUiState {
    val preserveQuery = panel == activePanel
    return copy(
        activePanel = panel,
        isAutofillPanelVisible = true,
        isAutofillLoading = isLoading,
        isSearchEditing = false,
        errorMessage = null,
        query = if (preserveQuery) query else "",
        passwordSortMode = if (preserveQuery) passwordSortMode else MonicaImePasswordSortMode.ALPHABETICAL
    )
}

// The source snapshot can stay cached while each panel has a different filtered projection.
internal data class ImeVaultPresentation(
    val query: String,
    val scope: MonicaImeDatabaseScope,
    val passwordSortMode: MonicaImePasswordSortMode,
    val activePackageName: String
)

internal fun MonicaImeUiState.vaultPresentation(): ImeVaultPresentation = ImeVaultPresentation(
    query = query.trim(),
    scope = selectedDatabaseScope,
    passwordSortMode = if (activePanel == MonicaImePanel.PASSWORDS) {
        passwordSortMode
    } else {
        MonicaImePasswordSortMode.ALPHABETICAL
    },
    activePackageName = if (
        activePanel == MonicaImePanel.PASSWORDS &&
        passwordSortMode == MonicaImePasswordSortMode.RELEVANCE
    ) activePackageName else ""
)
