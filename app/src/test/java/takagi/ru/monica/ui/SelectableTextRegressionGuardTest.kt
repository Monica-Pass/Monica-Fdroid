package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectableTextRegressionGuardTest {

    @Test
    fun noteDetailMarkdownIsSelectableInViewMode() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteDetailScreen.kt"
        ).readText()

        assertTrue(
            Regex(
                "SelectionContainer\\s*\\{\\s*MarkdownPreviewText\\(",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(source)
        )
    }

    @Test
    fun passwordDetailNotesAreSelectableInViewMode() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/PasswordDetailScreen.kt"
        ).readText()
        val notesCard = source.substringAfter("private fun NotesCard(notes: String)")
            .substringBefore("private fun BoundNoteCard(")

        assertTrue(
            Regex(
                "SelectionContainer\\s*\\{\\s*Text\\(\\s*text\\s*=\\s*notes",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(notesCard)
        )
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }
}
