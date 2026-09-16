package takagi.ru.monica.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import uniffi.mdbx_ffi.MdbxFfiException
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

    @Test
    fun importsSplitAtTheDefaultCommandLimit() {
        val commands = List(4_097) { MdbxWriteCommand.DeleteEntry("entry-$it", "project") }
        val batches = planMdbx2WriteBatches(commands.map(::listOf), "import", defaultLimits(), true)
        assertEquals(17, batches.size)
        assertEquals(commands, batches.flatMap { it.commands })
        assertTrue(batches.all { it.commands.size <= 256 && it.limits.maxCommands == 256uL })
    }

    @Test
    fun importsKeepDependentCommandsTogetherAtTheDefaultBoundary() {
        val singles = List(255) { listOf(MdbxWriteCommand.DeleteEntry("entry-$it", "project")) }
        val dependent = listOf(MdbxWriteCommand.RestoreEntry("restore", "project"),
            MdbxWriteCommand.UpdateEntry("restore", "project", "login", "Title", "{}"))
        val batches = planMdbx2WriteBatches(singles + listOf(dependent), "restore", defaultLimits(), true)
        assertEquals(listOf(255, 2), batches.map { it.commands.size })
        assertEquals(dependent, batches.last().commands)
    }

    @Test
    fun importsCountUtf8BytesInsteadOfCharacters() {
        val payload = "{\"note\":\"" + "密".repeat(190_000) + "\"}"
        val commands = List(16) { MdbxWriteCommand.CreateEntry("entry-$it", "project", "login", "Title", payload) }
        val batches = planMdbx2WriteBatches(commands.map(::listOf), "utf8", defaultLimits(), true)
        assertEquals(commands.size, batches.size)
        assertEquals(commands, batches.flatMap { it.commands })
        assertTrue(batches.all { batch ->
            batch.commands.size.toLong() * payload.toByteArray(Charsets.UTF_8).size <= 1024L * 1024
        })
    }

    @Test
    fun importsIncludeEscapedTitlesInTheIntentBudget() {
        val commands = List(2) { MdbxWriteCommand.CreateEntry("entry-$it", "project", "login", "\u0000".repeat(600), "{}") }
        val limits = defaultLimits().copy(maxIntentBytes = 10_000uL)
        val batches = planMdbx2WriteBatches(commands.map(::listOf), "escaping", limits, true)
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.limits.maxIntentBytes == 10_000uL })
    }

    @Test
    fun importsIsolateLargeIndivisibleEntries() {
        val small = MdbxWriteCommand.CreateEntry("small", "project", "login", "Small", "{}")
        val large = MdbxWriteCommand.CreateEntry("large", "project", "login", "Large", "x".repeat(2 * 1024 * 1024))
        val last = small.copy(entryId = "last")
        val batches = planMdbx2WriteBatches(listOf(listOf(small), listOf(large), listOf(last)), "large", defaultLimits(), true)
        assertEquals(listOf(listOf(small), listOf(large), listOf(last)), batches.map { it.commands })
        assertEquals(2uL * 1024uL * 1024uL, batches[1].limits.maxPayloadBytesPerCommand)
        assertEquals(defaultLimits(), batches.first().limits)
        assertEquals(defaultLimits(), batches.last().limits)
    }

    @Test
    fun titlesRespectTheNativeUtf8PresentationLimitBeforePlanningWrites() {
        val titles = listOf("x".repeat(65_536), "漢".repeat(21_845) + "a")
        titles.forEach { title ->
            listOf(false, true).forEach { importing ->
                val commands = listOf(
                    MdbxWriteCommand.CreateEntry("entry", "project", "login", title, "{}"),
                    MdbxWriteCommand.UpdateEntry("entry", "project", "login", title, "{}"),
                    MdbxWriteCommand.CreateProject("project", title),
                    MdbxWriteCommand.CreateProjectWithParent("project", title, "parent"),
                    MdbxWriteCommand.RenameProject("project", title),
                )
                assertEquals(commands, planMdbx2WriteBatches(commands.map(::listOf), "boundary", defaultLimits(), importing)
                    .flatMap { it.commands })
                commands.forEach { command ->
                    val oversized = when (command) {
                        is MdbxWriteCommand.CreateEntry -> command.copy(title = title + "a")
                        is MdbxWriteCommand.UpdateEntry -> command.copy(title = title + "a")
                        is MdbxWriteCommand.CreateProject -> command.copy(title = title + "a")
                        is MdbxWriteCommand.CreateProjectWithParent -> command.copy(title = title + "a")
                        is MdbxWriteCommand.RenameProject -> command.copy(title = title + "a")
                        else -> error("Unexpected command")
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        planMdbx2WriteBatches(listOf(listOf(oversized)), "oversized-title", defaultLimits(), importing)
                    }
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedSingleEntriesAreRejectedWithoutTruncation() {
        val command = MdbxWriteCommand.CreateEntry("oversized", "project", "login", "Large",
            "x".repeat(16 * 1024 * 1024 + 1))
        planMdbx2WriteBatches(listOf(listOf(command)), "oversized", defaultLimits(), true)
    }

    @Test
    fun syncLimitRetryKeepsDependentCommandsAndOnlyAcknowledgesCommittedChildren() = runBlocking {
        val groups = List(3) { index -> listOf(
            MdbxWriteCommand.RestoreEntry("entry-$index", "project"),
            MdbxWriteCommand.UpdateEntry("entry-$index", "project", "login", "Title", "{}"),
            MdbxWriteCommand.DeleteEntry("entry-$index", "project"),
        ) }
        val batch = planMdbx2WriteBatches(groups, "retry", defaultLimits(), true).single()
        val attempts = mutableListOf<Mdbx2WriteBatch>()
        val acknowledgements = mutableListOf<List<MdbxWriteCommand>>()
        executeMdbx2ImportBatch(batch, execute = {
            attempts += it
            if (it.commandGroups.size > 1) throw syncSizeFailure()
            assertTrue(it.commandGroups.single() in groups)
        }, onCommitted = { acknowledgements += it })
        assertEquals(groups, acknowledgements)
        assertEquals(5, attempts.size)
        assertEquals(attempts.size, attempts.map { it.operationId }.distinct().size)
    }

    @Test
    fun unknownStorageFailureIsNotRetried() = runBlocking {
        val groups = List(2) { listOf(MdbxWriteCommand.DeleteEntry("entry-$it", "project")) }
        val batch = planMdbx2WriteBatches(groups, "io", defaultLimits(), true).single()
        var attempts = 0
        val failure = MdbxFfiException.Storage("disk I/O error")
        try {
            executeMdbx2ImportBatch(batch, execute = { attempts++; throw failure },
                onCommitted = { throw AssertionError("Unknown write result cannot be acknowledged") })
            throw AssertionError("The original error must be reported")
        } catch (actual: MdbxFfiException.Storage) { assertTrue(actual === failure) }
        assertEquals(1, attempts)
    }

    @Test
    fun indivisibleSyncLimitFailureIsNotRetriedOrAcknowledged() = runBlocking {
        val group = listOf(MdbxWriteCommand.RestoreEntry("entry", "project"),
            MdbxWriteCommand.UpdateEntry("entry", "project", "login", "Title", "{}"))
        val batch = planMdbx2WriteBatches(listOf(group), "single", defaultLimits(), true).single()
        var attempts = 0
        val failure = syncSizeFailure()
        try {
            executeMdbx2ImportBatch(batch, execute = { attempts++; throw failure },
                onCommitted = { throw AssertionError("Failed entry cannot be acknowledged") })
            throw AssertionError("An indivisible entry must report the limit")
        } catch (actual: MdbxFfiException.Storage) { assertTrue(actual === failure) }
        assertEquals(1, attempts)
    }

    @Test
    fun cancellationAfterSplitCommitStopsBeforeTheNextChild() = runBlocking {
        val groups = List(2) { listOf(MdbxWriteCommand.DeleteEntry("entry-$it", "project")) }
        val batch = planMdbx2WriteBatches(groups, "cancel", defaultLimits(), true).single()
        var attempts = 0
        val acknowledgements = mutableListOf<List<MdbxWriteCommand>>()
        try {
            executeMdbx2ImportBatch(batch, execute = {
                attempts++
                if (it.commandGroups.size > 1) throw syncSizeFailure()
            }, onCommitted = {
                acknowledgements += it
                throw CancellationException("Cancelled after a confirmed commit")
            })
            throw AssertionError("Cancellation must escape")
        } catch (_: CancellationException) { }
        assertEquals(2, attempts)
        assertEquals(listOf(groups.first()), acknowledgements)
    }

    private fun syncSizeFailure() = MdbxFfiException.Storage(
        "resource limit exceeded for sync delta payload bytes: 16777218 > 16777216")

    private fun defaultLimits(): MdbxWriteOperationLimits = MdbxWriteOperationLimits(
        maxCommands = 256u,
        maxPayloadBytesPerCommand = 1024uL * 1024uL,
        maxPayloadBytes = 8uL * 1024uL * 1024uL,
        maxIntentBytes = 16uL * 1024uL * 1024uL
    )
}
