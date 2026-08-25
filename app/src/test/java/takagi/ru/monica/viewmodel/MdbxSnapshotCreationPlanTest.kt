package takagi.ru.monica.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class MdbxSnapshotCreationPlanTest {

    @Test
    fun fullSnapshotRequestAlwaysCreatesFullSnapshot() {
        assertEquals(
            MdbxSnapshotCreationPlan.Create(fullSnapshot = true),
            planMdbxSnapshotCreation(
                requestedFullSnapshot = true,
                engineRequiresFullSnapshot = false,
                currentHeadCommitId = "head-2",
                latestSnapshotBaseCommitId = "head-2"
            )
        )
    }

    @Test
    fun unchangedIncrementalRequestRequiresConfirmation() {
        assertEquals(
            MdbxSnapshotCreationPlan.ConfirmFullSnapshot,
            planMdbxSnapshotCreation(
                requestedFullSnapshot = false,
                engineRequiresFullSnapshot = false,
                currentHeadCommitId = "head-2",
                latestSnapshotBaseCommitId = "head-2"
            )
        )
    }

    @Test
    fun changedMdbx1RequestKeepsRealIncrementalSnapshot() {
        assertEquals(
            MdbxSnapshotCreationPlan.Create(fullSnapshot = false),
            planMdbxSnapshotCreation(
                requestedFullSnapshot = false,
                engineRequiresFullSnapshot = false,
                currentHeadCommitId = "head-2",
                latestSnapshotBaseCommitId = "head-1"
            )
        )
    }

    @Test
    fun changedMdbx2RequestCreatesTruthfulFullSnapshot() {
        assertEquals(
            MdbxSnapshotCreationPlan.Create(fullSnapshot = true),
            planMdbxSnapshotCreation(
                requestedFullSnapshot = false,
                engineRequiresFullSnapshot = true,
                currentHeadCommitId = "head-2",
                latestSnapshotBaseCommitId = "head-1"
            )
        )
    }

    @Test
    fun firstSnapshotUsesTheEngineSupportedMode() {
        assertEquals(
            MdbxSnapshotCreationPlan.Create(fullSnapshot = false),
            planMdbxSnapshotCreation(
                requestedFullSnapshot = false,
                engineRequiresFullSnapshot = false,
                currentHeadCommitId = "head-1",
                latestSnapshotBaseCommitId = null
            )
        )
        assertEquals(
            MdbxSnapshotCreationPlan.Create(fullSnapshot = true),
            planMdbxSnapshotCreation(
                requestedFullSnapshot = false,
                engineRequiresFullSnapshot = true,
                currentHeadCommitId = "head-1",
                latestSnapshotBaseCommitId = null
            )
        )
    }
}
