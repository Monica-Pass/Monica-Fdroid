package takagi.ru.monica.autofill_ng

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import takagi.ru.monica.autofill_ng.utils.SmartCopyNotificationHelper
import takagi.ru.monica.autofill_ng.utils.SmartCopyReceiver
import takagi.ru.monica.utils.ClipboardUtils

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class SmartCopyNotificationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private fun text() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    private fun notification() = manager.activeNotifications.single { it.id == 9527 }.notification
    private fun copyIntent() = Intent(shadowOf(notification().contentIntent).savedIntent)
    private fun queue() = SmartCopyNotificationHelper.copyAndQueueNext(context, "fixture-user", "Username", "fixture-password", "Password")
    @Before fun before() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
    }
    @After fun after() {
        SmartCopyNotificationHelper.dismissNotification(context)
        ClipboardUtils.cancelPendingAutoClear()
    }
    @Test fun copySequencePreservesFirstCopyAndCancelsSecondActionAfterUse() {
        assertTrue(queue())
        assertEquals("fixture-user", text())
        val n = notification()
        assertEquals(2, n.actions.size)
        assertEquals(60_000L, n.timeoutAfter)
        assertFalse(n.publicVersion.extras.toString().contains("fixture-"))
        val copy = copyIntent()
        SmartCopyReceiver().onReceive(context, copy)
        assertEquals("fixture-password", text())
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(SmartCopyNotificationHelper.isCurrentSession(context, copy))
        assertTrue(shadowOf(n.contentIntent).isCanceled)
    }
    @Test fun disabledNotificationStillCopiesFirstValue() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(queue())
        assertEquals("fixture-user", text())
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun brokenNotificationServiceDoesNotLoseTheFirstCopyOrCrash() {
        val broken = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.NOTIFICATION_SERVICE) throw IllegalStateException("fixture unavailable")
                return super.getSystemService(name)
            }
        }
        assertFalse(SmartCopyNotificationHelper.copyAndQueueNext(broken,"fixture-user","Username","fixture-password","Password"))
        assertEquals("fixture-user",text())
    }
    @Test fun dismissInvalidatesQueuedSecretAndOldActionCannotDismissNewSession() {
        queue()
        val oldCopy = copyIntent()
        val oldDismiss = Intent(shadowOf(notification().deleteIntent).savedIntent)
        queue()
        SmartCopyReceiver().onReceive(context, oldDismiss)
        SmartCopyReceiver().onReceive(context, oldCopy)
        assertEquals("fixture-user", text())
        assertEquals(1, manager.activeNotifications.size)
        val currentCopy = copyIntent()
        SmartCopyReceiver().onReceive(context, Intent(shadowOf(notification().deleteIntent).savedIntent))
        SmartCopyReceiver().onReceive(context, currentCopy)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals("fixture-user", text())
    }
    @Test fun expiredOrLockedActionsNeverCopyPassword() {
        queue()
        val copy = copyIntent()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        SmartCopyReceiver().onReceive(context, copy)
        assertEquals("fixture-user", text())
        assertEquals(1, manager.activeNotifications.size)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        copy.putExtra(SmartCopyReceiver.EXTRA_EXPIRES_AT, 0L)
        SmartCopyReceiver().onReceive(context, copy)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals("fixture-user", text())
    }
}
