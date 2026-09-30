package takagi.ru.monica.transfer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.ApiTokenMetadata
import takagi.ru.monica.data.NativeApiTokenAssets
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.EmbeddedWalletContent

class NativeTokenBackupAssetsTest {
    private val bytes = byteArrayOf(1, 2, 3)
    private val attachment = NativeTokenBackupAttachment("wallet-backup", "application/octet-stream", 3,
        NativeApiTokenAssets.digest(bytes), java.util.Base64.getEncoder().encodeToString(bytes))
    private fun token(): NativeTokenBackup {
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.NOTE, title = "Copy",
            itemData = """{"content":"body","future":{"keep":true}}"""))
            .withAssets(listOf(EmbeddedWalletContent.Asset(attachment.fileName, "source.bin", attachment.mimeType,
                EmbeddedWalletContent.AssetRole.ATTACHMENT, attachment.size, attachment.sha256)))
        val metadata = ApiTokenMetadata.withCustomFields(ApiTokenMetadata.empty(), EmbeddedWalletContent.put(emptyList(), snapshot))
        return NativeTokenBackup("Token", "opaque-payload", metadata, attachments = listOf(attachment), attachmentCount = 1)
    }

    @Test fun missingCopiedWalletAttachmentIsRejectedEvenWhenCountMatchesAvailableFiles() {
        val source = token()
        val missing = source.copy(attachments = emptyList(), attachmentCount = 0)
        assertTrue(runCatching { NativeTokenBackupAssets.validate(listOf(missing)) }.isFailure)
        NativeTokenBackupAssets.validate(listOf(source))
        assertEquals(1, source.attachments.size)
    }

    @Test fun copiedWalletNamesMustBeUniqueAndMatchTheImmutableSizeAndHash() {
        val source = token()
        val duplicate = source.copy(attachments = listOf(attachment, attachment), attachmentCount = 2)
        val wrongHash = source.copy(attachments = listOf(attachment.copy(sha256 = "0".repeat(64))))
        val wrongSize = source.copy(attachments = listOf(attachment.copy(size = 4)))
        listOf(duplicate, wrongHash, wrongSize).forEach {
            assertTrue(runCatching { NativeTokenBackupAssets.validate(listOf(it)) }.isFailure)
        }
    }

    @Test fun supportedAndFutureSnapshotMetadataRemainVerbatimInArchiveDecode() {
        val source = token()
        val future = source.copy(metadata = source.metadata.replace("\\\"version\\\":1", "\\\"version\\\":2"))
        assertNotEquals(source.metadata, future.metadata)
        val copies = NativeTokenBackupAssets.decode(Json.encodeToString(listOf(source, future)))
        assertEquals(source.metadata, copies[0].metadata)
        assertEquals(future.metadata, copies[1].metadata)
        assertEquals(source.attachments, copies[0].attachments)
        assertEquals(future.attachments, copies[1].attachments)
    }

    @Test fun newManifestRejectsMissingEmptyDuplicateOrTruncatedTokenFiles() {
        val source = token()
        assertEquals(1, NativeTokenBackupAssets.expectedTokenCount("""{"version":1,"nativeTokenCount":1}"""))
        assertTrue(runCatching { NativeTokenBackupAssets.validateArchive(1, 0, emptyList()) }.isFailure)
        assertTrue(runCatching { NativeTokenBackupAssets.validateArchive(1, 1, emptyList()) }.isFailure)
        assertTrue(runCatching { NativeTokenBackupAssets.validateArchive(2, 1, listOf(source)) }.isFailure)
        assertTrue(runCatching { NativeTokenBackupAssets.validateArchive(1, 2, listOf(source)) }.isFailure)
        NativeTokenBackupAssets.validateArchive(1, 1, listOf(source))
    }

    @Test fun oldManifestsAndNewEmptyArchivesRemainSupported() {
        assertNull(NativeTokenBackupAssets.expectedTokenCount("""{"version":1,"source":"MDBX"}"""))
        NativeTokenBackupAssets.validateArchive(null, 0, emptyList())
        NativeTokenBackupAssets.validateArchive(null, 1, listOf(token()))
        NativeTokenBackupAssets.validateArchive(0, 0, emptyList())
        NativeTokenBackupAssets.validateArchive(0, 1, emptyList())
        assertTrue(runCatching { NativeTokenBackupAssets.validateArchive(0, 1, listOf(token())) }.isFailure)
    }

    @Test fun malformedManifestCountsFailInsteadOfDisablingCompletenessChecks() {
        listOf("-1", "null", "\"1\"", "1.5", "[]").forEach { invalid ->
            assertTrue(runCatching { NativeTokenBackupAssets.expectedTokenCount("{\"nativeTokenCount\":$invalid}") }.isFailure)
        }
    }
}
