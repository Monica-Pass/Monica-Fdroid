package takagi.ru.monica.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSnapshotPolicyTest {

    @Test
    fun snapshotCutoffUsesThirtyDayRetention() {
        val now = 40L * 24L * 60L * 60L * 1000L
        assertEquals(10L * 24L * 60L * 60L * 1000L, timelineSnapshotCutoff(now))
    }

    @Test
    fun snapshotEntityKeepsOnlyEncryptedPayloadField() {
        val snapshot = TimelineVersionSnapshot(
            operationLogId = 12L,
            itemType = "PASSWORD",
            itemId = 8L,
            operationType = "UPDATE",
            encryptedChangesJson = "MDK|ciphertext"
        )
        assertTrue(snapshot.encryptedChangesJson.startsWith("MDK|"))
    }

    @Test
    fun restoreRequiresEverySnapshotFieldToBeSupported() {
        assertTrue(
            areTimelineSnapshotFieldsReversible(
                itemType = "PASSWORD",
                fieldNames = listOf("用户名", "密码")
            )
        )
        assertFalse(
            areTimelineSnapshotFieldsReversible(
                itemType = "BANK_CARD",
                fieldNames = listOf("标题", "卡号")
            )
        )
        assertTrue(
            areTimelineSnapshotFieldsReversible(
                itemType = "BANK_CARD",
                fieldNames = listOf("卡号", TIMELINE_SNAPSHOT_FIELD_ITEM_DATA)
            )
        )
        assertFalse(
            areTimelineSnapshotFieldsReversible(
                itemType = "BANK_CARD",
                fieldNames = listOf("更新", TIMELINE_SNAPSHOT_FIELD_ITEM_DATA)
            )
        )
        assertFalse(
            areTimelineSnapshotFieldsReversible(
                itemType = "NOTE",
                fieldNames = listOf("内容", TIMELINE_SNAPSHOT_FIELD_ITEM_DATA)
            )
        )
        assertTrue(
            areTimelineSnapshotFieldsReversible(
                itemType = "NOTE",
                fieldNames = listOf(
                    "内容",
                    TIMELINE_SNAPSHOT_FIELD_ITEM_DATA,
                    TIMELINE_SNAPSHOT_FIELD_NOTES
                )
            )
        )
    }

    @Test
    fun onlyDedicatedSnapshotStateFieldsAreInternal() {
        assertTrue(isTimelineSnapshotInternalField(TIMELINE_SNAPSHOT_FIELD_ITEM_DATA))
        assertTrue(isTimelineSnapshotInternalField(TIMELINE_SNAPSHOT_FIELD_NOTES))
        assertFalse(isTimelineSnapshotInternalField("__batch_move_payload"))
    }
}
