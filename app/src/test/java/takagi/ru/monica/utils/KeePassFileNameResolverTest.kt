package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassFileNameResolverTest {

    @Test
    fun usesProviderDisplayNameAndRemovesKeepassExtension() {
        assertEquals(
            "My Vault",
            KeePassFileNameResolver.databaseNameFromCandidates(
                displayName = "My Vault.kdbx",
                uriLastPathSegment = "document:1000097490"
            )
        )
    }

    @Test
    fun keepsUnicodeFileNames() {
        assertEquals(
            "我的密码库",
            KeePassFileNameResolver.databaseNameFromCandidates(
                displayName = "我的密码库.KDB",
                uriLastPathSegment = "document:42"
            )
        )
    }

    @Test
    fun neverUsesDocumentIdentifierAsDatabaseName() {
        assertNull(
            KeePassFileNameResolver.databaseNameFromCandidates(
                displayName = null,
                uriLastPathSegment = "document:1000097490"
            )
        )
        assertTrue(KeePassFileNameResolver.isProviderIdentifier("document:1000097490"))
    }

    @Test
    fun fallsBackToUriFileNameWhenProviderDoesNotExposeDisplayName() {
        assertEquals(
            "vault",
            KeePassFileNameResolver.databaseNameFromCandidates(
                displayName = null,
                uriLastPathSegment = "vault.kdbx"
            )
        )
    }

    @Test
    fun manualNameWinsOverProviderIdentifierOnlyWhenItIsARealName() {
        assertEquals(
            "Custom name",
            KeePassFileNameResolver.chooseImportedDatabaseName(
                requestedName = "Custom name",
                displayName = "Vault.kdbx",
                uriLastPathSegment = "document:1000097490"
            )
        )
        assertEquals(
            "Vault",
            KeePassFileNameResolver.chooseImportedDatabaseName(
                requestedName = "document:1000097490",
                displayName = "Vault.kdbx",
                uriLastPathSegment = "document:1000097490"
            )
        )
        assertEquals(
            "Vault",
            KeePassFileNameResolver.chooseImportedDatabaseName(
                requestedName = KeePassFileNameResolver.DEFAULT_DATABASE_NAME,
                displayName = "Vault.kdbx",
                uriLastPathSegment = "document:1000097490"
            )
        )
    }

    @Test
    fun displayNameHasStableFallback() {
        assertEquals(
            KeePassFileNameResolver.DEFAULT_DATABASE_NAME,
            KeePassFileNameResolver.displayFileNameFromCandidates(
                displayName = null,
                uriLastPathSegment = "document:1000097490"
            )
        )
        assertTrue(
            KeePassFileNameResolver.displayFileNameFromCandidates(
                displayName = "Vault.kdbx",
                uriLastPathSegment = "document:1000097490"
            ).endsWith(".kdbx")
        )
    }
}
