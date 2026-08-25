package takagi.ru.monica.perf

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCompilationPerformanceGuardTest {

    @Test
    fun releaseUsesOptimizedR8AndConsumesMonicaBaselineProfile() {
        val settings = projectFile("settings.gradle").readText()
        val appBuild = projectFile("app/build.gradle").readText()
        val baselineProfile = projectFile("app/src/main/baseline-prof.txt").readText()
        val startupProfile = projectFile("app/src/main/startup-prof.txt").readText()

        assertTrue(settings.contains("include ':baselineprofile'"))
        assertTrue(appBuild.contains("proguard-android-optimize.txt"))
        assertTrue(baselineProfile.contains("Ltakagi/ru/monica/MainActivity;"))
        assertTrue(baselineProfile.contains("Ltakagi/ru/monica/ui/SimpleMainScreenKt;"))
        assertTrue(startupProfile.contains("Ltakagi/ru/monica/MonicaApplication;"))
        assertTrue(startupProfile.contains("Ltakagi/ru/monica/MainActivity;"))
    }

    @Test
    fun generatorCoversStartupAndPrimaryAuthenticatedSurfaces() {
        val generator = projectFile(
            "baselineprofile/src/main/java/takagi/ru/monica/baselineprofile/BaselineProfileGenerator.kt"
        ).readText()

        assertTrue(generator.contains("includeInStartupProfile = true"))
        assertTrue(generator.contains("startActivityAndWait()"))
        assertTrue(generator.contains("exercisePrimarySurface()"))
        assertTrue(generator.contains("openSettingsWhenAvailable()"))
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
