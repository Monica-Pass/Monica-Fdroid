package takagi.ru.monica.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.MdbxWriteOperationLimits

class Mdbx2BatchSupportTest {

    @Test
    fun raisesCommandLimitAboveInteractiveDefaultWithoutSplitting() {
        val groups = List(257) { index ->
            listOf(
                MdbxWriteCommand.DeleteEntry(
                    entryId = "entry-$index",
                    projectId = "project"
                )
            )
        }

        val batches = planMdbx2WriteBatches(groups, "batch", defaultLimits())

        assertEquals(1, batches.size)
        assertEquals(257, batches.single().commands.size)
        assertEquals(257uL, batches.single().limits.maxCommands)
        assertEquals("batch", batches.single().operationId)
    }

    @Test
    fun keepsOneEntryCommandGroupTogetherAcrossNativeCommandBoundary() {
        val singleCommandGroups = List(4_095) { index ->
            listOf(MdbxWriteCommand.DeleteEntry("entry-$index", "project"))
        }
        val dependentGroup = listOf(
            MdbxWriteCommand.RestoreEntry("dependent", "project"),
            MdbxWriteCommand.UpdateEntry("dependent", "project", "login", "Title", "{}")
        )

        val batches = planMdbx2WriteBatches(
            commandGroups = singleCommandGroups + listOf(dependentGroup),
            baseOperationId = "stable",
            defaultLimits = defaultLimits()
        )

        assertEquals(2, batches.size)
        assertEquals(4_095, batches[0].commands.size)
        assertEquals(dependentGroup, batches[1].commands)
        assertEquals("stable-1-of-2", batches[0].operationId)
        assertEquals("stable-2-of-2", batches[1].operationId)
    }

    @Test
    fun expandsPayloadLimitsOnlyAsNeededWithinNativeCeilings() {
        val payload = "x".repeat(2 * 1024 * 1024)
        val command = MdbxWriteCommand.CreateEntry(
            entryId = "entry",
            projectId = "project",
            entryType = "login",
            title = "Title",
            payloadJson = payload
        )

        val batch = planMdbx2WriteBatches(
            commandGroups = listOf(listOf(command)),
            baseOperationId = "payload",
            defaultLimits = defaultLimits()
        ).single()

        assertEquals(payload.toByteArray().size.toULong(), batch.limits.maxPayloadBytesPerCommand)
        assertTrue(batch.limits.maxPayloadBytes >= batch.limits.maxPayloadBytesPerCommand)
        assertTrue(batch.limits.maxIntentBytes > batch.limits.maxPayloadBytesPerCommand)
    }

    private fun defaultLimits(): MdbxWriteOperationLimits = MdbxWriteOperationLimits(
        maxCommands = 256u,
        maxPayloadBytesPerCommand = 1024uL * 1024uL,
        maxPayloadBytes = 8uL * 1024uL * 1024uL,
        maxIntentBytes = 16uL * 1024uL * 1024uL
    )
}
