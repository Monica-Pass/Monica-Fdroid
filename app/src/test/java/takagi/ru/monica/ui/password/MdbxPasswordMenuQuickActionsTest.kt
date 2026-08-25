package takagi.ru.monica.ui.password

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MdbxPasswordMenuQuickActionsTest {

    @Test
    fun sharedMenuDefinesSnapshotAndCommitHistoryActions() {
        val menuSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordTopActionsMenu.kt"
        ).readText()

        assertTrue(menuSource.contains("internal fun MdbxCreateSnapshotTopActionsMenuItem("))
        assertTrue(menuSource.contains("internal fun MdbxCommitHistoryTopActionsMenuItem("))
        assertTrue(menuSource.contains("R.string.mdbx_create_snapshot_menu"))
        assertTrue(menuSource.contains("R.string.mdbx_commit_history_menu"))
    }

    @Test
    fun bothPasswordMenusGateQuickActionsBySelectedMdbxCapabilities() {
        val passwordTopSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListTopSection.kt"
        ).readText()
        val vaultSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readText()

        listOf(passwordTopSource, vaultSource).forEach { source ->
            assertTrue(source.contains("MdbxCapability.SNAPSHOTS"))
            assertTrue(source.contains("MdbxCapability.DELTA_HISTORY"))
            assertTrue(source.contains("MdbxCreateSnapshotTopActionsMenuItem("))
            assertTrue(source.contains("MdbxCommitHistoryTopActionsMenuItem("))
            assertTrue(source.contains("createQuickSnapshot("))
            assertTrue(source.contains("onOpenMdbxCommitHistory("))
            assertTrue(source.contains("clearOperationState()"))
        }
    }

    @Test
    fun quickSnapshotUsesTimestampAndReportsCompletion() {
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()

        assertTrue(viewModelSource.contains("fun createQuickSnapshot("))
        assertTrue(viewModelSource.contains("SimpleDateFormat("))
        assertTrue(viewModelSource.contains("R.string.mdbx_quick_snapshot_name"))
        assertTrue(viewModelSource.contains("onResult: ((Result<MdbxSnapshotSummary>) -> Unit)?"))
        assertTrue(viewModelSource.contains("onResult?.invoke(Result.success(snapshot))"))
        assertTrue(viewModelSource.contains("onResult?.invoke(Result.failure(e))"))
    }

    @Test
    fun commitHistoryShortcutUsesParameterizedManagerRouteAndDirectBack() {
        val screensSource = projectFile(
            "app/src/main/java/takagi/ru/monica/navigation/Screens.kt"
        ).readText()
        val mainSource = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readText()
        val managerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()

        assertTrue(screensSource.contains("const val PAGE_COMMIT_HISTORY = \"commit_history\""))
        assertTrue(screensSource.contains("fun createRoute(databaseId: Long? = null, page: String"))
        assertTrue(mainSource.contains("Screen.MdbxManager.routePattern"))
        assertTrue(mainSource.contains("Screen.MdbxManager.createRoute("))
        assertTrue(managerSource.contains("initialDatabaseId: Long? = null"))
        assertTrue(managerSource.contains("initialPage: MdbxManagerInitialPage"))
        assertTrue(managerSource.contains("openedFromCommitHistoryShortcut"))
        assertTrue(managerSource.contains("viewModel.showDeltaHistory(database)"))
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
