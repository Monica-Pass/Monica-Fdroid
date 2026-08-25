package takagi.ru.monica.ui.vaultv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class VaultV2ImmediatePasswordMergeTest {

    @Test
    fun `new password is visible before secondary content finishes rebuilding`() {
        val entry = password(id = 1L, title = "New account")

        val result = mergeVaultV2ImmediatePasswordItems(
            computedItems = emptyList(),
            currentPasswordItems = buildVaultV2PasswordItems(listOf(entry))
        )

        assertEquals(listOf("password:1"), result.map { it.key })
        assertSame(entry, result.single().passwordEntry)
    }

    @Test
    fun `latest password replaces stale snapshot without duplication`() {
        val stale = buildVaultV2PasswordItems(listOf(password(1L, "Old title"))).single()
        val latestEntry = password(1L, "New title")

        val result = mergeVaultV2ImmediatePasswordItems(
            computedItems = listOf(stale, note("note:9")),
            currentPasswordItems = buildVaultV2PasswordItems(listOf(latestEntry))
        )

        assertEquals(2, result.size)
        assertEquals("New title", result.single { it.key == "password:1" }.title)
        assertEquals(1, result.count { it.key == "password:1" })
    }

    @Test
    fun `deleted password does not remain in retained snapshot`() {
        val stale = buildVaultV2PasswordItems(listOf(password(1L, "Deleted"))).single()

        val result = mergeVaultV2ImmediatePasswordItems(
            computedItems = listOf(stale, note("note:9")),
            currentPasswordItems = emptyList()
        )

        assertEquals(listOf("note:9"), result.map { it.key })
    }

    @Test
    fun `standalone authenticator is hidden immediately when its password appears`() {
        val authenticator = VaultV2Item(
            key = "totp:7",
            type = VaultV2ItemType.AUTHENTICATOR,
            title = "OTP",
            subtitle = "account",
            isFavorite = false,
            sortKey = "OTP",
            searchableValues = listOf("OTP"),
            boundPasswordId = 1L
        )

        val result = mergeVaultV2ImmediatePasswordItems(
            computedItems = listOf(authenticator),
            currentPasswordItems = buildVaultV2PasswordItems(listOf(password(1L, "Account")))
        )

        assertFalse(result.any { it.key == "totp:7" })
        assertEquals(listOf("password:1"), result.map { it.key })
    }

    private fun password(id: Long, title: String) = PasswordEntry(
        id = id,
        title = title,
        website = "example.com",
        username = "user",
        password = "secret"
    )

    private fun note(key: String) = VaultV2Item(
        key = key,
        type = VaultV2ItemType.NOTE,
        title = "Note",
        subtitle = "-",
        isFavorite = false,
        sortKey = "Note",
        searchableValues = listOf("Note")
    )
}
