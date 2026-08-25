package takagi.ru.monica.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassGroupFilterIdentityTest {
    private val filter = KeePassGroupFilterIdentity(
        databaseId = 9L,
        groupPath = "Accounts/Personal",
        groupUuid = "11111111-1111-1111-1111-111111111111"
    )

    @Test
    fun `UUID identity survives a display path rename`() {
        assertTrue(
            filter.matches(
                itemDatabaseId = 9L,
                itemGroupPath = "Renamed/Personal",
                itemGroupUuid = "11111111-1111-1111-1111-111111111111"
            )
        )
    }

    @Test
    fun `duplicate legacy paths remain distinguishable when both UUIDs are known`() {
        assertFalse(
            filter.matches(
                itemDatabaseId = 9L,
                itemGroupPath = "Accounts/Personal",
                itemGroupUuid = "22222222-2222-2222-2222-222222222222"
            )
        )
    }

    @Test
    fun `legacy rows without UUID use path fallback`() {
        assertTrue(
            filter.matches(
                itemDatabaseId = 9L,
                itemGroupPath = "Accounts/Personal",
                itemGroupUuid = null
            )
        )
    }

    @Test
    fun `database identity is always required`() {
        assertFalse(
            filter.matches(
                itemDatabaseId = 10L,
                itemGroupPath = "Accounts/Personal",
                itemGroupUuid = "11111111-1111-1111-1111-111111111111"
            )
        )
    }
}
