package takagi.ru.monica.data.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class DedupPasswordIdentityTest {
    @Test
    fun equivalentUrlsAndCaseProduceSameIdentity() {
        val first = password(1, "https://www.Example.com/", "User@example.com")
        val second = password(2, "example.com", " user@EXAMPLE.com ")

        assertEquals(DedupPasswordIdentity.key(first), DedupPasswordIdentity.key(second))
    }

    @Test
    fun differentAccountsDoNotMerge() {
        val first = password(1, "example.com", "first")
        val second = password(2, "example.com", "second")

        assertNotEquals(DedupPasswordIdentity.key(first), DedupPasswordIdentity.key(second))
    }

    @Test
    fun urlNormalizationKeepsMeaningfulPath() {
        val first = password(1, "https://example.com/account", "user")
        val second = password(2, "https://example.com/admin", "user")

        assertNotEquals(DedupPasswordIdentity.key(first), DedupPasswordIdentity.key(second))
    }

    private fun password(id: Long, website: String, username: String) = PasswordEntry(
        id = id,
        title = "Example",
        website = website,
        username = username,
        password = "secret"
    )
}
