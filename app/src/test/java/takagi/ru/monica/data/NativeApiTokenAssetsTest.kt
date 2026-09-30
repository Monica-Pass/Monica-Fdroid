package takagi.ru.monica.data

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.model.EmbeddedWalletContent

class NativeApiTokenAssetsTest {
    @Test fun exportSuggestionCannotChooseAPathOrContainControlCharacters() {
        assertEquals("report.txt", NativeApiTokenAssets.exportName("../../private/report.txt"))
        assertEquals("report.txt", NativeApiTokenAssets.exportName("C:\\private\\report.txt"))
        assertEquals("report.txt", NativeApiTokenAssets.exportName("report\n.txt"))
        assertEquals("attachment", NativeApiTokenAssets.exportName(".."))
    }

    @Test fun validatesActualBytesAndStopsAtTheBound() {
        val original = byteArrayOf(0, 1, 2, -1)
        val hash = NativeApiTokenAssets.digest(original)
        NativeApiTokenAssets.validate(original, 4, hash)
        assertArrayEquals(original, NativeApiTokenAssets.readBounded(original.inputStream(), 4))
        assertTrue(runCatching { NativeApiTokenAssets.readBounded(original.inputStream(), 3) }.isFailure)
        assertTrue(runCatching { NativeApiTokenAssets.validate(byteArrayOf(0, 1, 2, 3), 4, hash) }.isFailure)
        assertTrue(runCatching { NativeApiTokenAssets.validate(original, 3, hash) }.isFailure)
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), original)
    }

    @Test fun walletMetadataDoesNotAlterOrGrantGatewayPermissions() {
        val payload = """{"schema":"monica.api-token.v1","provider":"custom","token":"synthetic-token"}"""
        val original = ApiTokenPayload.decode(payload)
        val bytes = byteArrayOf(4, 5, 6)
        val snapshot = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.NOTE, title = "Copied note",
            itemData = """{"content":"body","future":{"keep":true}}""")).withAssets(listOf(
                EmbeddedWalletContent.Asset("wallet-test", "note.bin", "application/octet-stream",
                    EmbeddedWalletContent.AssetRole.ATTACHMENT, bytes.size.toLong(), NativeApiTokenAssets.digest(bytes))))
        val metadata = ApiTokenMetadata.withCustomFields(ApiTokenMetadata.empty(), EmbeddedWalletContent.put(emptyList(), snapshot) +
            CustomFieldDraft(-20, "permissions", "admin", true))
        assertTrue(ApiTokenMetadata.isValid(metadata))
        assertEquals(original, ApiTokenPayload.decode(payload))
        assertFalse(ApiTokenPayload.isValid(payload))
        assertEquals(snapshot.encode(), ApiTokenMetadata.customFields(metadata).first().value)
        val read = (EmbeddedWalletContent.read(ApiTokenMetadata.customFields(metadata).first().value) as EmbeddedWalletContent.ReadResult.Available).snapshot
        assertEquals(snapshot.assets, read.assets)
        assertTrue(read.itemData.containsKey("future"))
    }
}
