package takagi.ru.monica.autofill_ng

import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class AutofillMetadataCacheTest {
    @Test
    fun cachedMetadataUsesCurrentCredentialsAndRanking() {
        val matcher = BitwardenLikeAutofillMatcherNg(useNativeIndex = false)
        val original = listOf(entry(1), entry(2))
        match(matcher, original)
        val updated = listOf(original[0], original[1].copy(
            password = "new-synthetic-value", username = "new account",
            isFavorite = true, updatedAt = Date(10),
        ))
        val result = match(matcher, updated)
        assertSame(updated[1], result.first())
        assertEquals("new-synthetic-value", result.first().password)
        assertEquals(2, matcher.metadataBuildCount)
    }

    @Test
    fun reorderingAndDeletingReuseMetadataWhileEditsUpdateMatches() {
        val matcher = BitwardenLikeAutofillMatcherNg(useNativeIndex = false)
        val original = List(300) { entry(it.toLong() + 1) }
        match(matcher, original)
        match(matcher, original.reversed().drop(1))
        assertEquals(300, matcher.metadataBuildCount)
        val changed = original.reversed().drop(1).map {
            if (it.id == 1L) it.copy(website = "https://other.test") else it
        } + entry(301)
        val cached = match(matcher, changed)
        val baseline = match(BitwardenLikeAutofillMatcherNg(cacheMetadata = false), changed)
        assertEquals(baseline, cached)
        assertTrue(cached.none { it.id == 1L || it.id == 300L })
        assertEquals(302, matcher.metadataBuildCount)
        match(matcher, emptyList())
        match(matcher, changed)
        assertEquals(602, matcher.metadataBuildCount)
    }

    @Test
    fun clearingDuringPreparationDoesNotBlockOrRestoreTheClearedCache() {
        val matcher = BitwardenLikeAutofillMatcherNg(useNativeIndex = false)
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val entries = listOf(entry(1))
        val paused = object : AbstractList<PasswordEntry>() {
            override val size = entries.size
            override fun get(index: Int): PasswordEntry {
                started.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                return entries[index]
            }
        }
        try {
            val running = executor.submit<List<PasswordEntry>> { match(matcher, paused) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            matcher.clear()
            resume.countDown()
            assertEquals(entries, running.get(5, TimeUnit.SECONDS))
            match(matcher, entries)
            assertEquals("A cleared in-flight snapshot must not be retained", 2, matcher.metadataBuildCount)
        } finally {
            resume.countDown()
            executor.shutdownNow()
            matcher.clear()
        }
    }

    private fun entry(id: Long) = PasswordEntry(
        id = id, title = "Synthetic $id", website = "https://example.test",
        username = "account-$id", password = "synthetic-value",
        createdAt = Date(0), updatedAt = Date(0),
    )

    private fun match(matcher: BitwardenLikeAutofillMatcherNg, entries: List<PasswordEntry>) =
        matcher.match(entries, "com.browser", "example.test",
            config = BitwardenLikeAutofillMatcherNg.Config(maxSuggestions = Int.MAX_VALUE))
}
