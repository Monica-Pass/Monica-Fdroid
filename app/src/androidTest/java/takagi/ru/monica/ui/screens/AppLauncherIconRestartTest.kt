package takagi.ru.monica.ui.screens

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.AppLauncherIcon
import takagi.ru.monica.data.AppLauncherLabel
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.SettingsManager

/** The device driver runs prepare / verify / restore around a real process stop. */
class AppLauncherIconRestartTest {
    @Test fun iconSelectionSurvivesRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = SettingsManager(context)
        val saved = File(context.filesDir, "launcher-icon-restart-original.json")
        val phase = InstrumentationRegistry.getArguments().getString("launcherPhase")
        suspend fun prepare() {
            check(!saved.exists()) { "Restore the previous launcher test fixture first" }
            val original = manager.settingsFlow.first()
            saved.writeText(JSONObject().put("icon", original.appLauncherIcon.name)
                .put("label", original.appLauncherLabel.name).put("language", original.language.name).toString())
            manager.updateLanguage(Language.CHINESE)
            manager.updateAppLauncherLabel(AppLauncherLabel.MONICA_PASS)
            manager.updateAppLauncherIcon(AppLauncherIcon.BLUE_STAR)
        }
        suspend fun verify() {
            assertEquals(AppLauncherIcon.BLUE_STAR, SettingsManager(context).settingsFlow.first().appLauncherIcon)
            val launchers = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0,
            )
            assertEquals(1, launchers.size)
            assertEquals("takagi.ru.monica.BlueStarVisibleLauncherAlias", launchers.single().activityInfo.name)
        }
        suspend fun restore() {
            val original = JSONObject(saved.readText())
            manager.updateAppLauncherLabel(AppLauncherLabel.valueOf(original.getString("label")))
            manager.updateLanguage(Language.valueOf(original.getString("language")))
            manager.updateAppLauncherIcon(AppLauncherIcon.valueOf(original.getString("icon")))
            check(saved.delete())
        }
        when (phase) {
            "prepare" -> prepare()
            "verify" -> verify()
            "restore" -> restore()
            null -> {
                // Normal suite execution remains independent of the external driver.
                prepare()
                try { verify() } finally { restore() }
            }
            else -> error("Unknown launcher test phase")
        }
    }
}
