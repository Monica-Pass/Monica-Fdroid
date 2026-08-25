package takagi.ru.monica.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry

class PasswordDisplayDeduplicationRegressionTest {

    @Test
    fun independentLocalRowsDoNotShareAReplicaIdentity() {
        val first = PasswordEntry(id = 101, title = "same", website = "example.com", username = "u", password = "p")
        val second = first.copy(id = 102)

        assertNull(passwordDisplayStableIdentityKey(first))
        assertNull(passwordDisplayStableIdentityKey(second))
    }

    @Test
    fun explicitReplicaRowsShareIdentityForAllViewCollapse() {
        val first = PasswordEntry(
            id = 101,
            title = "same",
            website = "example.com",
            username = "u",
            password = "p",
            replicaGroupId = "replica-1"
        )
        val second = first.copy(id = 102)

        assertEquals(
            passwordDisplayStableIdentityKey(first),
            passwordDisplayStableIdentityKey(second)
        )
        assertTrue(passwordDisplayStableIdentityKey(first).orEmpty().startsWith("replica:"))
    }

    @Test
    fun displayDedupeKeepsIndependentLocalRowsButCollapsesReplicas() {
        val localFirst = PasswordEntry(id = 101, title = "same", website = "example.com", username = "u", password = "p")
        val localSecond = localFirst.copy(id = 102)
        val replicaFirst = localFirst.copy(id = 201, replicaGroupId = "replica-1")
        val replicaSecond = localFirst.copy(
            id = 202,
            replicaGroupId = "replica-1",
            mdbxDatabaseId = 7L
        )

        val rows = listOf(
            localFirst to "plain",
            localSecond to "plain",
            replicaFirst to "plain",
            replicaSecond to "plain"
        )
        val result = dedupePasswordDisplayRows(rows) { candidates -> candidates.firstOrNull() }

        assertEquals(3, result.size)
        assertTrue(result.any { it.id == localFirst.id })
        assertTrue(result.any { it.id == localSecond.id })
    }

    @Test
    fun sameTargetMultiPasswordSiblingsRemainVisibleEvenWhenReplicaGroupMatches() {
        val first = PasswordEntry(
            id = 301,
            title = "same",
            website = "example.com",
            username = "u",
            password = "p",
            replicaGroupId = "replica-2"
        )
        val second = first.copy(id = 302, password = "p")
        val result = dedupePasswordDisplayRows(
            listOf(first to "plain", second to "plain")
        ) { candidates -> candidates.firstOrNull() }

        assertEquals(2, result.size)
    }

    @Test
    fun generatorDoesNotBlockSavingAnIdenticalPassword() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/GeneratorScreen.kt").readText()

        assertTrue(source.contains("enabled = !saved"))
        assertTrue(source.contains("passwordViewModel.addPasswordEntry(entry)"))
        assertTrue(!source.contains("if (!alreadyExists)"))
    }

    private fun projectFile(relativePath: String): java.io.File {
        var directory = java.io.File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !java.io.File(directory, "settings.gradle").exists() &&
            !java.io.File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return java.io.File(directory, relativePath)
    }
}
