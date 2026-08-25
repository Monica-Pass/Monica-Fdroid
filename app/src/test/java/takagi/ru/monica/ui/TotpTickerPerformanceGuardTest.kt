package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpTickerPerformanceGuardTest {

    @Test
    fun smoothProgressAnimatesTowardTheNextSecondAndResetsAtPeriodBoundary() {
        assertEquals(
            11f / 30f,
            nextSmoothTotpProgressTarget(progress = 10f / 30f, periodSeconds = 30),
            0.0001f,
        )
        assertEquals(
            1f,
            nextSmoothTotpProgressTarget(progress = 29f / 30f, periodSeconds = 30),
            0.0001f,
        )
        assertTrue(shouldResetSmoothTotpProgress(animatedProgress = 0.98f, sampledProgress = 0f))
        assertFalse(shouldResetSmoothTotpProgress(animatedProgress = 0.4f, sampledProgress = 0.35f))
    }

    @Test
    fun listCardsUseOneSecondSamplesWithoutCreatingSmoothPageTickerOrCardFallbacks() {
        val cardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/TotpCodeCard.kt"
        ).readText()
        val listSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt"
        ).readText()
        val steamSource = projectFile(
            "app/src/main/java/takagi/ru/monica/steam/ui/SteamScreen.kt"
        ).readText()
        val steamCodeContent = steamSource.substringAfter("private fun SteamCodeContent(")
            .substringBefore("private fun SteamAccountDetailContent(")

        assertTrue(cardSource.contains("sharedProgressTimeMillis == null && sharedTickSeconds == null"))
        assertTrue(cardSource.contains("val generationWindow"))
        assertTrue(cardSource.contains("Animatable(clampedProgress)"))
        assertTrue(cardSource.contains("durationMillis = 1000"))
        assertFalse(listSource.contains("val sharedProgressTimeMillis = rememberTotpTickerMillis"))
        assertFalse(listSource.contains("sharedProgressTimeMillis = sharedProgressTimeMillis"))
        assertTrue(steamCodeContent.contains("rememberTotpTickerMillis(smooth = false)"))
        assertFalse(steamCodeContent.contains("sharedProgressTimeMillis = sharedProgressTimeMillis"))
    }

    @Test
    fun expiryBlinkUsesDedicatedDrawLayerAnimationInsteadOfSecondSampleParity() {
        val cardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/TotpCodeCard.kt"
        ).readText()

        assertTrue(cardSource.contains("rememberInfiniteTransition(label = \"totp_expiry_blink\")"))
        assertTrue(cardSource.contains("RepeatMode.Reverse"))
        assertTrue(cardSource.contains("alpha = expiryBlinkAlpha.value"))
        assertFalse(cardSource.contains("progressTimeMillis / 500L"))
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
