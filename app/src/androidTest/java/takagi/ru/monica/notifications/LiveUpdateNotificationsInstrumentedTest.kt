package takagi.ru.monica.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.service.AutofillOtpNotificationService
import takagi.ru.monica.autofill_ng.utils.SmartCopyNotificationHelper
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.PermissionRepository
import takagi.ru.monica.ui.screens.PermissionManagementScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.viewmodel.PermissionViewModel

/** Only synthetic notifications in an explicitly isolated test user; never touches the vault. */
class LiveUpdateNotificationsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val store = ViewModelStore()
    private val existingChannels = mutableSetOf<String>()
    private val data = TotpData(secret = "JBSWY3DPEHPK3PXP")
    private var originalPromotion: Boolean? = null
    private val requirePromotion get() = InstrumentationRegistry.getArguments().getString("requireLivePromotion") == "true"
    @Before fun before() {
        Assume.assumeTrue(android.os.Process.myUid() / 100000 > 0 &&
            InstrumentationRegistry.getArguments().getString("autofillIsolatedUser") == "true")
        listOf("autofill_otp", "smart_copy_channel").filterTo(existingChannels) { manager.getNotificationChannel(it) != null }
        assertTrue("Grant ordinary notification permission to the isolated test user", manager.areNotificationsEnabled())
        assertFalse(LiveUpdateNotifications.isDeviceLocked(context))
        if (requirePromotion) {
            assertTrue(Build.VERSION.SDK_INT >= 36)
            originalPromotion = LiveUpdateNotifications.canPostPromotedNotifications(context)
            setPromotionEnabled(true)
        }
    }
    @After fun after() {
        context.stopService(Intent(context, AutofillOtpNotificationService::class.java))
        SmartCopyNotificationHelper.dismissNotification(context)
        ClipboardUtils.cancelPendingAutoClear()
        originalPromotion?.let { setPromotionEnabled(it) }
        compose.runOnIdle { store.clear() }
        listOf("autofill_otp", "smart_copy_channel").filterNot { it in existingChannels }
            .forEach { manager.deleteNotificationChannel(it) }
    }
    private fun foreground() {
        compose.setContent { MonicaTheme { Text("Monica synthetic notification test") } }
        compose.waitForIdle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
        assertTrue("Notification condition timed out", condition())
    }
    private fun current(id: Int) = manager.activeNotifications.firstOrNull { it.id == id }?.notification
    private fun clipboard(): String? {
        var result: String? = null
        instrumentation.runOnMainSync { result = context.getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString() }
        return result
    }
    private fun assertPresentation(n: Notification) {
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        assertTrue(n.publicVersion.actions.isNullOrEmpty())
        assertFalse(n.publicVersion.extras.toString().contains("Fixture"))
        assertFalse(n.publicVersion.extras.toString().contains("fixture-password"))
        assertNull(n.contentView)
        val requested = n.extras.getBoolean("android.requestPromotedOngoing")
        if (requirePromotion) {
            assertTrue("Newer system must exercise the promoted path", requested)
            assertTrue("System must actually promote the notification", n.flags and Notification.FLAG_PROMOTED_ONGOING != 0)
        }
        assertEquals(Build.VERSION.SDK_INT >= 36 && LiveUpdateNotifications.canPostPromotedNotifications(context), requested)
        if (requested) assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 36) {
            if (requested) {
                assertTrue("Notification must satisfy platform promotion requirements", n.hasPromotableCharacteristics())
                assertFalse(n.shortCriticalText.isNullOrBlank())
                assertFalse(n.shortCriticalText.orEmpty().contains("fixture"))
            }
            File(context.filesDir,"live-updates-presentation-${n.channelId}.json").writeText(
                org.json.JSONObject().put("sdk",Build.VERSION.SDK_INT).put("requested",requested)
                    .put("eligible",n.hasPromotableCharacteristics())
                    .put("promoted",n.flags and Notification.FLAG_PROMOTED_ONGOING != 0)
                    .put("shortCriticalText",n.shortCriticalText).toString(2))
        }
    }
    private fun assertCanceled(action: PendingIntent) {
        try { action.send(); fail("Closed notification action was still valid") }
        catch (_: PendingIntent.CanceledException) { }
    }
    private fun screenshot(name: String) {
        val ui = instrumentation.uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            ui.executeShellCommand("cmd statusbar expand-notifications")
        ).use { it.readBytes() }
        val expectedTitle = if (name.startsWith("otp")) "Fixture OTP"
            else context.getString(R.string.smart_copy_notification_copy_password)
        fun containsTitle(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            return node.text?.contains(expectedTitle) == true ||
                (0 until node.childCount).any { containsTitle(node.getChild(it)) }
        }
        // Wait for the rendered notification, not an arbitrary 500ms animation delay.
        // Slow/software-rendered systems can otherwise capture the unchanged test Activity.
        await { ui.rootInActiveWindow?.packageName?.toString() == "com.android.systemui" && containsTitle(ui.rootInActiveWindow) }
        ui.waitForIdle(300,3000)
        val image = checkNotNull(ui.takeScreenshot())
        File(context.filesDir, "live-updates-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
        ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        fun hasFocus(): Boolean {
            var focused = false
            instrumentation.runOnMainSync {
                focused = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .any { it.window.decorView.hasWindowFocus() }
            }
            return focused
        }
        val deadline = SystemClock.elapsedRealtime() + 1500
        while (!hasFocus() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
        if (!hasFocus() && ui.rootInActiveWindow?.packageName?.toString() == "com.android.systemui") {
            ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
        // Reading the clipboard in assertions requires focus, even though notification actions
        // can legitimately WRITE it while the app is in the background.
        await { hasFocus() }
    }
    @Test fun otpRefreshCopyAndDismissUseCurrentCodeAndNeverResurrect() {
        foreground()
        instrumentation.runOnMainSync { AutofillOtpNotificationService.start(context,data,"Fixture OTP",30) }
        await { current(12001) != null }
        val n = checkNotNull(current(12001))
        assertPresentation(n)
        assertEquals(2,n.actions.size)
        screenshot("otp-api${Build.VERSION.SDK_INT}")
        val before = TotpGenerator.generateOtp(data)
        n.actions[0].actionIntent.send()
        await { clipboard() == before || clipboard() == TotpGenerator.generateOtp(data) }
        n.actions[1].actionIntent.send()
        await { current(12001) == null }
        Thread.sleep(1300)
        assertNull(current(12001))
        assertCanceled(n.actions[0].actionIntent)
    }
    @Test fun otpReplacementInvalidatesOldActionsAndTimeoutStopsUpdates() {
        foreground()
        instrumentation.runOnMainSync { AutofillOtpNotificationService.start(context,data,"Fixture first",30) }
        await { current(12001) != null }
        val old = checkNotNull(current(12001))
        instrumentation.runOnMainSync { AutofillOtpNotificationService.start(context,data,"Fixture second",3) }
        await { current(12001)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.contains("second") == true }
        assertCanceled(old.actions[0].actionIntent)
        assertCanceled(old.actions[1].actionIntent)
        await { current(12001) == null }
        Thread.sleep(1100)
        assertNull(current(12001))
    }
    @Test fun smartCopyCopiesInOrderAndClosingInvalidatesTheNextCopy() {
        foreground()
        fun queue() = instrumentation.runOnMainSync {
            assertTrue(SmartCopyNotificationHelper.copyAndQueueNext(context,"fixture-user","Username","fixture-password","Password"))
        }
        queue()
        await { current(9527) != null }
        assertEquals("fixture-user",clipboard())
        val n = checkNotNull(current(9527))
        assertPresentation(n)
        assertEquals(60_000L,n.timeoutAfter)
        screenshot("smart-copy-api${Build.VERSION.SDK_INT}")
        n.actions[0].actionIntent.send()
        await { clipboard() == "fixture-password" && current(9527) == null }
        assertCanceled(n.contentIntent)
        queue()
        await { current(9527) != null }
        val second = checkNotNull(current(9527))
        second.actions[1].actionIntent.send()
        await { current(9527) == null }
        assertCanceled(second.contentIntent)
        assertEquals("fixture-user",clipboard())
    }
    @Test fun permissionRowIsPlatformGatedAndOpensSystemSettings() {
        val model = PermissionViewModel(context.applicationContext as android.app.Application)
        store.put("permissions",model)
        compose.setContent { MonicaTheme { PermissionManagementScreen({},model) } }
        val label = context.getString(R.string.permission_live_updates_name)
        compose.waitUntil(8000) { !model.isLoading.value }
        if (Build.VERSION.SDK_INT < 36) {
            compose.onNodeWithText(label).assertDoesNotExist()
            return
        }
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir,"live-updates-permissions-api36.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
        assertTrue(PermissionRepository(context).getAllPermissions().any { it.id == "LIVE_UPDATES" })
        val expected = checkNotNull(LiveUpdateNotifications.settingsIntent(context).resolveActivity(context.packageManager))
        compose.onNodeWithText(label).performClick()
        val ui = instrumentation.uiAutomation
        await { ui.rootInActiveWindow?.packageName?.toString() == expected.packageName }
        ui.waitForIdle(500,5000)
        Thread.sleep(500)
        val settingsImage = checkNotNull(ui.takeScreenshot())
        File(context.filesDir,"live-updates-system-settings-api36.png").outputStream().use { settingsImage.compress(Bitmap.CompressFormat.PNG,100,it) }
        settingsImage.recycle()
        ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun setPromotionEnabled(enabled: Boolean) {
        if (LiveUpdateNotifications.canPostPromotedNotifications(context) == enabled) return
        context.startActivity(LiveUpdateNotifications.settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ui = instrumentation.uiAutomation
        val expected = checkNotNull(LiveUpdateNotifications.settingsIntent(context).resolveActivity(context.packageManager))
        await { ui.rootInActiveWindow?.packageName?.toString() == expected.packageName }
        ui.waitForIdle(500,5000)
        fun switches(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> {
            if (node == null) return emptyList()
            return (if (node.isCheckable) listOf(node) else emptyList()) +
                (0 until node.childCount).flatMap { switches(node.getChild(it)) }
        }
        await { switches(ui.rootInActiveWindow).isNotEmpty() }
        val toggle = switches(ui.rootInActiveWindow).single { it.isEnabled }
        if (toggle.isChecked != enabled) assertTrue(toggle.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        await { LiveUpdateNotifications.canPostPromotedNotifications(context) == enabled }
        ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(500)
    }
}
