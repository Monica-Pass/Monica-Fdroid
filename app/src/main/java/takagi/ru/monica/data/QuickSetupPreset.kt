package takagi.ru.monica.data

/** Page layouts only: applying a preset never connects accounts or changes security settings. */
enum class QuickSetupPreset(
    val tabs: List<BottomNavContentTab>,
    val vaultOverviewEnabled: Boolean? = null,
) {
    BITWARDEN_PREVIEW(
        listOf(BottomNavContentTab.VAULT_V2, BottomNavContentTab.SEND, BottomNavContentTab.GENERATOR),
        vaultOverviewEnabled = true,
    ),
    BITWARDEN_LIST(
        listOf(BottomNavContentTab.VAULT_V2, BottomNavContentTab.SEND, BottomNavContentTab.GENERATOR),
        vaultOverviewEnabled = false,
    ),
    AUTHENTICATOR(listOf(BottomNavContentTab.AUTHENTICATOR, BottomNavContentTab.STEAM)),
    PAGED(listOf(
        BottomNavContentTab.PASSWORDS, BottomNavContentTab.AUTHENTICATOR,
        BottomNavContentTab.CARD_WALLET, BottomNavContentTab.NOTES,
    )),
    EVERYDAY(listOf(
        BottomNavContentTab.PASSWORDS, BottomNavContentTab.AUTHENTICATOR, BottomNavContentTab.GENERATOR,
    )),
    MINIMAL(listOf(BottomNavContentTab.PASSWORDS, BottomNavContentTab.GENERATOR));

    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        bottomNavOrder = BottomNavContentTab.sanitizeOrder(tabs),
        bottomNavVisibility = quickSetupNavigationVisibility(tabs),
        // Keep Settings reachable, including when the user later keeps just one content tab.
        autoHideBottomNavWhenSingleTab = false,
        vaultOverviewEnabled = vaultOverviewEnabled ?: settings.vaultOverviewEnabled,
        vaultV2LayoutMode = if (vaultOverviewEnabled != null) VaultV2LayoutMode.CLASSIC else settings.vaultV2LayoutMode,
        passwordPageAggregateEnabled = if (BottomNavContentTab.PASSWORDS in tabs) false else settings.passwordPageAggregateEnabled,
    )

    fun matches(settings: AppSettings): Boolean =
        settings.quickSetupVisibleTabs() == tabs &&
            !settings.autoHideBottomNavWhenSingleTab &&
            (BottomNavContentTab.PASSWORDS !in tabs || !settings.passwordPageAggregateEnabled) &&
            (vaultOverviewEnabled == null ||
                (settings.vaultOverviewEnabled == vaultOverviewEnabled && settings.vaultV2LayoutMode == VaultV2LayoutMode.CLASSIC))
}

/** Passkeys share the authenticator destination in the actual main-screen Dock. */
fun AppSettings.quickSetupTabOrder(): List<BottomNavContentTab> =
    BottomNavContentTab.sanitizeOrder(bottomNavOrder)
        .map { if (it == BottomNavContentTab.PASSKEY) BottomNavContentTab.AUTHENTICATOR else it }
        .distinct()

fun AppSettings.quickSetupVisibleTabs(): List<BottomNavContentTab> =
    quickSetupTabOrder()
        .filter { tab ->
            if (tab == BottomNavContentTab.AUTHENTICATOR) {
                bottomNavVisibility.authenticator || bottomNavVisibility.passkey
            } else bottomNavVisibility.isVisible(tab)
        }

fun quickSetupNavigationVisibility(tabs: List<BottomNavContentTab>): BottomNavVisibility {
    require(tabs.isNotEmpty()) { "At least one content page must remain visible" }
    val authenticator = BottomNavContentTab.AUTHENTICATOR in tabs || BottomNavContentTab.PASSKEY in tabs
    return BottomNavVisibility(
        vaultV2 = BottomNavContentTab.VAULT_V2 in tabs,
        passwords = BottomNavContentTab.PASSWORDS in tabs,
        authenticator = authenticator,
        cardWallet = BottomNavContentTab.CARD_WALLET in tabs,
        generator = BottomNavContentTab.GENERATOR in tabs,
        notes = BottomNavContentTab.NOTES in tabs,
        send = BottomNavContentTab.SEND in tabs,
        passkey = authenticator,
        steam = BottomNavContentTab.STEAM in tabs,
    )
}
