package takagi.ru.monica.keepass

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassWritePreflightTest {
    @Test
    fun `small database and attachment fit within available heap`() {
        val result = KeePassWritePreflight.evaluate(
            currentDatabaseBytes = 2L * MIB,
            incomingPayloadBytes = 1L * MIB,
            availableHeapBytes = 128L * MIB
        )

        assertTrue(result.allowed)
        assertFalse(result.requiresConfirmation)
        assertEquals(0L, result.additionalBytesRequired)
    }

    @Test
    fun `large attachment is rejected before allocation with a concrete deficit`() {
        val result = KeePassWritePreflight.evaluate(
            currentDatabaseBytes = 80L * MIB,
            incomingPayloadBytes = 96L * MIB,
            availableHeapBytes = 120L * MIB
        )

        assertFalse(result.allowed)
        assertTrue(result.additionalBytesRequired > 0)
        assertTrue(result.estimatedPeakBytes > result.availableHeapBytes)
    }

    @Test
    fun `borderline write requests confirmation instead of failing`() {
        val result = KeePassWritePreflight.evaluate(
            currentDatabaseBytes = 24L * MIB,
            incomingPayloadBytes = 8L * MIB,
            availableHeapBytes = 128L * MIB
        )

        assertTrue(result.allowed)
        assertTrue(result.requiresConfirmation)
    }

    @Test
    fun `native uri attachment checks declared size before reading bytes`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readText()
        val method = source
            .substringAfter("internal suspend fun addNativeAttachmentFromUri(")
            .substringBefore("internal suspend fun deleteNativeAttachment(")

        val preflightIndex = method.indexOf("KeePassWritePreflight.evaluateRuntime")
        val allocationIndex = method.indexOf("openInputStream(sourceUri)")
        assertTrue(preflightIndex >= 0)
        assertTrue(allocationIndex >= 0)
        assertTrue(preflightIndex < allocationIndex)
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

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
