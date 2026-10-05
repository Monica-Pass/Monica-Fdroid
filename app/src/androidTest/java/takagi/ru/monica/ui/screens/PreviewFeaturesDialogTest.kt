package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import java.io.File

class PreviewFeaturesDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun remainingOptionsScrollAndCloseWithoutTheRetiredToggle() {
        var settings by mutableStateOf(AppSettings())
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                PreviewFeaturesDialog(settings,
                    onHideFabOnScrollChanged = { settings = settings.copy(hideFabOnScroll = it) },
                    onBitwardenStatusChanged = { settings = settings.copy(bitwardenBottomStatusBarEnabled = it) },
                    onReduceAnimationsChanged = { settings = settings.copy(reduceAnimations = it) },
                    onDismiss = { dismissed = true })
            }
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(3)
        compose.onNodeWithText(context.getString(R.string.hide_fab_on_scroll_title)).performClick()
        compose.runOnIdle { assertTrue(settings.hideFabOnScroll) }
        compose.onNodeWithText(context.getString(R.string.reduce_animations)).performScrollTo().assertIsDisplayed()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(context.filesDir, "preview-features.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(context.getString(R.string.close)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
