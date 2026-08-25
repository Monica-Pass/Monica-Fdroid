package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.model.DiffChange
import takagi.ru.monica.data.model.TimelineEvent

class TimelineSafetyPolicyTest {

    @Test
    fun redactedAndPasswordChangesAreAlwaysSensitive() {
        assertTrue(DiffChange("用户名", "<redacted>", "<redacted>").isTimelineSensitiveChange())
        assertTrue(DiffChange("密码", "old", "new").isTimelineSensitiveChange())
        assertTrue(DiffChange("Password", "", "new").isTimelineSensitiveChange())
        assertFalse(DiffChange("网站", "a.example", "b.example").isTimelineSensitiveChange())
    }

    @Test
    fun encryptedSnapshotMasksSensitiveValuesUntilUserRevealsThem() {
        assertFalse(shouldMaskTimelineSnapshotField("PASSWORD", "标题"))
        assertTrue(shouldMaskTimelineSnapshotField("PASSWORD", "用户名"))
        assertTrue(shouldMaskTimelineSnapshotField("BANK_CARD", "卡号"))
        assertTrue(shouldMaskTimelineSnapshotField("NOTE", "内容"))
        assertFalse(shouldMaskTimelineSnapshotField("CATEGORY", "名称"))
    }

    @Test
    fun internalStorageTitleUsesCurrentTitleWhenAvailable() {
        val log = log(summary = "PASSWORD#1507")

        assertEquals(
            "GitHub",
            resolveTimelineDisplaySummary(log, currentTitle = "GitHub", genericTypeLabel = "密码条目")
        )
    }

    @Test
    fun internalStorageTitleNeverLeaksNumericIdWhenItemNoLongerExists() {
        val log = log(summary = "PASSWORD#1507")

        assertEquals(
            "密码条目",
            resolveTimelineDisplaySummary(log, currentTitle = null, genericTypeLabel = "密码条目")
        )
    }

    @Test
    fun legitimateSummaryIsNotReplaced() {
        val log = log(summary = "手动上传 · 临时", itemType = "WEBDAV_UPLOAD")

        assertEquals(
            "手动上传 · 临时",
            resolveTimelineDisplaySummary(log, currentTitle = null, genericTypeLabel = "云备份")
        )
    }

    private fun log(
        summary: String,
        itemType: String = "PASSWORD"
    ) = TimelineEvent.StandardLog(
        id = "1",
        timestamp = 0L,
        deviceId = "device",
        summary = summary,
        itemId = 1507L,
        itemType = itemType,
        operationType = "UPDATE"
    )
}
