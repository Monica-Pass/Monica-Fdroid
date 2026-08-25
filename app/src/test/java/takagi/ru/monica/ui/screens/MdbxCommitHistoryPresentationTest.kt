package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.repository.MdbxCommitChangeSummary
import takagi.ru.monica.repository.MdbxDeltaSummary

class MdbxCommitHistoryPresentationTest {

    @Test
    fun batchCreatesBecomeOneReadableAddOperation() {
        val summary = delta(
            operationKind = "monica-upsert-entries",
            changes = listOf(
                change("entry", "one", "create"),
                change("entry", "two", "create"),
                change("entry", "three", "create")
            )
        )

        val presentation = summary.toHistoryPresentation()

        assertEquals("添加了3 个条目", presentation.title)
        assertEquals("新增 3", presentation.supportingText)
        assertEquals(3, presentation.objectCount)
        assertTrue(presentation.canRevert)
        assertFalse(presentation.isSystemCommit)
    }

    @Test
    fun initializationCommitIsExplainedWithoutPretendingItHasObjects() {
        val presentation = delta(
            operationKind = "monica-initialize",
            commitKind = "change",
            changeScope = "project",
            changes = emptyList()
        ).toHistoryPresentation()

        assertEquals("初始化数据库", presentation.title)
        assertEquals("建立数据库根目录和初始结构", presentation.supportingText)
        assertTrue(presentation.isSystemCommit)
        assertFalse(presentation.canRevert)
    }

    @Test
    fun keyRotationCommitIsSystemHistoryAndCannotRevert() {
        val presentation = delta(
            commitKind = "key-rotation",
            changeScope = "key-epoch",
            changes = emptyList()
        ).toHistoryPresentation()

        assertEquals("数据库系统事件", presentation.title)
        assertTrue(presentation.supportingText.contains("加密密钥"))
        assertTrue(presentation.isSystemCommit)
        assertFalse(presentation.canRevert)
    }

    @Test
    fun mixedActionsUseCompactCounts() {
        val presentation = delta(
            changes = listOf(
                change("entry", "one", "create"),
                change("entry", "two", "update"),
                change("entry", "three", "delete")
            )
        ).toHistoryPresentation()

        assertEquals("更新了3 个条目", presentation.title)
        assertEquals("新增 1 · 修改 1 · 删除 1", presentation.supportingText)
    }

    @Test
    fun objectTypeLabelsPreferMonicaContentType() {
        assertEquals("密码", mdbxHistoryObjectTypeLabel("entry", "login"))
        assertEquals("验证器", mdbxHistoryObjectTypeLabel("entry", "totp"))
        assertEquals("Steam 账号", mdbxHistoryObjectTypeLabel("entry", "steam-mafile"))
        assertEquals("文件夹", mdbxHistoryObjectTypeLabel("project"))
    }

    private fun delta(
        operationKind: String? = "monica-upsert-entries",
        commitKind: String = "change",
        changeScope: String = "entry",
        changes: List<MdbxCommitChangeSummary>
    ) = MdbxDeltaSummary(
        commitId = "commit",
        deviceId = "device",
        localSeq = 1,
        commitKind = commitKind,
        changeScope = changeScope,
        changedObjectIds = changes.joinToString(prefix = "[", postfix = "]") { "\"${it.objectId}\"" },
        changedObjectPreview = "",
        changedFieldSummary = "",
        parentCount = 1,
        createdAt = "2026-08-01T00:00:00Z",
        operationKind = operationKind,
        changes = changes
    )

    private fun change(type: String, id: String, action: String) = MdbxCommitChangeSummary(
        objectType = type,
        objectId = id,
        action = action,
        fields = emptyList()
    )
}
