package takagi.ru.monica.ui.cardwallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

private class WalletStackOverlayState {
    private var owner: Any? = null
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    fun show(owner: Any, content: @Composable () -> Unit) {
        this.owner = owner
        this.content = content
    }

    fun remove(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            content = null
        }
    }
}

private val LocalWalletStackOverlay = staticCompositionLocalOf<WalletStackOverlayState?> { null }

/** Covers the main scaffold, but stays inside its navigation scene and page transition. */
@Composable
internal fun WalletStackOverlayHost(content: @Composable () -> Unit) {
    val state = remember { WalletStackOverlayState() }
    CompositionLocalProvider(LocalWalletStackOverlay provides state) {
        Box(Modifier.fillMaxSize()) {
            val overlay = state.content
            Box(
                Modifier.fillMaxSize().then(
                    if (overlay != null) Modifier.clearAndSetSemantics {} else Modifier
                )
            ) {
                content()
            }
            if (overlay != null) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                // Own blank areas in hit testing without consuming the
                                // gestures handled by the browser's children.
                                awaitPointerEvent(PointerEventPass.Final)
                            }
                        }
                    }
                ) {
                    overlay()
                }
            }
        }
    }
}

/** Mount at the scene root so the wallet list's padding and gestures do not affect browsing. */
@Composable
internal fun WalletStackOverlay(content: @Composable () -> Unit) {
    val host = checkNotNull(LocalWalletStackOverlay.current) { "WalletStackOverlayHost is required" }
    val latestContent by rememberUpdatedState(content)
    DisposableEffect(host) {
        val owner = Any()
        host.show(owner) { latestContent() }
        onDispose { host.remove(owner) }
    }
}
