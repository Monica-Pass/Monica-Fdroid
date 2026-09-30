package takagi.ru.monica.autofill_ng

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import takagi.ru.monica.autofill_ng.service.AutofillOtpNotificationService
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class AutofillOtpActionSafetyTest {
    @Test fun invalidAndUndecryptableSecretsAreNeverOfferedAsCodes() {
        listOf("", "!!!", "MDK|ciphertext", "V2|ciphertext", "C2|ciphertext", "A").forEach {
            assertFalse(AutofillOtpActions.hasUsableSecret(TotpData(secret = it)))
        }
        for (type in listOf(OtpType.TOTP, OtpType.HOTP, OtpType.STEAM, OtpType.YANDEX)) {
            assertTrue(AutofillOtpActions.hasUsableSecret(TotpData(secret = "JBSWY3DPEHPK3PXP", otpType = type)))
        }
        assertTrue(AutofillOtpActions.hasUsableSecret(TotpData(secret = "0123456789abcdef", otpType = OtpType.MOTP)))
    }

    @Test fun deniedNotificationsNeverStartForegroundService() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(AutofillOtpNotificationService.canShowNotification(context))
        AutofillOtpNotificationService.start(context, TotpData(secret = "JBSWY3DPEHPK3PXP"), "Fixture", 30)
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test fun disabledChannelIsRespectedAndNeverReplaced() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("autofill_otp", "Fixture", NotificationManager.IMPORTANCE_NONE))
        AutofillOtpNotificationService.start(context, TotpData(secret = "JBSWY3DPEHPK3PXP"), "Fixture", 30)
        assertFalse(AutofillOtpNotificationService.canShowNotification(context))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("autofill_otp").importance)
        assertNull(shadowOf(context as Application).nextStartedService)
    }
}
