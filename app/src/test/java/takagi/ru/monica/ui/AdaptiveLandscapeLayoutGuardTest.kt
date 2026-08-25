package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLandscapeLayoutGuardTest {

    @Test
    fun vaultAndSteamReceiveTheExistingWindowWidthContract() {
        val mainScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()
        val steamSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readText()

        assertTrue(mainScreenSource.contains("VaultV2TabPane("))
        assertTrue(mainScreenSource.contains("isCompactWidth = isCompactWidth"))
        assertTrue(mainScreenSource.contains("wideListPaneWidth = wideListPaneWidth"))
        assertTrue(steamSource.contains("isCompactWidth: Boolean"))
        assertTrue(steamSource.contains("wideListPaneWidth: Dp"))
    }

    @Test
    fun vaultWideLayoutOwnsBothTheListAndTheDetailPane() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2TabPane.kt"
        ).readText()

        assertTrue(source.contains("if (isCompactWidth)"))
        assertTrue(source.contains(".width(wideListPaneWidth)"))
        assertTrue(source.contains("VaultV2DetailPaneContent("))
        assertTrue(source.contains("DetailPane("))
    }

    @Test
    fun steamKeepsCompactNavigationAndUsesListDetailForWideTokenPages() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readText()

        assertTrue(source.contains("SteamAdaptiveContent("))
        assertTrue(source.contains("SteamWideCodeContent("))
        assertTrue(source.contains(".width(wideListPaneWidth)"))
        assertTrue(source.contains("if (isCompactWidth)"))
    }

    @Test
    fun vaultWideDetailOccupancyParticipatesInFabVisibility() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/MainScreenFab.kt"
        ).readText()

        assertTrue(source.contains("BottomNavItem.VaultV2 -> vaultV2HasWideDetail"))
    }

    @Test
    fun vaultWideHistoryUsesTheFullTabSurfaceInsteadOfTheListColumn() {
        val mainScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()
        val vaultSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readText()

        assertTrue(mainScreenSource.contains("if (passwordHistoryPageMode.isVisible)"))
        assertTrue(mainScreenSource.contains("useEmbeddedHistoryPages = isCompactWidth"))
        assertTrue(vaultSource.contains("useEmbeddedHistoryPages: Boolean = true"))
        assertTrue(vaultSource.contains("onOpenHistory"))
        assertTrue(vaultSource.contains("onOpenTrashPage"))
    }

    @Test
    fun vaultRoutesEveryAggregatedEntryTypeToTheWideDetailHost() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt"
        ).readText()

        assertTrue(source.contains("onOpenPassword = handlePasswordDetailOpen"))
        assertTrue(source.contains("onOpenTotp = handleTotpOpen"))
        assertTrue(source.contains("onOpenBankCard = handleBankCardOpen"))
        assertTrue(source.contains("onOpenDocument = handleDocumentOpen"))
        assertTrue(source.contains("onOpenNote = { handleNoteOpen(it) }"))
        assertTrue(source.contains("onOpenPasskey = handleVaultV2PasskeyOpen"))
        assertTrue(source.contains("VaultV2DetailKind.PASSWORD"))
        assertTrue(source.contains("VaultV2DetailKind.AUTHENTICATOR"))
        assertTrue(source.contains("VaultV2DetailKind.BANK_CARD"))
        assertTrue(source.contains("VaultV2DetailKind.DOCUMENT"))
        assertTrue(source.contains("VaultV2DetailKind.NOTE"))
        assertTrue(source.contains("VaultV2DetailKind.PASSKEY"))
    }

    @Test
    fun steamWideModeIsLimitedToTokenMasterDetailAndKeepsOtherSectionsReadable() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readText()

        assertTrue(source.contains("selectedSection == SteamSection.CODE"))
        assertTrue(source.contains("SteamReadableSectionFrame(isCompactWidth)"))
        assertTrue(source.contains("SteamSection.INVENTORY -> SteamInventoryContent"))
        assertTrue(source.contains("SteamSection.MARKET -> SteamReadableSectionFrame"))
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
}
