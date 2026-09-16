package takagi.ru.monica.localization

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.SettingsManager

@RunWith(AndroidJUnit4::class)
class ClassicalChineseImeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun installedKeyboardUsesTheSavedLanguageAndRefreshesWhileOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val manager = SettingsManager(context)
        val originalLanguage = runBlocking { manager.settingsFlow.first().language }
        val originalLocale = Locale.getDefault()
        val originalIme = shell("settings get secure default_input_method").trim()
        val component = "takagi.ru.monica/.ime.MonicaInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any {
            ComponentName.unflattenFromString(it.trim()) == ComponentName.unflattenFromString(component)
        }
        val originalFlags = automation.serviceInfo.flags
        assertTrue("The emulator must have a restorable input method", originalIme.matches(Regex("[A-Za-z0-9_.$/]+")) && originalIme != "null")
        lateinit var editor: EditText

        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            runBlocking { manager.updateLanguage(Language.CLASSICAL_CHINESE) }
            // A service launched outside the main activity must read the saved choice.
            Locale.setDefault(Locale.ENGLISH)
            shell("ime enable $component")
            shell("ime set $component")
            compose.setContent {
                AndroidView(factory = { viewContext ->
                    EditText(viewContext).apply { editor = this; hint = "Locale test" }
                })
            }
            compose.runOnIdle {
                editor.requestFocus()
                editor.post {
                    (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            waitForKeyboardLabel("自填")
            val screenshot = automation.takeScreenshot()
            File(context.getExternalFilesDir(null), "classical-chinese-keyboard.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()

            runBlocking { manager.updateLanguage(Language.ENGLISH) }
            waitForKeyboardLabel("Autofill")
            runBlocking { manager.updateLanguage(Language.CLASSICAL_CHINESE) }
            waitForKeyboardLabel("自填")
        } finally {
            shell("ime set '$originalIme'")
            if (!wasEnabled) shell("ime disable $component")
            runBlocking { manager.updateLanguage(originalLanguage) }
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            Locale.setDefault(originalLocale)
        }
    }

    private fun waitForKeyboardLabel(text: String) {
        try {
            compose.waitUntil(20_000) { text in keyboardLabels() }
        } catch (error: Throwable) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null), "classical-chinese-keyboard-failure.png")
                .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            throw AssertionError("Expected IME label '$text'; actual labels: ${keyboardLabels()}", error)
        }
    }

    private fun keyboardLabels(): List<String> {
        val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
        return try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .flatMap { window -> window.root?.let(::textLabels).orEmpty() }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun textLabels(node: AccessibilityNodeInfo): List<String> {
        return try {
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::textLabels).orEmpty() }
        } finally {
            node.recycle()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
