package takagi.ru.monica.autofill_ng

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualFillTargetPolicyTest {
    @Test
    fun targetBoundFillWaitsUntilDetectedAppIsActive() {
        assertNull(
            resolveManualFillTargetPackage(
                activePackage = "com.other.app",
                packageNameToSkip = "takagi.ru.monica",
                expectedTargetPackage = "com.detected.app",
            )
        )
        assertEquals(
            "com.detected.app",
            resolveManualFillTargetPackage(
                activePackage = "COM.DETECTED.APP",
                packageNameToSkip = "takagi.ru.monica",
                expectedTargetPackage = "com.detected.app",
            )
        )
    }

    @Test
    fun legacyManualEntryKeepsCurrentAppBehavior() {
        assertEquals(
            "com.current.app",
            resolveManualFillTargetPackage(
                activePackage = "com.current.app",
                packageNameToSkip = "takagi.ru.monica",
                expectedTargetPackage = null,
            )
        )
        assertNull(
            resolveManualFillTargetPackage(
                activePackage = "takagi.ru.monica",
                packageNameToSkip = "takagi.ru.monica",
                expectedTargetPackage = null,
            )
        )
    }
}
