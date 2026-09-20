package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/** Editors opened from a generator own their window, including their save FAB. */
@Composable
internal fun GeneratorEntryDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnClickOutside = false,
        securePolicy = SecureFlagPolicy.SecureOn
    )) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}
