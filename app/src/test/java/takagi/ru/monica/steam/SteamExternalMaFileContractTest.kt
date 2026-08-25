package takagi.ru.monica.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.data.SteamExternalMaFileContract

class SteamExternalMaFileContractTest {
    @Test
    fun markerRequiresExactMonicaTypeAndVersion() {
        assertTrue(
            SteamExternalMaFileContract.isMarked(
                listOf("monica.type" to "STEAM_MAFILE_V1")
            )
        )
        assertFalse(
            SteamExternalMaFileContract.isMarked(
                listOf("Monica.Type" to "steam")
            )
        )
    }

    @Test
    fun attachmentSelectionRejectsMissingOrAmbiguousMaFiles() {
        assertEquals(
            "account.maFile",
            SteamExternalMaFileContract.selectMaFile(listOf("note.txt", "account.maFile"))
        )
        assertNull(SteamExternalMaFileContract.selectMaFile(listOf("note.txt")))
        assertNull(
            SteamExternalMaFileContract.selectMaFile(
                listOf("one.maFile", "two.maFile")
            )
        )
    }

    @Test
    fun markedEntryCanInspectASingleLegacyAttachmentWithEncryptedName() {
        assertEquals(
            listOf("2.encrypted-name"),
            SteamExternalMaFileContract.candidateFileNames(
                listOf("2.encrypted-name")
            )
        )
        assertEquals(
            listOf("account.maFile", "2.encrypted-name"),
            SteamExternalMaFileContract.candidateFileNames(
                listOf("2.encrypted-name", "account.maFile")
            )
        )
    }

    @Test
    fun pendingMarkerRemainsRecoverableWithoutBeingTreatedAsReady() {
        val fields = listOf(
            SteamExternalMaFileContract.MARKER_FIELD to
                SteamExternalMaFileContract.PENDING_MARKER_VALUE
        )

        assertTrue(SteamExternalMaFileContract.isPending(fields))
        assertFalse(SteamExternalMaFileContract.isMarked(fields))
    }

    @Test
    fun generatedAttachmentNameIsSafeAndKeepsMaFileExtension() {
        val account = SteamAccount(
            id = 1,
            steamId = "76561199871008657",
            accountName = "bad/name:*?",
            displayName = "Steam",
            deviceId = "",
            sharedSecret = "secret",
            identitySecret = null,
            revocationCode = null,
            tokenGid = null,
            accessToken = null,
            refreshToken = null,
            steamLoginSecure = null,
            rawSteamGuardJson = "{}",
            selected = true,
            sortOrder = 0,
            createdAt = 1,
            updatedAt = 1
        )

        assertEquals("bad_name___.maFile", SteamExternalMaFileContract.attachmentFileName(account))
    }

    @Test
    fun maFileSizeValidationUsesTheExternalAttachmentLimit() {
        assertTrue(
            SteamExternalMaFileContract.isValidMaFileSize(
                SteamExternalMaFileContract.MAX_MAFILE_BYTES
            )
        )
        assertFalse(SteamExternalMaFileContract.isValidMaFileSize(0))
        assertFalse(
            SteamExternalMaFileContract.isValidMaFileSize(
                SteamExternalMaFileContract.MAX_MAFILE_BYTES + 1
            )
        )
    }
}
