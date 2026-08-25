package takagi.ru.monica.viewmodel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordFlowSharingPerformanceGuardTest {

    @Test
    fun passwordReadyAndListStatesShareTheirTransformedUpstream() {
        val source = passwordViewModelSource()

        assertTrue(source.contains("private val sharedPasswordEntriesSource"))
        assertTrue(source.contains("passwordEntriesSource.shareIn("))
        assertTrue(source.contains("private val sharedAllPasswordsSource"))
        assertTrue(source.contains("private val sharedAllPasswordsForUiSource"))
        assertTrue(source.contains("private val sharedArchivedPasswordsForUiSource"))

        assertEquals(1, source.countOccurrences("passwordEntriesSource.shareIn("))
        assertFalse(
            source.substringAfter("val passwordEntriesReady:")
                .substringBefore("private val sharedAllPasswordsSource")
                .contains("passwordEntriesSource\n")
        )
    }

    @Test
    fun allAndArchivedRoomFlowsAreSharedAcrossUiAndDecryptedConsumers() {
        val source = passwordViewModelSource()
        val flowSection = source.substringAfter("private val rawAllPasswordsSource")
            .substringBefore("private fun dedupeSmart(")

        assertEquals(1, flowSection.countOccurrences("repository.getAllPasswordEntries()"))
        assertEquals(1, flowSection.countOccurrences("repository.getArchivedEntries()"))
        assertTrue(flowSection.contains("rawAllPasswordsSource"))
        assertTrue(flowSection.contains("rawArchivedPasswordsSource"))
    }

    private fun passwordViewModelSource(): String = projectFile(
        "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
    ).readText()

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length, step = 1, partialWindows = false).count { it == value }

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
