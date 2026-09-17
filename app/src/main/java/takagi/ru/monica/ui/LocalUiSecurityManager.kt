package takagi.ru.monica.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import takagi.ru.monica.security.SecurityManager

/** The Activity already initializes this dependency before opening the vault. */
internal val LocalUiSecurityManager = staticCompositionLocalOf<SecurityManager?> { null }

@Composable
internal fun rememberUiSecurityManager(): SecurityManager {
    LocalUiSecurityManager.current?.let { return it }
    val context = LocalContext.current.applicationContext
    // Standalone previews and test hosts do not have MonicaContent's provider.
    return remember(context) { SecurityManager(context) }
}
