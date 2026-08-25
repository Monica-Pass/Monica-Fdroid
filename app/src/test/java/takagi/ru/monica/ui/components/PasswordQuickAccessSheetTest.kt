package takagi.ru.monica.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class PasswordQuickAccessSheetTest {

    @Test
    fun topHandoffConsumesOnlyTheGuardWindowAndThenLetsSheetReceiveDrag() {
        val guard = PasswordQuickAccessTopHandoffGuard(guardPx = 24f)

        assertEquals(8f, guard.consume(8f, listAtTop = true), 0.001f)
        assertEquals(8f, guard.consume(8f, listAtTop = true), 0.001f)
        assertEquals(8f, guard.consume(20f, listAtTop = true), 0.001f)
        assertEquals(24f, guard.consumedPx, 0.001f)
    }

    @Test
    fun leavingTopOrReversingDirectionResetsHandoffWindow() {
        val leavingTopGuard = PasswordQuickAccessTopHandoffGuard(guardPx = 24f)
        leavingTopGuard.consume(12f, listAtTop = true)
        assertEquals(0f, leavingTopGuard.consume(10f, listAtTop = false), 0.001f)
        assertEquals(0f, leavingTopGuard.consumedPx, 0.001f)

        val reversingGuard = PasswordQuickAccessTopHandoffGuard(guardPx = 24f)
        reversingGuard.consume(12f, listAtTop = true)
        assertEquals(0f, reversingGuard.consume(-6f, listAtTop = true), 0.001f)
        assertEquals(0f, reversingGuard.consumedPx, 0.001f)
    }

    @Test
    fun recentItemsDiscardInvalidRecordsAndUseStableTieBreak() {
        val items = listOf(
            item(id = 1L, openCount = 3, lastOpenedAt = 100L),
            item(id = 3L, openCount = 1, lastOpenedAt = 200L),
            item(id = 2L, openCount = 2, lastOpenedAt = 200L),
            item(id = 4L, openCount = 0, lastOpenedAt = 300L),
            item(id = 5L, openCount = 1, lastOpenedAt = 0L)
        )

        assertEquals(
            listOf(3L, 2L, 1L),
            rankRecentPasswordQuickAccessItems(items).map { it.entry.id }
        )
    }

    @Test
    fun frequentItemsRequireRepeatedUseAndSortByCountThenRecency() {
        val items = listOf(
            item(id = 1L, openCount = 1, lastOpenedAt = 500L),
            item(id = 2L, openCount = 4, lastOpenedAt = 100L),
            item(id = 3L, openCount = 2, lastOpenedAt = 300L),
            item(id = 4L, openCount = 4, lastOpenedAt = 200L)
        )

        assertEquals(
            listOf(4L, 2L, 3L),
            rankFrequentPasswordQuickAccessItems(items).map { it.entry.id }
        )
    }

    @Test
    fun rankingLimitIsAppliedAfterFilteringAndSorting() {
        val items = listOf(
            item(id = 1L, openCount = 2, lastOpenedAt = 100L),
            item(id = 2L, openCount = 3, lastOpenedAt = 200L),
            item(id = 3L, openCount = 4, lastOpenedAt = 300L)
        )

        assertEquals(
            listOf(3L, 2L),
            rankFrequentPasswordQuickAccessItems(items, limit = 2).map { it.entry.id }
        )
        assertTrue(rankRecentPasswordQuickAccessItems(items, limit = -1).isEmpty())
    }

    @Test
    fun identityShowsAccountBeforeNormalizedSiteAndHasFallback() {
        val entry = passwordEntry(
            id = 1L,
            username = "alice@example.com",
            website = "https://www.example.com/login"
        )

        assertEquals(
            "alice@example.com • example.com",
            passwordQuickAccessIdentity(entry)
        )
        assertEquals(
            "-",
            passwordQuickAccessIdentity(passwordEntry(id = 2L))
        )
    }

    @Test
    fun sheetKeepsCompactBoundedListAndStableLazyKeys() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/PasswordQuickAccessSheet.kt"
        ).readText()

        assertFalse(source.contains("fillMaxHeight"))
        assertFalse(source.contains("heightIn(min ="))
        assertTrue(source.contains("heightIn(max = 560.dp)"))
        assertTrue(source.contains("QUICK_ACCESS_COMPACT_LIST_LIMIT"))
        assertTrue(source.contains("activeItems.size <= QUICK_ACCESS_COMPACT_LIST_LIMIT"))
        assertTrue(source.contains("rememberLazyListState()"))
        assertTrue(source.contains("key = { it.entry.id }"))
        assertTrue(source.contains("rememberAutoMatchedSimpleIcon("))
        assertTrue(source.contains("rememberFavicon("))
        assertTrue(source.contains("contentWindowInsets = {"))
        assertTrue(source.contains("WindowInsets(0, 0, 0, 0)"))
        assertTrue(source.contains("nestedScroll(topHandoffConnection)"))
    }

    private fun item(id: Long, openCount: Int, lastOpenedAt: Long): PasswordQuickAccessItem {
        return PasswordQuickAccessItem(
            entry = passwordEntry(id = id),
            openCount = openCount,
            lastOpenedAt = lastOpenedAt
        )
    }

    private fun passwordEntry(
        id: Long,
        username: String = "",
        website: String = ""
    ): PasswordEntry {
        return PasswordEntry(
            id = id,
            title = "Entry $id",
            website = website,
            username = username,
            password = ""
        )
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
