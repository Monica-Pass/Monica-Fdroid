package takagi.ru.monica.keepass

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeePassDatabaseAccessPolicyTest {
    @Test
    fun `read-only database rejects shared write boundary`() {
        val policy = InMemoryKeePassDatabaseAccessPolicy()
        policy.setReadOnly(DATABASE_ID, true)

        assertTrue(policy.isReadOnly(DATABASE_ID))
        try {
            policy.requireWritable(DATABASE_ID)
            fail("Expected a read-only rejection")
        } catch (error: KeePassDatabaseReadOnlyException) {
            assertEquals(DATABASE_ID, error.databaseId)
        }
    }

    @Test
    fun `writable database passes shared write boundary`() {
        val policy = InMemoryKeePassDatabaseAccessPolicy()

        policy.requireWritable(DATABASE_ID)

        assertFalse(policy.isReadOnly(DATABASE_ID))
    }

    @Test
    fun `runtime lock clears every decoded cache layer`() {
        val calls = mutableListOf<String>()
        val lock = KeePassDatabaseRuntimeLock(
            loadedDatabaseInvalidator = { calls += "loaded:$it" },
            nativeSessionInvalidator = { calls += "session:$it" },
            projectionInvalidator = { calls += "projection:$it" },
            activeDatabaseClearer = { calls += "active:$it" }
        )

        lock.lock(DATABASE_ID)

        assertEquals(
            listOf(
                "loaded:$DATABASE_ID",
                "session:$DATABASE_ID",
                "projection:$DATABASE_ID",
                "active:$DATABASE_ID"
            ),
            calls
        )
    }

    @Test
    fun `service enforces read-only at the shared encoded write boundary`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()
        val writeMethod = source
            .substringAfter("private suspend fun writeDatabase")
            .substringBefore("private fun keePassPendingChangeRepository")
        val guard = writeMethod.indexOf("accessPolicy.requireWritable(database.id)")
        // The write path now encodes to a temporary artifact, but the access
        // guard must still run before any potentially expensive encoding work.
        val encode = writeMethod.indexOf("encodeDatabaseArtifact")

        assertTrue(guard >= 0)
        assertTrue(encode > guard)
    }

    @Test
    fun `process lock invalidates session projection and active cache state`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()
        val invalidation = source
            .substringAfter("fun invalidateProcessCache")
            .substringBefore("fun markDatabaseActive")

        assertTrue(invalidation.contains("loadedDatabaseCache.remove(databaseId)"))
        assertTrue(invalidation.contains("nativeSessionCache.invalidate(databaseId)"))
        assertTrue(invalidation.contains("nativeProjectionBundleCache.invalidate(databaseId)"))
        assertTrue(invalidation.contains("projectionIndexGate.invalidate(databaseId)"))
        assertTrue(invalidation.contains("activeDatabaseId = null"))
    }

    private fun projectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Unable to find project file: $relativePath")
    }

    private companion object {
        const val DATABASE_ID = 42L
    }
}
