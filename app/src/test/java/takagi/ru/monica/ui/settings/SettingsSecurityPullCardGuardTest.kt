package takagi.ru.monica.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSecurityPullCardGuardTest {

    @Test
    fun pullProgressClampsAtBothEnds() {
        assertEquals(
            0f,
            calculateSecurityAnalysisPullProgress(
                currentOffset = -10f,
                triggerDistance = 100f
            ),
            0.0001f
        )
        assertEquals(
            0.5f,
            calculateSecurityAnalysisPullProgress(
                currentOffset = 50f,
                triggerDistance = 100f
            ),
            0.0001f
        )
        assertEquals(
            1f,
            calculateSecurityAnalysisPullProgress(
                currentOffset = 160f,
                triggerDistance = 100f
            ),
            0.0001f
        )
    }

    @Test
    fun pullStateKeepsScrollPriorityAndTriggersOnlyOnRelease() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/common/pull/PullToActionState.kt"
        ).readText()

        assertTrue(source.contains("canStartPullState()"))
        assertTrue(source.contains("source == NestedScrollSource.UserInput"))
        assertTrue(source.contains("performPullThreshold()"))
        assertTrue(source.contains("currentOffset >= triggerDistance"))
        assertTrue(source.contains("override suspend fun onPreFling"))
        assertTrue(source.contains("onTriggeredState()"))
        assertTrue(source.contains("Spring.DampingRatioNoBouncy"))
    }

    @Test
    fun settingsPlacesPullCardBeforeSearchAndUsesTopBoundary() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/SettingsScreen.kt"
        ).readText()
        val cardIndex = source.indexOf("SecurityAnalysisPullCard(")
        val searchIndex = source.indexOf("SettingsSearchField(")

        assertTrue(cardIndex >= 0)
        assertTrue(searchIndex > cardIndex)
        assertTrue(source.contains(".nestedScroll(securityPullState.nestedScrollConnection)"))
        assertTrue(source.contains("canStartPull = { scrollState.value == 0 }"))
        assertTrue(source.contains("enabled = settingsSearchQuery.isBlank()"))
        assertTrue(source.contains("onTriggered = onSecurityAnalysis"))
        assertTrue(source.contains("onOpen = onSecurityAnalysis"))
        assertFalse(source.contains("// 安全分析入口卡片 - 置顶显示"))
    }

    @Test
    fun cardHasCompactExpandedThemeAwareAndAccessibleStates() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/settings/SecurityAnalysisPullCard.kt"
        ).readText()

        assertTrue(source.contains("compactHeight = 88.dp"))
        assertTrue(source.contains("expandedHeight = 236.dp"))
        assertTrue(source.contains("surfaceContainerHigh"))
        assertTrue(source.contains("primaryContainer"))
        assertTrue(source.contains("Card("))
        assertTrue(source.contains("onClick = onOpen"))
        assertTrue(source.contains("clearAndSetSemantics"))
        assertTrue(source.contains("contentDescription ="))
        assertTrue(source.contains("security_analysis_pull_release"))
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
