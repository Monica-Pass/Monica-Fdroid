package takagi.ru.monica.ui.common.pull

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

object PullSearchDefaults {
    val TriggerDistance = 64.dp
}

/** Draw only in the space exposed above the translated content, never over list rows. */
@Composable
fun PullSearchHint(
    currentOffset: Float,
    triggerDistance: Float = with(LocalDensity.current) { PullSearchDefaults.TriggerDistance.toPx() },
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.pull_release_to_search),
) {
    if (currentOffset <= 0f || triggerDistance <= 0f) return
    val progress = currentOffset / triggerDistance
    val tint by animateColorAsState(
        targetValue = if (progress >= 1f) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        animationSpec = tween(120),
        label = "pull_search_hint_tint",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { currentOffset.toDp() })
            .clipToBounds()
            .testTag("pull_search_hint"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = tint,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .graphicsLayer { alpha = calculatePullVisualProgress(progress) },
        )
    }
}
