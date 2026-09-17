package takagi.ru.monica.autofill_ng

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.autofill_ng.fixture.AutofillFormFixtureActivity
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.service.MonicaAccessibilityService
import takagi.ru.monica.utils.SettingsManager

/**
 * Runs against Android's real AutofillManager and AccessibilityService binder.
 * Requires an isolated Android user; see docs/autofill-quality-validation.md.
 */
@RunWith(AndroidJUnit4::class)
class AutofillFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val dao by lazy { PasswordDatabase.getDatabase(context).passwordEntryDao() }
    private val settings by lazy { SettingsManager(context) }
    private val preferences by lazy { AutofillPreferences(context) }
    private lateinit var security: SecurityManager
    private lateinit var ui: UiAutomation
    private var oldSettings: AppSettings? = null
    private var oldIme: String? = null
    private var accessibilityEnabledByTest = false
    private val insertedIds = mutableListOf<Long>()

    @Before fun setUp() = runBlocking {
        assumeTrue("Use a dedicated Android test user and opt in explicitly",
            android.os.Process.myUid() / 100000 > 0 &&
                InstrumentationRegistry.getArguments().getString("autofillIsolatedUser") == "true")
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        oldSettings = settings.settingsFlow.first()
        security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) security.setMasterPassword(MASTER_PASSWORD)
        check(security.unlockVaultWithPassword(MASTER_PASSWORD)) {
            "This user has another vault. Use the dedicated empty test user; never reset it here."
        }
        settings.updateAutofillAuthRequired(false)
        settings.updateBiometricEnabled(false)
        settings.updateAutoLockMinutes(5)
        preferences.setAutofillEnabled(true)
        preferences.setPasswordSuggestionEnabled(false)
        preferences.setV2RespectAutofillOffEnabled(true)
        SessionManager.attachAppContext(context)
        SessionManager.markUnlocked()
        insertedIds += dao.insertPasswordEntry(PasswordEntry(
            title = NATIVE_TITLE, website = "", appPackageName = fixturePackage,
            username = security.encryptData(AutofillFormFixtureActivity.USERNAME),
            password = security.encryptData(AutofillFormFixtureActivity.PASSWORD),
        ))
        insertedIds += dao.insertPasswordEntry(PasswordEntry(
            title = WEB_TITLE, website = AutofillFormFixtureActivity.ORIGIN,
            username = security.encryptData(AutofillFormFixtureActivity.USERNAME),
            password = security.encryptData(AutofillFormFixtureActivity.PASSWORD),
        ))
    }

    @After fun tearDown(): Unit = runBlocking {
        insertedIds.forEach { dao.deletePasswordEntryById(it) }
        oldSettings?.let {
            settings.updateAutofillAuthRequired(it.autofillAuthRequired)
            settings.updateBiometricEnabled(it.biometricEnabled)
            settings.updateAutoLockMinutes(it.autoLockMinutes)
            AutofillSessionGrants.clear()
        }
        oldIme?.let { selectIme(it) }
        if (accessibilityEnabledByTest) {
            val user = android.os.Process.myUid() / 100000
            val component = ComponentName(context, MonicaAccessibilityService::class.java)
            val others = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
            if (others.isEmpty()) shell("settings --user $user delete secure enabled_accessibility_services")
            else shell("settings --user $user put secure enabled_accessibility_services ${componentArgument(others.joinToString(":"))}")
            val end = SystemClock.elapsedRealtime() + 2000
            while (MonicaAccessibilityService.isCredentialFillAvailable(context) && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        }
    }

    @Test fun systemAutofillFillsStandardNativeLoginWithoutVerification() {
        open("standard")
        fillFromSystem(NATIVE_TITLE)
        expectStatus("user=OK password=OK")
    }

    @Test fun systemAutofillWithoutVerificationStillWorksAfterSessionLock() {
        SessionManager.markLocked()
        AutofillSessionGrants.clear()
        open("standard")
        fillFromSystem(NATIVE_TITLE)
        expectStatus("user=OK password=OK")
    }

    @Test fun systemAutofillRecognizesChineseEnterpriseFields() {
        open("enterprise")
        fillFromSystem(NATIVE_TITLE)
        expectStatus("user=OK password=OK")
    }

    @Test fun systemAutofillKeepsNativeLoginBesideHelpWebView() {
        open("mixed")
        fillFromSystem(NATIVE_TITLE)
        expectStatus("user=OK password=OK")
    }

    @Test fun systemAutofillSupportsUsernameThenPasswordInOneActivity() {
        open("step")
        fillFromSystem(NATIVE_TITLE)
        expectStatus("user=OK password=ABSENT")
        tap(waitNode { it.text?.toString()?.equals("Next step", ignoreCase = true) == true })
        fillFromSystem(NATIVE_TITLE)
        expectStatus("user=ABSENT password=OK")
    }

    @Test fun systemAutofillFillsWebViewAndDispatchesInputEvents() {
        open("web")
        waitNode { it.contentDescription?.toString() == "fixture-status" && it.text?.contains("web=READY") == true }
        fillFromSystem(WEB_TITLE, web = true)
        expectStatus("user=OK password=OK")
    }

    @Test fun systemAutofillInlineSelectionReturnsTheChosenCredential() {
        open("inline")
        fillFromSystem(NATIVE_TITLE)
        expectStatus("user=OK password=OK")
    }

    @Test fun manualSystemRequestSupportsAnUnlabelledField() {
        open("unlabelled")
        tap(waitNode { it.text?.toString()?.equals("Request autofill", ignoreCase = true) == true })
        tap(waitNode { it.text?.toString() == NATIVE_TITLE })
        expectStatus("user=OK password=ABSENT")
    }

    @Test fun verifiedFillReturnsToTheOriginalForm() = runBlocking {
        settings.updateAutofillAuthRequired(true)
        SessionManager.markLocked()
        AutofillSessionGrants.clear()
        open("standard")
        focusFirstField()
        tap(waitNode { it.text?.toString() == context.getString(R.string.autofill_unlock_monica) })
        verifyAndSelectCredential()
        expectStatus("user=OK password=OK")
    }

    @Test fun cancellingVerificationLeavesTheFormEmptyAndAllowsRetry() = runBlocking {
        settings.updateAutofillAuthRequired(true)
        SessionManager.markLocked()
        AutofillSessionGrants.clear()
        open("standard")
        focusFirstField()
        tap(waitNode { it.text?.toString() == context.getString(R.string.autofill_unlock_monica) })
        tap(waitNode { it.text?.toString() == context.getString(R.string.cancel) })
        expectStatus("user=EMPTY password=EMPTY")
        assertFalse(AutofillSessionGrants.isGranted(
            takagi.ru.monica.autofill_ng.auth.AutofillGrantContext(fixturePackage,null,null,null)))
        // Move focus without a coordinate tap: the returned suggestion can
        // overlap the first field and a second tap would already open it.
        assertTrue(waitNode { it.contentDescription?.toString() == "fixture-field-1" }
            .performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        tap(waitNode { it.text?.toString() == context.getString(R.string.autofill_unlock_monica) })
        verifyAndSelectCredential()
        expectStatus("user=OK password=OK")
    }

    @Test fun accessibilityFillsChineseFieldsWithNonstandardInputTypes() {
        open("plain_chinese", accessibilityOnly = true)
        assertTrue(fillWithAccessibility())
        expectStatus("user=OK password=OK")
    }

    @Test fun accessibilityHandlesBothStepsAndReportsSuccess() {
        open("step", accessibilityOnly = true)
        assertTrue(fillWithAccessibility())
        expectStatus("user=OK password=ABSENT")
        tap(waitNode { it.text?.toString()?.equals("Next step", ignoreCase = true) == true })
        assertTrue(fillWithAccessibility())
        expectStatus("user=ABSENT password=OK")
    }

    @Test fun accessibilityLeavesOtpAndSearchUntouched() {
        open("otp", accessibilityOnly = true)
        assertTrue(fillWithAccessibility())
        expectStatus("user=OK password=ABSENT otp=EMPTY")
        open("search", accessibilityOnly = true)
        assertFalse(fillWithAccessibility())
        expectStatus("other=EMPTY")
    }

    @Test fun accessibilityFillsWebViewAndDispatchesInputEvents() {
        open("web", accessibilityOnly = true)
        waitNode { it.contentDescription?.toString() == "fixture-status" && it.text?.contains("web=READY") == true }
        assertTrue(fillWithAccessibility(web = true))
        expectStatus("user=OK password=OK")
    }

    @Test fun accessibilityPickerReturnsToTheAppAndFillsBothFields() {
        open("enterprise", accessibilityOnly = true)
        fillThroughAccessibilityPicker(NATIVE_TITLE)
        expectStatus("user=OK password=OK")
    }

    @Test fun accessibilityPickerHandlesAWebViewThatReplacesThePasswordField() {
        open("web_dynamic", accessibilityOnly = true)
        waitNode { it.contentDescription?.toString() == "fixture-status" && it.text?.contains("web=READY") == true }
        fillThroughAccessibilityPicker(WEB_TITLE, web = true)
        expectStatus("user=OK password=OK")
    }

    @Test fun accessibilityWillNotFillAnotherActiveApp() {
        open("standard", accessibilityOnly = true)
        focusFirstField()
        awaitAccessibility()
        val result = runBlocking {
            withContext(Dispatchers.Main) {
                MonicaAccessibilityService.requestCredentialFill(
                    "some.other.app", AutofillFormFixtureActivity.USERNAME,
                    AutofillFormFixtureActivity.PASSWORD, false)
            }
        }
        assertFalse(result)
        expectStatus("user=EMPTY password=EMPTY")
    }

    private fun open(scenario: String, accessibilityOnly: Boolean = false) {
        selectIme(if (scenario == "inline") {
            requireNotNull(oldIme) { "Install an inline-capable keyboard for the inline test" }
        } else {
            "$fixturePackage/takagi.ru.monica.autofill_ng.fixture.AutofillFixtureInputMethodService"
        })
        context.startActivity(Intent().apply {
            component = ComponentName(fixturePackage,AutofillFormFixtureActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("scenario",scenario)
            putExtra("accessibilityOnly",accessibilityOnly)
        })
        waitNode { it.contentDescription?.toString() == "fixture-status" }
    }

    private fun focusFirstField(web: Boolean = false) {
        tap(waitNode { if (web) it.isEditable && it.packageName?.toString() == fixturePackage
            else it.contentDescription?.toString() == "fixture-field-0" })
    }

    private fun fillFromSystem(title: String, web: Boolean = false) {
        val started = SystemClock.elapsedRealtime()
        focusFirstField(web)
        tap(waitNode { it.text?.toString() == title || it.contentDescription?.contains(title) == true })
        android.util.Log.i("AutofillFlowTest","Suggestion selection after ${SystemClock.elapsedRealtime()-started}ms")
    }

    private fun verifyAndSelectCredential() {
        val masterField = waitNode { it.isEditable && it.packageName?.toString() == context.packageName }
        assertTrue(masterField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, MASTER_PASSWORD)
        }))
        tap(waitNode { it.text?.toString() == context.getString(R.string.confirm) })
        tap(waitNode { it.text?.toString() == NATIVE_TITLE })
    }

    private fun awaitAccessibility() {
        if (!MonicaAccessibilityService.isCredentialFillAvailable(context)) {
            // Instrumentation force-stops its target process. Rebind only our
            // service in the opted-in test user after that forced restart.
            val user = android.os.Process.myUid() / 100000
            val component = ComponentName(context, MonicaAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').filter { it.isNotBlank() }
            val otherServices = enabled.filter { ComponentName.unflattenFromString(it) != component }
            if (otherServices.isEmpty()) {
                shell("settings --user $user delete secure enabled_accessibility_services")
            } else {
                shell("settings --user $user put secure enabled_accessibility_services ${componentArgument(otherServices.joinToString(":"))}")
            }
            Thread.sleep(200)
            shell("settings --user $user put secure enabled_accessibility_services " +
                componentArgument((otherServices + component.flattenToString()).joinToString(":")))
            shell("settings --user $user put secure accessibility_enabled 1")
            accessibilityEnabledByTest = true
        }
        val end = SystemClock.elapsedRealtime() + 10000
        while (!MonicaAccessibilityService.isCredentialFillAvailable(context) && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(50)
        }
        assertTrue("Enable Monica accessibility for the isolated user",MonicaAccessibilityService.isCredentialFillAvailable(context))
    }

    private fun fillWithAccessibility(web: Boolean = false): Boolean {
        focusFirstField(web)
        awaitAccessibility()
        return runBlocking {
            withContext(Dispatchers.Main) {
                MonicaAccessibilityService.requestCredentialFill(
                    fixturePackage, AutofillFormFixtureActivity.USERNAME,
                    AutofillFormFixtureActivity.PASSWORD, false)
            }
        }
    }

    private fun fillThroughAccessibilityPicker(title: String, web: Boolean = false) {
        focusFirstField(web)
        awaitAccessibility()
        context.startActivity(Intent(context,AutofillPickerActivityV2::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE,true)
            putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_TARGET_PACKAGE,fixturePackage)
        })
        tap(waitNode { it.text?.toString() == title })
        // The regular vault list opens the entry's actions first.
        tap(waitNode { it.text?.toString() == context.getString(R.string.autofill) })
    }

    private fun expectStatus(expected: String) {
        waitNode { it.contentDescription?.toString() == "fixture-status" && it.text?.contains(expected) == true }
    }

    private fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 10000
        var fixtureStatus: String? = null
        do {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            ui.rootInActiveWindow?.let(queue::add)
            ui.windows.mapNotNull { it.root }.forEach(queue::add)
            var count = 0
            while (queue.isNotEmpty() && count++ < 600) {
                val node = queue.removeFirst()
                if (node.contentDescription?.toString() == "fixture-status") {
                    fixtureStatus = node.text?.toString()
                }
                if (predicate(node)) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            }
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        throw AssertionError("Expected autofill UI/status did not appear within 10 seconds; " +
            "activeRoot=${ui.rootInActiveWindow?.className}, windows=${ui.windows.size}, " +
            "flags=${ui.serviceInfo.flags}, fixtureStatus=$fixtureStatus")
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val rect = Rect().also(node::getBoundsInScreen)
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(ui.injectInputEvent(event,true)) } finally { event.recycle() }
        }
    }

    private fun selectIme(component: String) {
        val user = android.os.Process.myUid() / 100000
        listOf("ime enable --user $user ${componentArgument(component)}", "ime set --user $user ${componentArgument(component)}").forEach(::shell)
        check(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == component) {
            "Could not select test input method $component"
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { stream ->
            stream.bufferedReader().readText()
        }
    }

    private fun componentArgument(value: String): String {
        // UiAutomation passes this command to Runtime.exec, not to a shell.
        require(value.matches(Regex("[A-Za-z0-9_./:$]+"))) { "Invalid test component" }
        return value
    }

    companion object {
        private const val MASTER_PASSWORD = "Monica-Autofill-Test-2026!"
        private const val NATIVE_TITLE = "Autofill native test credential"
        private const val WEB_TITLE = "Autofill web test credential"
    }
}
