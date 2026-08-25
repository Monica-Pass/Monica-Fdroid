package takagi.ru.monica.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbxHealthDiagnosticIntegrationGuardTest {

    @Test
    fun mdbx2RepositoryPreservesNativeIssueCategorySeverityAndDescription() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/Mdbx2Repository.kt"
        ).readText()

        assertTrue(source.contains("val healthIssues = health.issues.map"))
        assertTrue(source.contains("NativeMdbxHealthIssueSeverity.INFO -> MdbxHealthSeverity.INFO"))
        assertTrue(source.contains("NativeMdbxHealthIssueSeverity.WARNING -> MdbxHealthSeverity.WARNING"))
        assertTrue(source.contains("NativeMdbxHealthIssueSeverity.ERROR -> MdbxHealthSeverity.ERROR"))
        assertTrue(source.contains("NativeMdbxHealthIssueSeverity.CRITICAL -> MdbxHealthSeverity.CRITICAL"))
        assertTrue(source.contains("category = issue.category"))
        assertTrue(source.contains("description = issue.description"))
        assertTrue(source.contains("healthIssues = healthIssues"))
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            candidates += File(directory, relativePath)
            directory = directory.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath")
    }
}
