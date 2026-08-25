package takagi.ru.monica.ui.vaultv2

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType

class VaultV2BatchMovePlanTest {

    @Test
    fun `batch plan preserves every selectable vault item by its domain type`() {
        val password = VaultV2Item(
            key = "password:1",
            type = VaultV2ItemType.PASSWORD,
            title = "Password",
            subtitle = "",
            isFavorite = false,
            sortKey = "password",
            searchableValues = emptyList(),
            passwordEntry = PasswordEntry(
                id = 1L,
                title = "Password",
                website = "",
                username = "",
                password = ""
            )
        )
        val totp = SecureItem(
            id = 2L,
            itemType = ItemType.TOTP,
            title = "Authenticator",
            itemData = "{}"
        )
        val note = SecureItem(
            id = 3L,
            itemType = ItemType.NOTE,
            title = "Note",
            itemData = ""
        )
        val passkey = PasskeyEntry(
            id = 4L,
            credentialId = "credential",
            rpId = "example.com",
            rpName = "Example",
            userId = "user-id",
            userName = "user",
            userDisplayName = "User",
            publicKey = "public-key",
            privateKeyAlias = "private-key"
        )

        val plan = buildVaultV2BatchMovePlan(
            listOf(
                password,
                VaultV2Item(
                    key = "totp:2",
                    type = VaultV2ItemType.AUTHENTICATOR,
                    title = "Authenticator",
                    subtitle = "",
                    isFavorite = false,
                    sortKey = "totp",
                    searchableValues = emptyList(),
                    totpItem = totp
                ),
                VaultV2Item(
                    key = "note:3",
                    type = VaultV2ItemType.NOTE,
                    title = "Note",
                    subtitle = "",
                    isFavorite = false,
                    sortKey = "note",
                    searchableValues = emptyList(),
                    secureItem = note
                ),
                VaultV2Item(
                    key = "passkey:4",
                    type = VaultV2ItemType.PASSKEY,
                    title = "Example",
                    subtitle = "",
                    isFavorite = false,
                    sortKey = "passkey",
                    searchableValues = emptyList(),
                    passkeyEntry = passkey
                )
            )
        )

        assertEquals(listOf(password.passwordEntry), plan.passwordEntries)
        assertEquals(listOf(totp), plan.aggregateSelection.totpItems)
        assertEquals(listOf(note), plan.aggregateSelection.notes)
        assertEquals(listOf(passkey), plan.aggregateSelection.passkeys)
        assertEquals(4, plan.totalCount)
    }

    @Test
    fun `vault move reuses the unified batch executor and publishes breadcrumb progress`() {
        val source = source("ui/vaultv2/VaultV2Pane.kt")
        val moveHandler = source
            .substringAfter("onTargetSelected = { target, _ ->")
            .substringBefore("if (showCreateCategoryDialog)")

        assertTrue(moveHandler.contains("buildVaultV2BatchMovePlan(selectedItems.toList())"))
        assertTrue(moveHandler.contains("executeMixedPasswordBatchMove("))
        assertTrue(moveHandler.contains("PasswordBatchTransferProgressTracker.update("))
        assertTrue(moveHandler.contains("passwordViewModel.viewModelScope.launch"))
        assertFalse(moveHandler.contains("passwordViewModel.movePasswordsToCategory("))
        assertFalse(moveHandler.contains("totpViewModel.moveToCategory("))
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(
            directory,
            "app/src/main/java/takagi/ru/monica/$relativePath"
        ).readText()
    }
}
