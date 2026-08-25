package takagi.ru.monica.baselineprofile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "takagi.ru.monica.fdroid"

@RunWith(AndroidJUnit4::class)
@RequiresApi(Build.VERSION_CODES.P)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
    ) {
        pressHome()
        startActivityAndWait()
        exercisePrimarySurface()
        openSettingsWhenAvailable()
    }

    @Test
    fun generateStartupProfile() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.exercisePrimarySurface() {
        device.wait(
            Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)),
            5_000L,
        )
        device.waitForIdle()

        val centerX = device.displayWidth / 2
        val startY = device.displayHeight * 3 / 4
        val endY = device.displayHeight / 3
        repeat(2) {
            device.swipe(centerX, startY, centerX, endY, 12)
            device.waitForIdle()
        }
        device.swipe(centerX, endY, centerX, startY, 12)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openSettingsWhenAvailable() {
        val settingsButton = device.findObject(By.descContains("设置"))
            ?: device.findObject(By.descContains("Settings"))
            ?: device.findObject(By.text("设置"))
            ?: device.findObject(By.text("Settings"))
        settingsButton?.click()
        device.waitForIdle()
    }
}
