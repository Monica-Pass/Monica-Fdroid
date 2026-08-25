package takagi.ru.monica.keepass

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant

class KeePassSourceSafetyTest {
    @Test
    fun unchangedSourceRevisionIsAccepted() {
        val loaded = "encrypted-kdbx-v1".encodeToByteArray()
        val expected = KeePassSourceSafety.revisionOf(loaded)

        KeePassSourceSafety.requireUnchanged(
            expectedRevision = expected,
            currentBytes = loaded.copyOf(),
            sourceLabel = "content://fixture/database.kdbx"
        )
    }

    @Test
    fun precomputedUnchangedSourceRevisionIsAcceptedWithoutRehashingBytes() {
        val expected = KeePassSourceSafety.revisionOf("encrypted-kdbx-v1".encodeToByteArray())

        KeePassSourceSafety.requireUnchanged(
            expectedRevision = expected,
            currentRevision = expected.copy(),
            sourceLabel = "content://fixture/database.kdbx"
        )
    }

    @Test
    fun streamRevisionMatchesByteArrayRevision() {
        val bytes = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        val fromBytes = KeePassSourceSafety.revisionOf(bytes)
        val fromStream = KeePassSourceSafety.revisionOf(ByteArrayInputStream(bytes))
        assertEquals(fromBytes, fromStream)
    }

    @Test
    fun changedSourceRevisionIsRejectedBeforeWrite() {
        val expected = KeePassSourceSafety.revisionOf("encrypted-kdbx-v1".encodeToByteArray())

        try {
            KeePassSourceSafety.requireUnchanged(
                expectedRevision = expected,
                currentBytes = "encrypted-kdbx-v2-from-other-client".encodeToByteArray(),
                sourceLabel = "content://fixture/database.kdbx"
            )
            fail("Expected KeePassSourceChangedException")
        } catch (error: KeePassSourceChangedException) {
            assertTrue(error.message.orEmpty().contains("已被其他应用修改"))
        }
    }

    @Test
    fun recoveryCopyIsWrittenAtomicallyAndCanBeVerified() {
        val root = Files.createTempDirectory("monica-keepass-recovery").toFile()
        try {
            val bytes = "encrypted-kdbx-recovery".encodeToByteArray()
            val store = KeePassRecoveryStore(
                rootDir = root,
                nowProvider = { Instant.parse("2026-08-17T12:00:00Z") }
            )

            val copy = store.create(databaseId = 42L, bytes = bytes)

            assertTrue(copy.file.isFile)
            assertArrayEquals(bytes, copy.file.readBytes())
            assertTrue(store.verify(copy))
            assertFalse(File(copy.file.parentFile, "${copy.file.name}.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryCopyAcceptsTheRevisionAlreadyComputedAtTheWriteBoundary() {
        val root = Files.createTempDirectory("monica-keepass-recovery-revision").toFile()
        try {
            val bytes = "encrypted-kdbx-recovery".encodeToByteArray()
            val revision = KeePassSourceSafety.revisionOf(bytes)
            val store = KeePassRecoveryStore(root)

            val copy = store.create(databaseId = 42L, bytes = bytes, revision = revision)

            assertEquals(revision, copy.revision)
            assertTrue(store.verify(copy))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptedRecoveryCopyFailsVerificationAndIsRetained() {
        val root = Files.createTempDirectory("monica-keepass-recovery-corrupt").toFile()
        try {
            val store = KeePassRecoveryStore(root)
            val copy = store.create(42L, "encrypted-kdbx-recovery".encodeToByteArray())
            copy.file.writeText("corrupted")

            assertFalse(store.verify(copy))
            assertTrue(copy.file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun foregroundGoogleDriveWriteUsesLoadedExpectedVersion() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()
        val googleBranch = serviceSource
            .substringAfter("KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE ->")
            .substringBefore("else -> null")

        assertTrue(
            "Google Drive foreground writes must use the same conditional revision policy as WebDAV/OneDrive.",
            googleBranch.contains("expectedRemoteVersion") &&
                googleBranch.contains("fileSource.write(bytes, expectedVersion = expectedRemoteVersion)")
        )
    }

    @Test
    fun googleDriveRevisionAcceptsEitherDriveVersionOrChecksum() {
        assertEquals(
            "drive-version-8",
            KeePassRemoteVersionPolicy.preferred(
                versionToken = "drive-version-8",
                etag = "md5-checksum"
            )
        )
        assertTrue(
            KeePassRemoteVersionPolicy.matches(
                expected = "drive-version-8",
                versionToken = "drive-version-8",
                etag = "md5-checksum"
            )
        )
        assertTrue(
            KeePassRemoteVersionPolicy.matches(
                expected = "md5-checksum",
                versionToken = "drive-version-8",
                etag = "md5-checksum"
            )
        )
        assertFalse(
            KeePassRemoteVersionPolicy.matches(
                expected = "stale-version",
                versionToken = "drive-version-8",
                etag = "md5-checksum"
            )
        )
    }

    @Test
    fun loadedDatabaseCarriesSourceContentRevisionIntoWriteBoundary() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()

        assertTrue(serviceSource.contains("val sourceRevision: KeePassSourceRevision"))
        assertTrue(serviceSource.contains("expectedSourceRevision = loaded.sourceRevision"))
        assertTrue(serviceSource.contains("KeePassSourceSafety.requireUnchanged"))
        assertNotNull(serviceSource)
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
