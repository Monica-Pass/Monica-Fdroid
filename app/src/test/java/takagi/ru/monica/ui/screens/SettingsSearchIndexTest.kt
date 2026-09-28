package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {
    private fun entry(id: String, title: String, summary: String = "", path: String = "设置", keywords: String = "") =
        SettingsSearchEntry(id, title, summary, path, "test", keywords = keywords)

    @Test fun specificTitleRanksBeforeDescriptionAndParentPathMatches() {
        val entries = listOf(
            entry("parent", "显示方式", path = "设置 › 图标"),
            entry("description", "主应用外观", summary = "更换图标"),
            entry("title", "图标"),
        )
        assertEquals(listOf("title", "description", "parent"), searchSettings(entries, "图标").map { it.id })
    }

    @Test fun wordsCanMatchASettingAndItsLocationTogether() {
        val entries = listOf(
            entry("keyboard", "隐藏数字按键预览", path = "设置 › 自动填充", keywords = "键盘 PIN keyboard"),
            entry("number", "随机排列数字键", keywords = "键盘 PIN keyboard"),
        )
        assertEquals(listOf("keyboard"), searchSettings(entries, " 键盘   隐藏 ").map { it.id })
        assertEquals(listOf("keyboard"), searchSettings(entries, "自动填充 隐藏").map { it.id })
    }

    @Test fun englishCaseAndFullWidthInputAreNormalized() {
        val entries = listOf(entry("icon", "主应用图标", keywords = "Grok bot"))
        assertEquals(listOf("icon"), searchSettings(entries, "  ＧＲＯＫ　ＢＯＴ  ").map { it.id })
    }

    @Test fun blankAndUnmatchedQueriesDoNotReturnEverySetting() {
        val entries = listOf(entry("one", "自动锁定"))
        assertTrue(searchSettings(entries, "  \n ").isEmpty())
        assertTrue(searchSettings(entries, "不存在的设置").isEmpty())
    }

    @Test fun aliasesDoNotDiscardOtherRequiredWordsAndDuplicatesAreRemoved() {
        val original = entry("clipboard", "剪贴板自动清除", keywords = "清理 clipboard")
        assertEquals(1, searchSettings(listOf(original, original), "clipboard 清理").size)
        assertTrue(searchSettings(listOf(original), "clipboard 键盘").isEmpty())
    }
}
