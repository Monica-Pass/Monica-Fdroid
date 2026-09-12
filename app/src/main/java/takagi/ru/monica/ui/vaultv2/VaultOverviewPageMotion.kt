package takagi.ru.monica.ui.vaultv2

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import takagi.ru.monica.ui.navigation.EASY_NOTES_FADE_DURATION
import takagi.ru.monica.ui.navigation.EASY_NOTES_INITIAL_SCALE
import takagi.ru.monica.ui.navigation.EASY_NOTES_SCALE_DURATION

/** Animate the current page's layer while its list state and data pipeline stay in the pane. */
@Composable
internal fun rememberVaultOverviewPageMotion(showOverview: Boolean, enabled: Boolean): Modifier {
    val transition = updateTransition(showOverview, label = "VaultOverviewNavigation")
    val fadeProgress = transition.animateFloat(
        transitionSpec = {
            if (enabled) tween(EASY_NOTES_FADE_DURATION) else snap()
        },
        label = "VaultOverviewPageFade",
    ) { overview -> if (overview) 0f else 1f }
    val scaleProgress = transition.animateFloat(
        transitionSpec = {
            if (enabled) tween(EASY_NOTES_SCALE_DURATION) else snap()
        },
        label = "VaultOverviewPageScale",
    ) { overview -> if (overview) 0f else 1f }
    val targetProgress = if (showOverview) 0f else 1f

    return Modifier.graphicsLayer {
        // Read animation frames here, not during composition: sorting, Rust projection and
        // lazy-list measurement do not need to rerun for each frame. Only the current page
        // is composed, so outgoing content cannot pick up the destination's live filters.
        val remainingFade = if (enabled) abs(targetProgress - fadeProgress.value).coerceIn(0f, 1f) else 0f
        val remainingScale = if (enabled) abs(targetProgress - scaleProgress.value).coerceIn(0f, 1f) else 0f
        alpha = 1f - remainingFade
        scaleX = 1f - (1f - EASY_NOTES_INITIAL_SCALE) * remainingScale
        scaleY = scaleX
    }
}
