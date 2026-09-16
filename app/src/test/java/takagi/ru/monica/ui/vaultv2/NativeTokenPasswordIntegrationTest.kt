package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.NativeApiTokenSummary
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageAggregateStackEntry
import takagi.ru.monica.ui.password.getPasswordInfoKey

class NativeTokenPasswordIntegrationTest {
    private val token = buildVaultV2NativeTokenItems(listOf(NativeApiTokenSummary(1, "id-a", "folder", "Work", "Token")), "API token").single()
    private val password = buildVaultV2PasswordItems(listOf(PasswordEntry(id = 4, title = "Password", website = "", username = "work", password = "encrypted"))).single()

    @Test fun tokenUsesPasswordTypeButNeverEntersTheOrdinaryTransferPlan() {
        assertEquals(VaultV2ItemType.PASSWORD, token.type)
        assertEquals("id-a", token.nativeToken?.entryId)
        val plan = buildVaultV2BatchMovePlan(listOf(token, password))
        assertEquals(listOf(password.passwordEntry), plan.passwordEntries)
    }

    @Test fun mixedManualStackRetainsOrderAndScrollbarCountsActualRows() {
        val membership = listOf(PasswordPageAggregateStackEntry(token.key, "group", 0), PasswordPageAggregateStackEntry(password.key, "group", 1))
        val sections = buildVaultV2StackedSections(listOf(password, token), membership, emptyMap(), emptySet(), "none", "strict", false)
        val group = sections.single().second.single()
        assertEquals(listOf(token.key, password.key), group.stackedItems.map { it.key })
        assertEquals(1, buildVaultV2SectionLayouts(sections, 1).single().items.size)
        assertEquals(2, buildVaultV2SectionLayouts(sections, 1).single().firstItemLazyIndex)
        val expanded = buildVaultV2StackedSections(listOf(password, token), membership, emptyMap(), emptySet(), "none", "strict", true)
        assertEquals(2, expanded.sumOf { it.second.size })
    }

    @Test fun sameTitleTokensAreNotMergedAsEquivalentCredentials() {
        val other = token.nativeToken!!.copy(entryId = "id-b").asPasswordCard("API token")
        assertNotEquals(getPasswordInfoKey(token.passwordEntry!!), getPasswordInfoKey(other))
    }
}
