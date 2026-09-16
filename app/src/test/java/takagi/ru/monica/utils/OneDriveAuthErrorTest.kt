package takagi.ru.monica.utils

import takagi.ru.monica.localization.xmlTestStrings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDriveAuthErrorTest {

    @Test
    fun powerOptimizationRefreshFailure_isTreatedAsTemporaryOneDriveAuthIssue() {
        val error = IllegalStateException(
            "Connection is not available to refresh token because power optimization is enabled. " +
                "And the device is in doze mode or the app is standby"
        )

        assertTrue(error.isOneDriveAuthTemporarilyUnavailable())
        assertEquals(
            "OneDrive 暂时无法刷新登录状态。请关闭电池优化，或点亮屏幕后重新打开 Monica 再试。",
            error.toOneDriveUserMessage(xmlTestStrings("zh"), "fallback")
        )
    }
}
