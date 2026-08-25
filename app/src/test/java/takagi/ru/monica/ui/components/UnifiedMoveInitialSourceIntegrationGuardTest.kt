package takagi.ru.monica.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedMoveInitialSourceIntegrationGuardTest {

    @Test
    fun `every list move picker receives the currently browsed database`() {
        val expectedBindings = mapOf(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListContent.kt" to
                "initialSource = currentFilter.toUnifiedMoveInitialSource()",
            "app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt" to
                "initialSource = selectedCategoryFilter.toUnifiedMoveInitialSource()",
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt" to
                "initialSource = selectedUnifiedFilter.toUnifiedMoveInitialSource()",
            "app/src/main/java/takagi/ru/monica/ui/screens/PasskeyListScreen.kt" to
                "initialSource = selectedCategoryFilter.toUnifiedMoveInitialSource()",
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt" to
                "initialSource = totpSelectedFilter.toUnifiedMoveInitialSource()",
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt" to
                "initialSource = storageSelection.toUnifiedMoveInitialSource()",
        )

        expectedBindings.forEach { (relativePath, expectedBinding) ->
            val source = projectFile(relativePath).readText()
            assertTrue(
                "$relativePath must initialize its move picker from the current database filter.",
                source.contains(expectedBinding),
            )
        }
    }

    @Test
    fun `move picker keeps the target empty while applying its initial database`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/UnifiedMoveToCategoryBottomSheet.kt",
        ).readText()

        assertTrue(
            source.contains("initialSource: UnifiedMoveInitialSource = UnifiedMoveInitialSource.MonicaLocal"),
        )
        assertTrue(
            source.contains("val selectedTarget = remember { mutableStateOf<UnifiedMoveCategoryTarget?>(null) }"),
        )
        assertTrue(
            source.contains("resolveUnifiedMoveInitialSourceKey(initialSource, sourceKeys)"),
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            candidates += File(directory, relativePath)
            directory = directory.parentFile
        }

        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to find project file: $relativePath")
    }
}
