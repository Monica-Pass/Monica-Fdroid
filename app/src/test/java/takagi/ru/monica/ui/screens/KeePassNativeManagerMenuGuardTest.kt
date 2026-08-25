package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassNativeManagerMenuGuardTest {

    @Test
    fun `password top menu exposes native manager for the selected keepass database`() {
        val menu = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordTopActionsMenu.kt",
        ).readText()
        val topSection = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListTopSection.kt",
        ).readText()
        val activity = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt",
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/LocalKeePassViewModel.kt",
        ).readText()

        assertTrue(menu.contains("KeepassNativeManagerTopActionsMenuItem"))
        assertTrue(topSection.contains("onOpenKeePassNativeManager"))
        assertTrue(topSection.contains("KeepassNativeManagerTopActionsMenuItem"))
        assertTrue(viewModel.contains("nativeManagerOpenRequests"))
        assertTrue(activity.contains("localKeePassViewModel.nativeManagerOpenRequests.collect"))
        assertTrue(activity.contains("navController.navigate(Screen.LocalKeePass.route)"))
    }

    @Test
    fun `vault top menu keeps the same manager shortcut`() {
        val vault = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt",
        ).readText()

        assertTrue(vault.contains("onOpenKeePassNativeManager"))
        assertTrue(vault.contains("KeepassNativeManagerTopActionsMenuItem"))
    }

    @Test
    fun `manager shortcut is translated in every bundled language`() {
        val resourceFiles = listOf(
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values-zh/strings.xml",
            "app/src/main/res/values-de/strings.xml",
            "app/src/main/res/values-es/strings.xml",
            "app/src/main/res/values-ko/strings.xml",
        )

        resourceFiles.forEach { path ->
            assertTrue(
                "$path should translate keepass_native_open_manager",
                projectFile(path).readText().contains("<string name=\"keepass_native_open_manager\">"),
            )
        }
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
