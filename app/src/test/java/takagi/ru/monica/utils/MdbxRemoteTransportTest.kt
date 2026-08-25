package takagi.ru.monica.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbxRemoteTransportTest {
    @Test
    fun pathsAreStableAndContentAddressed() {
        val digest = "ab".repeat(32)
        assertEquals("vaults/main.mdbx.sync", MdbxRemoteSyncPaths.syncRoot("/vaults/main.mdbx/"))
        assertEquals(
            "vaults/main.mdbx.sync/streams/device-a/transfer-a/segments/0000000007-$digest.mdbxsync",
            MdbxRemoteSyncPaths.segmentPath(
                remoteVaultPath = "vaults/main.mdbx",
                deviceId = "device-a",
                generationId = "transfer-a",
                sequence = 7u,
                digestHex = digest
            )
        )
        assertEquals(
            "vaults/main.mdbx.sync/blobs/ab/ab/$digest",
            MdbxRemoteSyncPaths.blobPath("vaults/main.mdbx", digest)
        )
    }

    @Test
    fun pathEscapeAndMalformedDigestAreRejected() {
        assertFails { MdbxRemoteSyncPaths.normalizePath("vaults/../main.mdbx") }
        assertFails { MdbxRemoteSyncPaths.component("device/a") }
        assertFails { MdbxRemoteSyncPaths.blobPath("main.mdbx", "not-a-digest") }
        assertFails {
            MdbxRemoteSyncPaths.segmentPath("main.mdbx", "device", "transfer", 0u, "ff")
        }
    }

    @Test
    fun fileDigestUsesStreamingSha256() {
        val file = File.createTempFile("mdbx-transport-", ".bin")
        try {
            file.writeText("Monica MDBX2")
            assertEquals(
                "aba9d8b19b16ffa94307cea99f0a4b8f38a10aa1732a0f1c25686404bff6af94",
                MdbxRemoteSyncPaths.sha256Hex(file)
            )
        } finally {
            assertTrue(file.delete() || !file.exists())
        }
    }

    private fun assertFails(block: () -> Unit) {
        check(runCatching(block).isFailure) { "Expected operation to fail" }
    }
}
