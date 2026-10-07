package takagi.ru.monica.credentialexchange

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*

/** Debug-only host: recreate its composition with the Activity, not with the test rule. */
class CxfImportTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { content() }
    }

    companion object {
        var content by mutableStateOf<@Composable () -> Unit>({})
    }
}
