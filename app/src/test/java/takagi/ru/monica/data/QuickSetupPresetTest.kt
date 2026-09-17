package takagi.ru.monica.data

import org.junit.Assert.*
import org.junit.Test

class QuickSetupPresetTest {
    @Test fun requestedPresetsHaveTheExpectedDestinations() {
        val expected = mapOf(
            QuickSetupPreset.BITWARDEN_PREVIEW to listOf(BottomNavContentTab.VAULT_V2, BottomNavContentTab.SEND, BottomNavContentTab.GENERATOR),
            QuickSetupPreset.BITWARDEN_LIST to listOf(BottomNavContentTab.VAULT_V2, BottomNavContentTab.SEND, BottomNavContentTab.GENERATOR),
            QuickSetupPreset.AUTHENTICATOR to listOf(BottomNavContentTab.AUTHENTICATOR, BottomNavContentTab.STEAM),
            QuickSetupPreset.PAGED to listOf(BottomNavContentTab.PASSWORDS, BottomNavContentTab.AUTHENTICATOR, BottomNavContentTab.CARD_WALLET, BottomNavContentTab.NOTES),
            QuickSetupPreset.EVERYDAY to listOf(BottomNavContentTab.PASSWORDS, BottomNavContentTab.AUTHENTICATOR, BottomNavContentTab.GENERATOR),
            QuickSetupPreset.MINIMAL to listOf(BottomNavContentTab.PASSWORDS, BottomNavContentTab.GENERATOR),
        )
        expected.forEach { (preset, tabs) ->
            val settings = preset.applyTo(AppSettings())
            assertEquals(preset.name, tabs, settings.quickSetupVisibleTabs())
            assertFalse(settings.autoHideBottomNavWhenSingleTab)
            assertTrue(preset.matches(settings))
        }
        assertTrue(QuickSetupPreset.BITWARDEN_PREVIEW.applyTo(AppSettings()).vaultOverviewEnabled)
        assertFalse(QuickSetupPreset.BITWARDEN_LIST.applyTo(AppSettings()).vaultOverviewEnabled)
    }

    @Test fun switchingPresetsDoesNotLeakPreviousTabsOrAggregateMode() {
        for (first in QuickSetupPreset.entries) for (second in QuickSetupPreset.entries) {
            val initial = AppSettings(passwordPageAggregateEnabled = true, autoHideBottomNavWhenSingleTab = true,
                vaultV2LayoutMode = VaultV2LayoutMode.HIERARCHICAL)
            val selected = second.applyTo(first.applyTo(initial))
            assertEquals(second.tabs, selected.quickSetupVisibleTabs())
            assertTrue(second.matches(selected))
            assertEquals(BottomNavContentTab.entries.toSet(), selected.bottomNavOrder.toSet())
            assertEquals(selected.bottomNavOrder.size, selected.bottomNavOrder.distinct().size)
        }
    }

    @Test fun presetsLeaveSecurityLanguageAndPersonalChoicesIntact() {
        val original = AppSettings(language = Language.POLISH, biometricEnabled = true,
            autoLockMinutes = 1, screenshotProtectionEnabled = true, autofillAuthRequired = true,
            isPlusActivated = true, colorScheme = ColorScheme.FOREST_GREEN, quickSetupCompleted = true,
            passwordCardDisplayFields = listOf(PasswordCardDisplayField.WEBSITE))
        QuickSetupPreset.entries.forEach { preset ->
            val selected = preset.applyTo(original)
            assertEquals(original.language, selected.language)
            assertEquals(original.biometricEnabled, selected.biometricEnabled)
            assertEquals(original.autoLockMinutes, selected.autoLockMinutes)
            assertEquals(original.screenshotProtectionEnabled, selected.screenshotProtectionEnabled)
            assertEquals(original.autofillAuthRequired, selected.autofillAuthRequired)
            assertEquals(original.isPlusActivated, selected.isPlusActivated)
            assertEquals(original.colorScheme, selected.colorScheme)
            assertEquals(original.quickSetupCompleted, selected.quickSetupCompleted)
            assertEquals(original.passwordCardDisplayFields, selected.passwordCardDisplayFields)
        }
    }

    @Test fun legacyPasskeyTabIsRepresentedByTheAuthenticatorAndEmptyDocksAreRejected() {
        val settings = AppSettings(
            bottomNavOrder = listOf(BottomNavContentTab.PASSKEY, BottomNavContentTab.AUTHENTICATOR),
            bottomNavVisibility = BottomNavVisibility(passwords = false, authenticator = false,
                cardWallet = false, notes = false, passkey = true),
        )
        assertEquals(listOf(BottomNavContentTab.AUTHENTICATOR), settings.quickSetupVisibleTabs())
        assertThrows(IllegalArgumentException::class.java) { quickSetupNavigationVisibility(emptyList()) }
        val chosen = QuickSetupPreset.BITWARDEN_LIST.applyTo(AppSettings())
        assertFalse(QuickSetupPreset.BITWARDEN_LIST.matches(chosen.copy(bottomNavOrder = chosen.bottomNavOrder.reversed())))
        assertFalse(QuickSetupPreset.BITWARDEN_PREVIEW.matches(chosen))
    }
}
