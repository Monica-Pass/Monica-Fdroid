package takagi.ru.monica.autofill_ng.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceRestartTest {
    private val other = "com.example.reader/.ReaderService"
    private val another = "org.example.helper/org.example.helper.Service"

    private class Settings(var value: String) : AccessibilityServiceSettings {
        val writes = mutableListOf<String>()
        var readCount = 0
        var onRead: (() -> Unit)? = null
        var onWrite: ((String) -> Unit)? = null
        override fun readEnabledServices(): String {
            readCount++
            onRead?.invoke()
            return value
        }
        override fun writeEnabledServices(value: String) {
            writes += value
            this.value = value
            onWrite?.invoke(value)
        }
    }

    @Test fun reconnectsOnlyMonicaAndPreservesExactOtherServicesAndOrder() {
        val original = "$other:$MONICA_ACCESSIBILITY_COMPONENT:$another"
        val settings = Settings(original)
        val result = AccessibilityServiceRestart(settings) {
            assertEquals("$other:$another", settings.value)
        }.restart()
        assertEquals(AccessibilityRecoveryResult.RESTART_REQUESTED, result)
        assertEquals(original, settings.value)
        assertEquals(listOf("$other:$another", original), settings.writes)
    }

    @Test fun neverRestartsTheMainEditionEvenWhenItsServiceIsEnabled() {
        val original = "$other:takagi.ru.monica/.service.MonicaAccessibilityService"
        val settings = Settings(original)
        assertEquals(AccessibilityRecoveryResult.NOT_ENABLED, AccessibilityServiceRestart(settings) {}.restart())
        assertEquals(original, settings.value)
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun restartsOnlyFdroidWhenBothEditionsAreEnabled() {
        val main = "takagi.ru.monica/takagi.ru.monica.service.MonicaAccessibilityService"
        val fdroid = "takagi.ru.monica.fdroid/takagi.ru.monica.service.MonicaAccessibilityService"
        val original = "$main:$other:$fdroid"
        val settings = Settings(original)
        assertEquals(AccessibilityRecoveryResult.RESTART_REQUESTED, AccessibilityServiceRestart(settings) {
            assertEquals("$main:$other", settings.value)
        }.restart())
        assertEquals(listOf("$main:$other", original), settings.writes)
    }

    @Test fun aServiceClassRelativeToTheFdroidPackageIsNotOurService() {
        val settings = Settings("takagi.ru.monica.fdroid/.service.MonicaAccessibilityService")
        assertEquals(AccessibilityRecoveryResult.NOT_ENABLED, AccessibilityServiceRestart(settings) {}.restart())
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun neverEnablesAServiceTheUserHasDisabled() {
        val settings = Settings(other)
        assertEquals(AccessibilityRecoveryResult.NOT_ENABLED, AccessibilityServiceRestart(settings) {}.restart())
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun anEmptyListDoesNotGrantAccessibilityConsent() {
        val settings = Settings("")
        assertEquals(AccessibilityRecoveryResult.NOT_ENABLED, AccessibilityServiceRestart(settings) {}.restart())
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun similarPackageAndClassNamesAreNotTreatedAsMonica() {
        val settings = Settings("takagi.ru.monica.evil/.service.MonicaAccessibilityService:$other")
        assertEquals(AccessibilityRecoveryResult.NOT_ENABLED, AccessibilityServiceRestart(settings) {}.restart())
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun concurrentChangeBeforeDisconnectAbortsWithoutWriting() {
        val settings = Settings("$MONICA_ACCESSIBILITY_COMPONENT:$other")
        settings.onRead = { if (settings.readCount == 2) settings.value = other }
        assertEquals(AccessibilityRecoveryResult.SETTINGS_CHANGED, AccessibilityServiceRestart(settings) {}.restart())
        assertEquals(other, settings.value)
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun concurrentChangeDuringDisconnectIsNeverOverwritten() {
        val settings = Settings("$MONICA_ACCESSIBILITY_COMPONENT:$other")
        val result = AccessibilityServiceRestart(settings) { settings.value = "$other:$another" }.restart()
        assertEquals(AccessibilityRecoveryResult.SETTINGS_CHANGED, result)
        assertEquals("$other:$another", settings.value)
        assertEquals(listOf(other), settings.writes)
    }

    @Test fun partiallyAppliedDisconnectIsRolledBackEvenWhenTheWriteThrows() {
        val original = "$MONICA_ACCESSIBILITY_COMPONENT:$other"
        val settings = Settings(original)
        settings.onWrite = { if (settings.writes.size == 1) throw IllegalStateException("simulated timeout after write") }
        assertEquals(AccessibilityRecoveryResult.FAILED, AccessibilityServiceRestart(settings) {}.restart())
        assertEquals(original, settings.value)
    }

    @Test fun interruptionBetweenWritesStillRestoresTheSetting() {
        val original = "$MONICA_ACCESSIBILITY_COMPONENT:$other"
        val settings = Settings(original)
        AccessibilityServiceRestart(settings) { throw InterruptedException() }.restart()
        assertEquals(original, settings.value)
    }

    @Test fun failedRestoreReportsFailureInsteadOfLoopingOrClaimingSuccess() {
        val settings = Settings("$MONICA_ACCESSIBILITY_COMPONENT:$other")
        settings.onWrite = {
            if (settings.writes.size == 2) {
                settings.value = other
                throw SecurityException("permission revoked")
            }
        }
        assertEquals(AccessibilityRecoveryResult.FAILED, AccessibilityServiceRestart(settings) {}.restart())
        assertEquals(2, settings.writes.size)
        assertEquals(other, settings.value)
    }

    @Test fun readFailureDoesNotMakeAnyChanges() {
        val settings = Settings(MONICA_ACCESSIBILITY_COMPONENT)
        settings.onRead = { throw SecurityException() }
        assertEquals(AccessibilityRecoveryResult.FAILED, AccessibilityServiceRestart(settings) {}.restart())
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun unknownHelperResultFailsClosed() {
        assertEquals(AccessibilityRecoveryResult.FAILED, AccessibilityRecoveryResult.fromCode(-100))
    }
}
