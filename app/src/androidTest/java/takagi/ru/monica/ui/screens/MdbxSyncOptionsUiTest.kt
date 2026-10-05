package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.workers.MdbxAutoSyncPreferences

class MdbxSyncOptionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MdbxSyncOptionsTestActivity>()

    @Test fun remoteVaultOffersManualAndAutomaticWithoutLosingManualAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = compose.activity.databaseId
        val preferences = MdbxAutoSyncPreferences(context)
        try {
            val toggle = compose.onNode(isToggleable())
            toggle.assertIsOff().performClick().assertIsOn()
            assertTrue(preferences.isEnabled(id))
            compose.activityRule.scenario.recreate()
            compose.onNode(isToggleable()).assertIsOn()
            assertEquals(id, compose.activity.databaseId)
            capture(context, "mdbx-sync-options-enabled.png")
            toggle.performClick().assertIsOff()
            assertFalse(preferences.isEnabled(id))
            compose.onNodeWithText(context.getString(R.string.mdbx_sync_status_label)).performClick()
            assertEquals(1, compose.activity.manualCalls)
            capture(context, "mdbx-sync-options.png")
        } finally {
            preferences.setEnabled(id, false)
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("mdbx-auto-$id").result.get()
        }
    }

    private fun capture(context: android.content.Context, name: String) {
        compose.waitForIdle()
        // Wait for the release ripple so the artifact shows the resting state.
        android.os.SystemClock.sleep(500)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
