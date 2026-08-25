package takagi.ru.monica.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerInteractionPerformanceGuardTest {

    @Test
    fun interactionRefreshIsThrottledAndUsesAsynchronousPreferencesWrite() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SessionManager.kt"
        ).readText()
        val refreshBody = source.substringAfter("fun refreshSession()")
            .substringBefore("fun flushPendingRefresh()")

        assertTrue(refreshBody.contains("shouldRefreshSessionActivity("))
        assertTrue(refreshBody.contains("shouldPersistSessionRefresh("))
        assertTrue(refreshBody.contains("persistAllAsync()"))
        assertFalse(refreshBody.contains("persistAllSynchronously()"))
        assertFalse(refreshBody.contains(".commit()"))
    }

    @Test
    fun criticalLockBoundariesRemainSynchronousAndBackgroundingFlushesActivity() {
        val sessionSource = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SessionManager.kt"
        ).readText()
        val baseActivitySource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/base/BaseMonicaActivity.kt"
        ).readText()
        val lockBody = sessionSource.substringAfter("fun markLocked(")
            .substringBefore("fun updateAutoLockTimeout(")

        assertTrue(lockBody.contains("persistAllSynchronously()"))
        assertTrue(sessionSource.contains("fun flushPendingRefresh()"))
        assertTrue(baseActivitySource.contains("override fun onStop()"))
        assertTrue(baseActivitySource.contains("SessionManager.flushPendingRefresh()"))
    }

    @Test
    fun timeoutUpdateCannotOverwriteAnUnrestoredPersistedSession() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SessionManager.kt"
        ).readText()
        val timeoutBody = source.substringAfter("fun updateAutoLockTimeout(")
            .substringBefore("fun canSkipVerification(")

        assertTrue(timeoutBody.contains("putInt(KEY_AUTO_LOCK, minutes)"))
        assertTrue(timeoutBody.contains(".apply()"))
        assertFalse(timeoutBody.contains("persistAllAsync()"))
        assertFalse(timeoutBody.contains("persistAllSynchronously()"))
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
