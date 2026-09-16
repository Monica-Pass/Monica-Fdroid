package takagi.ru.monica.localization

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.model.TimelineEvent
import takagi.ru.monica.ui.screens.timelineFieldLabel
import takagi.ru.monica.ui.screens.timelineFieldValue
import takagi.ru.monica.ui.screens.timelineLogSummary

class TimelineTextPresentationTest {
    @Test fun historicalFieldLabelsAreTranslatedWithoutChangingTheirIdentifiers() {
        for (language in listOf("en", "zh", "lzh", "vi", "ja", "ru", "ko", "de", "es", "fr", "pl")) {
            val strings = xmlTestStrings(language)
            assertEquals(strings.get(R.string.username), timelineFieldLabel("用户名", strings))
            assertEquals(strings.get(R.string.timeline_display_conflicts), timelineFieldLabel("冲突数", strings))
            assertEquals("Custom 字段", timelineFieldLabel("Custom 字段", strings))
        }
    }

    @Test fun onlyKnownSystemValuesAreTranslated() {
        val strings = xmlTestStrings("en")
        assertEquals("Background sync", timelineFieldValue("模式", "静默同步", strings))
        assertEquals("Resolved", timelineFieldValue("处理状态", "已解决", strings))
        assertEquals("已解决", timelineFieldValue("标题", "已解决", strings))
        assertEquals("静默同步", timelineFieldValue("密码", "静默同步", strings))
        assertEquals("我的自定义状态", timelineFieldValue("状态", "我的自定义状态", strings))
    }

    @Test fun legacyBackupTitlesFollowTheCurrentLanguage() {
        val strings = xmlTestStrings("en")
        assertEquals("Automatic upload · Permanent", timelineLogSummary(log("自动上传 · 永久", "WEBDAV_UPLOAD"), strings))
        assertEquals("Sync download", timelineLogSummary(log("同步下载", "WEBDAV_DOWNLOAD"), strings))
        assertEquals("同步下载", timelineLogSummary(log("同步下载", "PASSWORD"), strings))
        assertEquals("我自定的名称", timelineLogSummary(log("我自定的名称", "PASSWORD"), strings))
    }

    @Test fun deletionDetailsKeepTheUserTitleAndVaultIdentifier() {
        val strings = xmlTestStrings("en")
        assertEquals("My 银行 (Moved to trash; deletion pending sync)",
            timelineLogSummary(log("My 银行 (移入回收站（待同步删除）)", "PASSWORD", "DELETE"), strings))
        assertEquals("Contract (Deleted from vault #42)",
            timelineLogSummary(log("Contract (从 Vault #42 删除)", "BITWARDEN_SEND", "DELETE"), strings))
        assertEquals("My note (移入回收站)",
            timelineLogSummary(log("My note (移入回收站)", "NOTE", "CREATE"), strings))
    }

    private fun log(summary: String, type: String, operation: String = "SYNC") =
        TimelineEvent.StandardLog("test", 0L, "device", summary, itemType = type, operationType = operation)
}
