package takagi.ru.monica.ui.vaultv2

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.MainActivity
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.SettingsManager

/** Opt in with -e vaultScrollFreshInstall true on a fresh disposable emulator. */
@RunWith(AndroidJUnit4::class)
class VaultScrollbarInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun visibleCardPositions(): Map<String, Float> = compose.onAllNodes(
        SemanticsMatcher("vault password card") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("vault_item_password:") == true
        }, useUnmergedTree = true,
    ).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0f }
        .associate { it.config[SemanticsProperties.TestTag] to it.boundsInRoot.top }

    @Test fun actualVaultCardsMoveContinuouslyWhenDraggingTheScrollbar() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("vaultScrollFreshInstall") == "true" &&
            (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val security = SecurityManager(context)
        assumeFalse("Use a fresh disposable emulator", security.isMasterPasswordSet())
        val settings = SettingsManager(context)
        val database = PasswordDatabase.getDatabase(context)
        security.setMasterPassword("vault-scroll-verification")
        val encrypted = security.encryptData("synthetic-password")
        runBlocking {
            settings.updateQuickSetupCompleted(true)
            settings.updateLanguage(Language.ENGLISH)
            settings.updateBiometricEnabled(false)
            settings.updateScreenshotProtectionEnabled(false)
            settings.updateDynamicColorEnabled(false)
            settings.updateVaultOverviewEnabled(false)
            settings.updateBottomNavVisibility(BottomNavContentTab.VAULT_V2, true)
            BottomNavContentTab.entries.filter { it != BottomNavContentTab.VAULT_V2 }
                .forEach { settings.updateBottomNavVisibility(it, false) }
            database.passwordEntryDao().insertPasswordEntries(List(500) { index ->
                PasswordEntry(title = "Entry ${index.toString().padStart(4, '0')}", website = "",
                    username = "synthetic-user-$index", password = encrypted)
            })
        }
        SecurityManager.clearRuntimeUnlockCache()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("vault-scroll-verification")
            compose.onAllNodes(hasSetTextAction())[0].performImeAction()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("vault_scrollbar").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val scrollbar = compose.onNodeWithTag("vault_scrollbar")
            val density = context.resources.displayMetrics.density
            val movements = mutableListOf<Float>()
            compose.mainClock.autoAdvance = false
            try {
                scrollbar.performTouchInput {
                    down(Offset(centerX, 20f * density))
                    moveBy(Offset(0f, 28f * density))
                }
                compose.mainClock.advanceTimeBy(96)
                compose.waitForIdle()
                var previous = visibleCardPositions()
                assertTrue("Real vault cards must be visible", previous.isNotEmpty())
                repeat(30) {
                    scrollbar.performTouchInput { moveBy(Offset(0f, 0.25f)) }
                    compose.mainClock.advanceTimeBy(48)
                    compose.waitForIdle()
                    val current = visibleCardPositions()
                    // Ignore a partially clipped first card; compare cards visible in both samples.
                    val deltas = current.mapNotNull { (key, top) -> previous[key]?.minus(top) }.filter { it > 0f }
                    movements += deltas.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
                    previous = current
                }
                scrollbar.performTouchInput { up() }
                compose.mainClock.advanceTimeBy(96)
                compose.waitForIdle()
            } finally {
                compose.mainClock.autoAdvance = true
            }
            File(context.getExternalFilesDir("scroll-performance"), "actual-vault.json").writeText(
                JSONObject().put("entries", 500).put("sampleMovementPx", JSONArray(movements))
                    .put("advancingSamples", movements.count { it > 0f }).put("samples", movements.size).toString(2)
            )
            assertTrue("The real vault should move for each small thumb movement: $movements", movements.count { it > 0f } >= 27)
            assertTrue("Small thumb movement must not jump a whole card: $movements", movements.all { it < 80f * density })
        }
    }
}
