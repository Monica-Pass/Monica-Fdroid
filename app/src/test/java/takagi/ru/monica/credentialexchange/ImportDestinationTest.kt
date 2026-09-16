package takagi.ru.monica.credentialexchange

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*

class ImportDestinationTest {
    @Test fun importedPasswordsLoseEveryOldStorageIdentityButKeepTheirContent() {
        val original = PasswordEntry(id = 77, title = "test", website = "https://example.com", username = "alice", password = "secret",
            keepassDatabaseId = 9, keepassEntryUuid = "old", mdbxDatabaseId = 8, mdbxFolderId = "folder",
            bitwardenVaultId = 7, bitwardenCipherId = "old-cipher", bitwardenRevisionDate = "old-revision", replicaGroupId = "replica",
            categoryId = 12, ssoRefEntryId = 999, boundNoteId = 222, passkeyBindings = "old")
        val destination = ImportDestination(ImportDestinationKind.KEEPASS, 3)
        val mapped = destination.password(original)
        assertEquals(0L, mapped.id)
        assertEquals(3L, mapped.keepassDatabaseId)
        assertNotEquals("old", mapped.keepassEntryUuid)
        assertNull(mapped.mdbxDatabaseId); assertNull(mapped.bitwardenVaultId); assertNull(mapped.bitwardenCipherId)
        assertNull(mapped.bitwardenRevisionDate); assertNull(mapped.replicaGroupId); assertNull(mapped.categoryId)
        assertNull(mapped.boundNoteId); assertNull(mapped.ssoRefEntryId); assertEquals("", mapped.passkeyBindings)
        assertEquals(original.password, mapped.password); assertEquals(original.website, mapped.website)
    }

    @Test fun bitwardenImportCreatesPendingNativeCredentialsWithoutReusingTheCipher() {
        val destination = ImportDestination(ImportDestinationKind.BITWARDEN, 42)
        val source = PasskeyEntry(credentialId = "AQID", rpId = "example.com", rpName = "Example", userId = "AQ", userName = "alice",
            userDisplayName = "Alice", publicKey = "public", privateKeyAlias = "private", bitwardenCipherId = "old",
            keepassDatabaseId = 7, mdbxDatabaseId = 9, signCount = 5)
        val mapped = destination.passkey(source)
        assertEquals("AQID", mapped.credentialId); assertEquals("private", mapped.privateKeyAlias)
        assertEquals(42L, mapped.bitwardenVaultId); assertNull(mapped.bitwardenCipherId)
        assertNull(mapped.keepassDatabaseId); assertNull(mapped.mdbxDatabaseId)
        assertEquals("PENDING", mapped.syncStatus)
        assertEquals(PasskeyEntry.MODE_BW_COMPAT, mapped.passkeyMode)
        assertEquals("Routing must never reset old counts", 5L, mapped.signCount)
    }

    @Test fun deduplicationScopesAreMutuallyExclusiveAndRejectConflictedBindings() {
        val entry = PasswordEntry(title = "test", username = "a", password = "b", website = "", mdbxDatabaseId = 5)
        assertTrue(ImportDestination(ImportDestinationKind.MDBX, 5).contains(entry))
        assertFalse(ImportDestination.Local.contains(entry))
        assertFalse(ImportDestination(ImportDestinationKind.MDBX, 6).contains(entry))
        assertFalse(ImportDestination(ImportDestinationKind.MDBX, 5).contains(entry.copy(bitwardenVaultId = 4)))
    }

    @Test fun serializedDestinationRetainsItsDatabaseAcrossRecreation() {
        ImportDestinationKind.entries.forEach { kind ->
            val target = ImportDestination(kind, if (kind == ImportDestinationKind.LOCAL) 0 else 123)
            assertEquals(target, ImportDestination.fromKey(target.key))
        }
        assertTrue(runCatching { ImportDestination(ImportDestinationKind.LOCAL, 99) }.isFailure)
        assertTrue(runCatching { ImportDestination(ImportDestinationKind.KEEPASS, -1) }.isFailure)
    }
}
