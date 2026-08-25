package takagi.ru.monica.keepass

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassBatchMoveTransactionGuardTest {
    @Test
    fun `same database password moves use one native database mutation`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/LocalKeePassViewModel.kt"
        ).readText()
        val body = source
            .substringAfter("suspend fun movePasswordEntriesToKdbx(")
            .substringBefore("private suspend fun copyPasswordAttachmentsToKdbx(")

        assertTrue(body.contains("workspaceRepository.moveNativeEntries("))
        assertTrue(body.contains("entryUuids = entryUuidsByPasswordId.values.toSet()"))
        assertTrue(body.contains("expectedRevisionToken = browser.sourceRevision.sha256"))
    }

    private fun projectFile(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
